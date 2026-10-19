package io.github.kkursun.openplay.phone

import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.roundToInt

/** Stereo 16-bit PCM in, ADTS frames of AAC-LC out, through output(frame, pts µs). */
class AacEncoder(private val rate: Int, bitrate: Int, private val output: (ByteArray, Long) -> Unit) {
    private val rateIndex = TsMuxer.RATES.indexOf(rate).also { require(it >= 0) { "AAC can't take $rate Hz sound" } }
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        start()
    }
    private val info = MediaCodec.BufferInfo()

    fun encode(pcm: ByteArray, pts: Long) {
        var at = 0
        while (at < pcm.size) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) { drain(); continue }
            val buffer = codec.getInputBuffer(i)!!
            buffer.clear()
            val n = minOf(buffer.remaining(), pcm.size - at) / 4 * 4
            buffer.put(pcm, at, n)
            codec.queueInputBuffer(i, 0, n, pts + at / 4 * 1_000_000L / rate, 0)
            at += n
        }
        drain()
    }

    fun finish() {
        while (true) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i >= 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            drain()
        }
        drain(end = true)
    }

    private fun drain(end: Boolean = false) {
        var waits = 0
        while (true) {
            val o = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || ++waits > 100) return
                continue
            }
            if (o < 0) continue // format changed: ADTS headers carry all it says
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val frame = ByteArray(info.size)
                codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }.get(frame)
                output(TsMuxer.adts(frame, 2, rateIndex, 2), info.presentationTimeUs)
            }
            codec.releaseOutputBuffer(o, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    fun release() = runCatching { codec.stop(); codec.release() }
}

/** Decodes sound a TV can't play (AC-3, DTS, MP3...) and encodes it as stereo AAC. */
class AudioTranscoder(format: MediaFormat, private val volume: Int = 100, private val output: (ByteArray, Long) -> Unit) {
    private val mime = format.getString(MediaFormat.KEY_MIME)!!
    private val decoder = try {
        MediaCodec.createDecoderByType(mime).apply { configure(format, null, null, 0); start() }
    } catch (e: Exception) {
        throw IllegalStateException("This phone can't decode the video's ${mime.substringAfter('/').uppercase()} sound to convert it. openplay on a PC can.")
    }
    private var encoder: AacEncoder? = null
    private var channels = 2
    private var float = false
    private val info = MediaCodec.BufferInfo()

    fun feed(data: ByteArray, pts: Long) {
        while (true) {
            val i = decoder.dequeueInputBuffer(10_000)
            if (i >= 0) {
                decoder.getInputBuffer(i)!!.apply { clear(); put(data) }
                decoder.queueInputBuffer(i, 0, data.size, pts, 0)
                break
            }
            drain(false)
        }
        drain(false)
    }

