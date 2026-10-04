package io.github.kkursun.openplay.phone

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
class AudioTranscoder(format: MediaFormat, private val output: (ByteArray, Long) -> Unit) {
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

    /** 16-bit stereo from the decoder's PCM: 5.1 and 7.1 folded down (fronts, centre and surrounds), mono doubled. */
    private fun stereo(pcm: ByteBuffer): ByteArray {
        val samples = if (float) pcm.asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
        else pcm.asShortBuffer().let { b -> ShortArray(b.remaining()).also { b.get(it) }.map { it / 32768f }.toFloatArray() }
        val frames = samples.size / channels
        val out = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val c = 0.7071f
        for (f in 0 until frames) {
            val s = { i: Int -> samples[f * channels + i] }
            var (l, r) = when (channels) {
                1 -> s(0) to s(0)
                2 -> s(0) to s(1)
                // Android's order: FL FR FC LFE BL BR (SL SR)
                else -> (s(0) + c * s(2) + c * s(4)) / (1 + 2 * c) to (s(1) + c * s(2) + c * s(minOf(5, channels - 1))) / (1 + 2 * c)
            }
            l = l.coerceIn(-1f, 1f)
            r = r.coerceIn(-1f, 1f)
            out.putShort((l * 32767).toInt().toShort()).putShort((r * 32767).toInt().toShort())
        }
        return out.array()
    }

    fun release() {
        runCatching { decoder.stop(); decoder.release() }
        encoder?.release()
    }
}

/** Repackages a video the TV can't take as it is (an MKV, say, or one with subtitles to add) into HLS as it's read,
 * like ffmpeg does on the PC: H.264 copied as it is, sound copied if it's AAC and converted to AAC if not. */
class Remuxer(private val open: () -> MediaExtractor, private val info: Probe, dir: File, copyAudio: Boolean) {
    private val transcode = info.audio != null && !(copyAudio && info.audio == "aac")
    val writer = HlsWriter(dir, SEGMENT, 0, info.video != null, info.audio != null)
    @Volatile var failure: String? = null
        private set
    @Volatile private var stopped = false

    private class Frame(val data: ByteArray, val pts: Long, val key: Boolean)

    fun start() = Thread(::run, "remux").apply { isDaemon = true }.start()

    fun stop() {
        stopped = true
    }

    private fun csd(format: MediaFormat?, key: String): ByteArray =
        format?.getByteBuffer(key)?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } } ?: ByteArray(0)

    private fun run() {
        var ex: MediaExtractor? = null
        var transcoder: AudioTranscoder? = null
        try {
            ex = open()
            val sound = ArrayDeque<Pair<ByteArray, Long>>() // waiting to be interleaved with the video
            if (info.videoTrack >= 0) {
                ex.selectTrack(info.videoTrack)
                writer.muxer.videoConfig(csd(info.videoFormat, "csd-0") + csd(info.videoFormat, "csd-1"))
            }
            if (info.audioTrack >= 0) ex.selectTrack(info.audioTrack)
            val asc = if (transcode || info.audioTrack < 0) null else TsMuxer.audioConfig(csd(info.audioFormat, "csd-0"))
            if (transcode) transcoder = AudioTranscoder(info.audioFormat!!) { frame, pts -> sound.addLast(frame to pts) }
            if (asc != null && asc.first == 5) writer.audioCodec = "mp4a.40.5"
            val times = DecodeTimes<Frame>()
            val write = { f: Frame, dts: Long ->
                while (sound.isNotEmpty() && sound.first().second <= dts) sound.removeFirst().let { (a, pts) -> writer.audio(a, pts) }
                writer.video(f.data, f.pts, dts, f.key)
            }
            val buffer = ByteBuffer.allocateDirect(16 shl 20)
            while (!stopped) {
                val track = ex.sampleTrackIndex
                if (track < 0) break
                val n = ex.readSampleData(buffer, 0)
                if (n < 0) break
                val data = ByteArray(n)
                buffer.duplicate().apply { position(0); limit(n) }.get(data)
                val pts = ex.sampleTime
                when {
                    track == info.videoTrack -> {
                        val key = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                        times.add(Frame(data, pts, key), pts).forEach { (f, dts) -> write(f, dts) }
                    }
                    transcoder != null -> transcoder.feed(data, pts)
                    asc != null -> sound.addLast(TsMuxer.adts(data, asc.first, asc.second, asc.third) to pts)
                }
                if (info.videoTrack < 0) while (sound.isNotEmpty()) sound.removeFirst().let { (a, p) -> writer.audio(a, p) }
                ex.advance()
            }
            if (stopped) return
            transcoder?.finish()
            times.flush().forEach { (f, dts) -> write(f, dts) }
            while (sound.isNotEmpty()) sound.removeFirst().let { (a, pts) -> writer.audio(a, pts) }
            writer.finish()
        } catch (e: Exception) { // MediaExtractor and MediaCodec throw all sorts
            if (!stopped) failure = if (e is IllegalStateException && e !is MediaCodec.CodecException) e.message else "Couldn't read the video: $e"
        } finally {
            transcoder?.release()
            ex?.release()
        }
    }
}
