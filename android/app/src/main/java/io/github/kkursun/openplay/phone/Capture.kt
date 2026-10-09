package io.github.kkursun.openplay.phone

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Bundle
import java.io.File
import kotlin.math.ceil

private const val RATE = 48000

/** Mirrors the phone's screen, and the sound apps play, into a live HLS stream in dir. */
class ScreenCapture(
    val projection: MediaProjection,
    private val dir: File,
    private val s: PhoneSettings,
    playlistSeconds: Double,
    private val dpi: Int,
    private val sound: Boolean, // false without the microphone permission sound capture needs
) {
    val writer = HlsWriter(dir, s.segment, maxOf(2, ceil(playlistSeconds / s.segment).toInt()), true, sound)
    @Volatile var failure: String? = null
        private set
    @Volatile private var running = true
    private lateinit var encoder: MediaCodec
    private var display: VirtualDisplay? = null
    private var record: AudioRecord? = null
    private val start = System.nanoTime() / 1000 // every timestamp counts from here

    fun start() {
        // A 16:9 picture whatever way the phone is held: Android fits the screen inside it, bars at the sides
        // when upright, so a landscape video or game fills the TV.
        val height = s.height
        val width = height * 16 / 9
        encoder = videoEncoder(width, height, s, s.segment)
        val surface = encoder.createInputSurface()
        encoder.start()
        display = projection.createVirtualDisplay("openplay", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        thread("mirror-video") { drainVideo() }
        thread("mirror-keyframes") {
            // Encoders round small keyframe intervals up; asking each segment keeps segments the length set.
            while (running) {
                Thread.sleep((s.segment * 1000).toLong())
                runCatching { encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            }
        }
        if (sound) thread("mirror-audio") { pumpAudio() }
    }

    private fun drainVideo() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val o = encoder.dequeueOutputBuffer(info, 100_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = encoder.outputFormat
                    val csd = listOf("csd-0", "csd-1").mapNotNull { f.getByteBuffer(it) }
                        .fold(ByteArray(0)) { acc, b -> acc + ByteArray(b.remaining()).also { b.duplicate().get(it) } }
                    if (csd.isNotEmpty()) writer.muxer.videoConfig(csd)
                }
                if (o < 0) continue
                val data = ByteArray(info.size)
                encoder.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }.get(data)
                encoder.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    writer.muxer.videoConfig(data)
                } else if (info.size > 0) {
                    val pts = info.presentationTimeUs - start
                    writer.video(data, pts, pts, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
            }
        } catch (e: Exception) {
            if (running) failure = "The video encoder stopped: $e"
        }
    }

    /** Sound locked to the wall clock, like the screen: gaps (nothing playing) become silence and anything running
     * ahead of the clock is dropped, so the TV's player never waits on one or the other. */
    @SuppressLint("MissingPermission") // only called with the permission granted
    private fun pumpAudio() {
        var aac: AacEncoder? = null
        try {
            val rec = playbackRecorder(projection, RATE)
            record = rec
            rec.startRecording()
            aac = AacEncoder(RATE, 160_000) { frame, pts -> writer.audio(frame, pts) }
            val begun = System.nanoTime() / 1000 - start
            var written = 0L // frames sent to the encoder
            val buffer = ByteArray(RATE / 10 * 4)
            while (running) {
                Thread.sleep(20)
                val due = (System.nanoTime() / 1000 - start - begun) * RATE / 1_000_000
                while (true) {
                    val n = rec.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                    if (n <= 0) break
                    if (written + n / 4 <= due + RATE / 5) { // drop audio >200 ms ahead of the clock
                        aac.encode(buffer.copyOf(n), begun + written * 1_000_000 / RATE)
                        written += n / 4
                    }
                }
                if (written < due - RATE / 10) { // >100 ms behind: nothing arrived, fill with silence
                    aac.encode(ByteArray(((due - written) * 4).toInt()), begun + written * 1_000_000 / RATE)
                    written = due
                }
            }
        } catch (e: Exception) {
            if (running) failure = "Sound capture stopped: $e"
        } finally {
            aac?.release()
        }
    }

    /** Why the stream stopped producing video, or null while it's healthy. */
    fun problem(): String? {
        failure?.let { return it }
        val age = (System.currentTimeMillis() - File(dir, "live.m3u8").lastModified()) / 1000.0
        if (File(dir, "live.m3u8").exists() && age > maxOf(5.0, 4 * s.segment)) {
            return "No new video for ${age.toInt()} s: the phone's encoder stalled."
        }
        return null
    }

    fun stop() {
        running = false
        runCatching { display?.release() }
        runCatching { record?.stop(); record?.release() }
        runCatching { encoder.stop(); encoder.release() }
        runCatching { projection.stop() }
    }

    private fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true }.start()
}

/** The H.264 encoder, set up for streaming, with a keyframe every keyframeSeconds. It asks for constant bitrate and
 * the Apple TV 3's profile and level too, unless the encoder refuses those. */
fun videoEncoder(width: Int, height: Int, s: PhoneSettings, keyframeSeconds: Double): MediaCodec =
    videoEncoder(width, height, s, keyframeSeconds, strict = true) ?: videoEncoder(width, height, s, keyframeSeconds, strict = false)
        ?: throw IllegalStateException("This phone's video encoder won't take ${width}x$height.")

private fun videoEncoder(width: Int, height: Int, s: PhoneSettings, keyframeSeconds: Double, strict: Boolean): MediaCodec? {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, s.bitrate * 1_000_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, s.fps)
        setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, s.fps.toFloat())
        setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeSeconds.toFloat())
        // A still screen sends no frames; repeating the last keeps segments coming.
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / s.fps)
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        setInteger(MediaFormat.KEY_PRIORITY, 0) // real time
        if (strict) {
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            // Encoders pick higher levels on their own; the Apple TV 3 decoder is only rated up to 4.0.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        }
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

/** Records the sound apps play (those that allow it), as 16-bit stereo at rate. Needs the microphone permission. */
@SuppressLint("MissingPermission")
fun playbackRecorder(projection: MediaProjection, rate: Int): AudioRecord {
    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
        .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
    val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
    return AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(rate * 4)
        .setAudioPlaybackCaptureConfig(config).build()
}