    fun finish() {
        while (true) {
            val i = decoder.dequeueInputBuffer(10_000)
            if (i >= 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            drain(false)
        }
        drain(true)
        encoder?.finish()
    }

    private fun setup(format: MediaFormat) {
        if (encoder != null) return
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        float = format.containsKey(MediaFormat.KEY_PCM_ENCODING) && format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (rate > 48000) throw IllegalStateException("The video's sound is $rate Hz, more than this phone converts. openplay on a PC can.")
        encoder = AacEncoder(rate, 192_000, output)
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            val o = decoder.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> setup(decoder.outputFormat)
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++waits > 100) return
                o >= 0 -> {
                    setup(decoder.outputFormat)
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }
                        encoder!!.encode(stereo(buffer.order(ByteOrder.nativeOrder())), info.presentationTimeUs)
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** 16-bit stereo from the decoder's PCM at the volume set: 5.1 and 7.1 folded down (fronts, centre and surrounds), mono doubled. */
    private fun stereo(pcm: ByteBuffer): ByteArray {
        val samples = if (float) pcm.asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        else pcm.asShortBuffer().let { b -> ShortArray(b.remaining()).also { b.get(it) }.map { it / 32768f }.toFloatArray() }
        val frames = samples.size / channels
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val c = 0.7071f
        val g = gain(volume)
        for (f in 0 until frames) {
            val s = { i: Int -> samples[f * channels + i] }
            var (l, r) = when (channels) {
                1 -> s(0) to s(0)
                2 -> s(0) to s(1)
                // Android's order: FL FR FC LFE BL BR (SL SR)
                else -> (s(0) + c * s(2) + c * s(4)) / (1 + 2 * c) to (s(1) + c * s(2) + c * s(minOf(5, channels - 1))) / (1 + 2 * c)
            }
            l = (l * g).coerceIn(-1f, 1f)
            r = (r * g).coerceIn(-1f, 1f)
            out.putShort((l * 32767).toInt().toShort()).putShort((r * 32767).toInt().toShort())
        }
        return out.array()
    }

    fun release() {
        runCatching { decoder.stop(); decoder.release() }
        encoder?.release()
    }
}

/** Converts video a TV can't play (HEVC, for the Apple TV 3) to H.264 with the phone's own decoder and encoder: each
 * decoded picture is drawn, through OpenGL, onto the encoder's input surface, scaled down to fit maxHeight (and 1920
 * wide) and with frames dropped to keep within maxFps. config gets the SPS and PPS, output the H.264 access units
 * (pts µs, keyframe) in decode order. */
class VideoTranscoder(
    format: MediaFormat, maxHeight: Int, private val maxFps: Double,
    private val config: (ByteArray) -> Unit, private val output: (ByteArray, Long, Boolean) -> Unit,
) {
    /** The size it's converted to, like "1920×800". */
    val size: String
    /** The H.264 level the encoder says its stream needs (41 for 4.1), 0 until known. */
    @Volatile var level = 0
        private set
    private val mime = format.getString(MediaFormat.KEY_MIME)!!
    private val drawer: FrameDrawer
    private val encoder: MediaCodec
    private lateinit var decoder: MediaCodec
    private val decoded = MediaCodec.BufferInfo()
    private val encoded = MediaCodec.BufferInfo()
    private val rate = FrameRate(maxFps)
    private var fed = 0 // frames given to the decoder
    private var made = 0 // and frames out of the encoder

    init {
        // Pictures the decoder rotates (phone recordings held upright) come out the other way round.
        val turned = format.getInteger(MediaFormat.KEY_ROTATION, 0) % 180 != 0
        val w = format.getInteger(if (turned) MediaFormat.KEY_HEIGHT else MediaFormat.KEY_WIDTH)
        val h = format.getInteger(if (turned) MediaFormat.KEY_WIDTH else MediaFormat.KEY_HEIGHT)
        val scale = minOf(1.0, 1920.0 / w, maxHeight.toDouble() / h)
        val width = (w * scale / 2).roundToInt() * 2 // encoders want even sizes
        val height = (h * scale / 2).roundToInt() * 2
        val bitrate = (width * height * maxFps * 0.14).toInt().coerceIn(2_000_000, 10_000_000) // ~8.7 Mbps at 1080p30
        size = "$width×$height"
        encoder = h264Encoder(width, height, maxFps, bitrate, strict = true, fast = true)
            ?: h264Encoder(width, height, maxFps, bitrate, strict = true, fast = false)
            ?: h264Encoder(width, height, maxFps, bitrate, strict = false, fast = false)
            ?: throw IllegalStateException("This phone's video encoder won't take ${width}x$height to convert the video. openplay on a PC can.")
        try {
            drawer = FrameDrawer(encoder.createInputSurface(), width, height)
        } catch (e: Exception) {
            encoder.release()
            throw e
        }
        encoder.start()
        // HDR video is asked to come out as SDR (Android 12 on, if the decoder can), as the Apple TV 3 shows nothing
        // else; and the decoder to run as fast as it can. Without either if it refuses.
        val hdr = format.getInteger(MediaFormat.KEY_COLOR_TRANSFER, 0) in setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)
        val sdr = { f: MediaFormat ->
            if (hdr && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) f.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        decoder = openDecoder(format) { sdr(it); fast(it) } ?: openDecoder(format) { sdr(it) } ?: openDecoder(format) {} ?: run {
            release()
            throw IllegalStateException("This phone can't decode the video's ${mime.substringAfter('/').uppercase()} picture to convert it. openplay on a PC can.")
        }
    }

    /** A decoder drawing onto the drawer's surface, with change made to its format; null if it refuses that. */
    private fun openDecoder(format: MediaFormat, change: (MediaFormat) -> Unit): MediaCodec? {
        val f = MediaFormat(format).also(change)
        val codec = try { MediaCodec.createDecoderByType(mime) } catch (_: Exception) { return null }
        return try {
            codec.configure(f, drawer.surface, null, 0)
            codec.start()
            codec
        } catch (_: Exception) {
            codec.release()
            null
        }
    }

    fun feed(data: ByteArray, pts: Long) {
        while (true) {
            val i = decoder.dequeueInputBuffer(10_000)
            if (i >= 0) {
                decoder.getInputBuffer(i)!!.apply { clear(); put(data) }
                decoder.queueInputBuffer(i, 0, data.size, pts, 0)
                break
            }
            drain(false)
        }
        drain(false)
        // Else the TV waits for the stream forever, without a word.
        if (++fed > 300 && made == 0) throw IllegalStateException("The phone's video decoder and encoder gave no video converting this one. openplay on a PC can.")
    }

    fun finish() {
        while (true) {
            val i = decoder.dequeueInputBuffer(10_000)
            if (i >= 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
            drain(false)
        }
        drain(true)
        encoder.signalEndOfInputStream()
        drainEncoder(true)
    }

    private fun drain(end: Boolean) {
        var waits = 0
        while (true) {
            drainEncoder(false) // so the encoder has room for the next picture
            val o = decoder.dequeueOutputBuffer(decoded, if (end) 10_000 else 0)
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end || ++waits > 100) return
                continue
            }
            if (o < 0) continue // format changed: the surface takes care of it
            waits = 0
            val render = decoded.size > 0 && rate.keep(decoded.presentationTimeUs)
            decoder.releaseOutputBuffer(o, render)
            if (render) drawer.draw(decoded.presentationTimeUs)
            if (decoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
        }
    }

    private fun drainEncoder(end: Boolean) {
        var waits = 0
        while (true) {
            val o = encoder.dequeueOutputBuffer(encoded, if (end) 10_000 else 0)
            when {
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++waits > 100) return
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = encoder.outputFormat
                    val csd = listOf("csd-0", "csd-1").mapNotNull { f.getByteBuffer(it) }
                        .fold(ByteArray(0)) { acc, b -> acc + ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                    if (csd.isNotEmpty()) config(capped(csd))
                }
                o >= 0 -> {
                    waits = 0
                    if (encoded.size > 0) {
                        val data = ByteArray(encoded.size)
                        encoder.getOutputBuffer(o)!!.apply { position(encoded.offset); limit(encoded.offset + encoded.size) }.get(data)
                        val key = encoded.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        if (encoded.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config(capped(data))
                        else {
                            made++
                            output(if (key) capped(data) else data, encoded.presentationTimeUs, key)
                        }
                    }
                    encoder.releaseOutputBuffer(o, false)
                    if (encoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** H.264 from the encoder with its SPS saying level 4.0 at most, the highest the Apple TV 3 plays. Encoders say
     * higher levels than they need (asked to run flat out, more so), though 1080p at 30 fps and this bitrate is
     * within 4.0's limits. */
    private fun capped(annexB: ByteArray): ByteArray {
        TsMuxer.capLevel(annexB, 40).let { if (it > 0) level = it }
        return annexB
    }

    fun release() {
        if (::decoder.isInitialized) runCatching { decoder.stop(); decoder.release() }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        drawer.release()
    }
}

/** Which frames to keep to play at no more than maxFps: none before the stream starts (pts 0), and none closer to the
 * last one kept than a frame at maxFps (with a quarter frame's leeway, for 29.97 and rounded timestamps). */
class FrameRate(maxFps: Double) {
    private val step = (1e6 / maxFps).toLong()
    private var next = 0L // the earliest the next frame kept can be, µs

    fun keep(pts: Long): Boolean {
        if (pts < 0 || pts < next - step / 4) return false
        next = maxOf(next, pts) + step
        return true
    }
}

/** Asks a codec to run as fast as it can, rather than at the pace of playing, which is how fast it's converted. */
private fun fast(format: MediaFormat) {
    format.setInteger(MediaFormat.KEY_PRIORITY, 1) // not real time
    format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) // Android's way of saying "flat out"
}

/** An H.264 encoder fed from a surface, at the Apple TV 3's profile and level (strict) and flat out (fast), or null
 * if it refuses those. Without B-frames, so frames come out in the order shown. */
private fun h264Encoder(width: Int, height: Int, fps: Double, bitrate: Int, strict: Boolean, fast: Boolean): MediaCodec? {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        setFloat(MediaFormat.KEY_FRAME_RATE, fps.toFloat())
        setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, (SEGMENT / 2).toFloat()) // segments are cut at keyframes
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        if (strict) {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        }
        if (fast) fast(this)
    }
    val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    return try {
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec
    } catch (_: Exception) {
        codec.release()
        null
    }
}

/** Draws the decoder's pictures onto an encoder's input surface with OpenGL, which scales them to width x height
 * (and turns, crops and converts their colours as the decoder says). Used from one thread: the one that made it. */
private class FrameDrawer(private val target: Surface, private val width: Int, private val height: Int) {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: android.opengl.EGLContext
    private val window: android.opengl.EGLSurface
    private val texture: Int
    private val program: Int
    private val matrix = FloatArray(16)
    private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)) // x, y, u, v
    private val frames = HandlerThread("transcode-frames").apply { start() }
    private val lock = Object()
    private var available = false
    private val pictures: SurfaceTexture
    /** Where the decoder draws. */
    val surface: Surface

    init {
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("OpenGL won't start: ${EGL14.eglGetError()}")
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0)
        if (count[0] == 0) throw IllegalStateException("OpenGL has no way to draw for the video encoder.")
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        window = EGL14.eglCreateWindowSurface(display, configs[0], target, intArrayOf(EGL14.EGL_NONE), 0)
        if (!EGL14.eglMakeCurrent(display, window, window, context)) throw IllegalStateException("OpenGL won't draw: ${EGL14.eglGetError()}")
        program = GLES20.glCreateProgram()
        for ((type, code) in listOf(GLES20.GL_VERTEX_SHADER to VERTEX, GLES20.GL_FRAGMENT_SHADER to FRAGMENT)) {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, code)
            GLES20.glCompileShader(shader)
            GLES20.glAttachShader(program, shader)
        }
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) throw IllegalStateException("OpenGL won't take the shaders: ${GLES20.glGetProgramInfoLog(program)}")
        texture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        pictures = SurfaceTexture(texture)
        // Called on its own thread: this one is waiting for it in draw().
        pictures.setOnFrameAvailableListener({ synchronized(lock) { available = true; lock.notifyAll() } }, Handler(frames.looper))
        surface = Surface(pictures)
    }

    /** Draws the picture the decoder just released onto the encoder's surface, to be shown at pts µs. */
    fun draw(pts: Long) {
        synchronized(lock) {
            val until = System.currentTimeMillis() + 5000
            while (!available) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) throw IllegalStateException("The phone's video decoder stopped giving pictures to convert.")
                lock.wait(left)
            }
            available = false
        }
        pictures.updateTexImage()
        pictures.getTransformMatrix(matrix)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "matrix"), 1, false, matrix, 0)
        for ((name, at) in listOf("position" to 0, "coord" to 2)) {
            val a = GLES20.glGetAttribLocation(program, name)
            GLES20.glEnableVertexAttribArray(a)
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 16, quad.position(at))
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, pts * 1000)
        EGL14.eglSwapBuffers(display, window)
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { pictures.release() }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, window)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
        runCatching { target.release() }
        frames.quitSafely()
    }

    companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val VERTEX = """
            uniform mat4 matrix;
            attribute vec4 position;
            attribute vec4 coord;
            varying vec2 at;
            void main() {
                gl_Position = position;
                at = (matrix * coord).xy;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES picture;
            varying vec2 at;
            void main() {
                gl_FragColor = texture2D(picture, at);
            }
        """
    }
}

/** Repackages a video the TV can't take as it is (an MKV, say, or one with subtitles to add) into HLS as it's read,
 * like ffmpeg does on the PC: H.264 copied as it is (or other video converted to H.264 that convertFor plays, if
 * given), sound copied if it's AAC and converted to AAC if not.
 * Resuming, it starts at the keyframe before start seconds in, with times counted from there. */
class Remuxer(
    private val open: () -> MediaExtractor, val info: Probe, dir: File, copyAudio: Boolean,
    private val volume: Int = 100, private val start: Double = 0.0, private val convertFor: Tv? = null,
) {
    private val transcode = info.audio != null && !(copyAudio && info.audio == "aac")
    val writer = HlsWriter(dir, SEGMENT, 0, info.video != null, info.audio != null)
    @Volatile var failure: String? = null
        private set
    @Volatile private var stopped = false
    /** Seconds into the video the stream starts at: 0, or where resuming started (known before any segment is). */
    @Volatile var offset = 0.0
        private set
    /** How many times faster than it plays the video is converted, 0 until it's known (or if it isn't converted). */
    @Volatile var speed = 0.0
        private set
    @Volatile private var video: VideoTranscoder? = null

    /** How converting the video is going, "" if it isn't converted (or hasn't started). */
    fun describe(): String {
        val v = video ?: return ""
        if (speed <= 0) return ""
        val level = if (v.level > 0) String.format(Locale.ROOT, ", encoder's level %.1f", v.level / 10.0) else ""
        return String.format(Locale.ROOT, "Converting to H.264 (%s%s) at %.1f× playing speed", v.size, level, speed)
    }

    fun start() = Thread(::run, "remux").apply { isDaemon = true }.start()

    fun stop() {
        stopped = true
    }

    private fun csd(format: MediaFormat?, key: String): ByteArray =
        format?.getByteBuffer(key)?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } } ?: ByteArray(0)

    private fun run() {
        var ex: MediaExtractor? = null
        var transcoder: AudioTranscoder? = null
        var converter: VideoTranscoder? = null
        try {
            ex = open()
            val mux = Interleaver(writer, info.videoTrack >= 0)
            if (info.videoTrack >= 0) {
                ex.selectTrack(info.videoTrack)
                // Converted video's comes from its encoder.
                if (convertFor == null) writer.muxer.videoConfig(csd(info.videoFormat, "csd-0") + csd(info.videoFormat, "csd-1"))
            }
            if (start > 0 && info.videoTrack >= 0) {
                // The keyframe before start, found with the video alone; the sound then starts there too.
                ex.seekTo((start * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                offset = maxOf(0L, ex.sampleTime) / 1e6
            } else if (start > 0) {
                offset = start
            }
            if (info.audioTrack >= 0) ex.selectTrack(info.audioTrack)
            if (offset > 0) ex.seekTo((offset * 1e6).toLong(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val shift = (offset * 1e6).toLong()
            val asc = if (transcode || info.audioTrack < 0) null else TsMuxer.audioConfig(csd(info.audioFormat, "csd-0"))
            if (transcode) transcoder = AudioTranscoder(info.audioFormat!!, volume, mux::audio)
            if (asc != null && asc.first == 5) writer.audioCodec = "mp4a.40.5"
            if (convertFor != null && info.videoTrack >= 0) {
                val began = System.nanoTime()
                converter = VideoTranscoder(info.videoFormat!!, convertFor.maxHeight, convertFor.maxFps, writer.muxer::videoConfig) { data, pts, key ->
                    val took = (System.nanoTime() - began) / 1e9
                    if (took > 2) speed = pts / 1e6 / took
                    mux.video(data, pts, key)
                }
                video = converter
            }
            val buffer = ByteBuffer.allocateDirect(16 shl 20)
            while (!stopped) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val n = ex.readSampleData(buffer, 0)
                if (n < 0) break
                val data = ByteArray(n)
                buffer.duplicate().apply { position(0); limit(n) }.get(data)
                val pts = ex.sampleTime - shift
                when {
                    track == info.videoTrack && converter != null -> converter.feed(data, pts)
                    track == info.videoTrack -> mux.video(data, pts, ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                    transcoder != null -> transcoder.feed(data, pts)
                    asc != null -> mux.audio(TsMuxer.adts(data, asc.first, asc.second, asc.third), pts)
                }
                ex.advance()
            }
            if (stopped) return
            transcoder?.finish()
            converter?.finish()
            mux.finish()
        } catch (e: Exception) { // MediaExtractor and MediaCodec throw all sorts
            if (!stopped) failure = if (e is IllegalStateException && e !is MediaCodec.CodecException) e.message else "Couldn't read the video: $e"
        } finally {
            transcoder?.release()
            converter?.release()
            ex?.release()
        }
    }
}
