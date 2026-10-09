package io.github.kkursun.openplay.phone

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.projection.MediaProjection
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

private const val AIR_RATE = 44100 // the PCM airmirror takes, 16-bit stereo

/** Mirrors the phone's screen, and the sound apps play, with AirPlay's own screen mirroring. airmirror (doubletake's
 * sender, built from the repository's airmirror/ into the APK as libairmirror.so) talks to the TV; this feeds it
 * H.264 and PCM through its stdin. No HLS buffer on the TV, so far less lag than ScreenCapture. */
class AirMirror(
    val projection: MediaProjection,
    private val exe: File,
    private val address: String,
    private val s: PhoneSettings,
    private val dpi: Int,
    private val sound: Boolean, // false without the microphone permission sound capture needs
) {
    @Volatile private var failure: String? = null
    @Volatile private var running = true
    private val log = ArrayDeque<String>() // airmirror's last lines, for errors
    private lateinit var process: Process
    private lateinit var stdin: OutputStream
    private var encoder: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var record: AudioRecord? = null

    /** Starts airmirror, and the capture once the TV has taken the session. Blocks until then. */
    fun start() {
        process = ProcessBuilder(listOfNotNull(exe.path, "-target", address, "-audio".takeIf { sound }))
            .redirectErrorStream(true).start()
        stdin = process.outputStream
        val lines = process.inputStream.bufferedReader()
        var canvas = 1280 to 720
        while (true) {
            val line = lines.readLine() ?: throw IllegalStateException("Mirroring failed: ${tail()}")
            remember(line)
            Regex("receiver canvas (\\d+)x(\\d+)").find(line)?.let { canvas = it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            if ("mirroring (data port" in line) break
        }
        thread("airmirror-log") { lines.forEachLine { remember(it) } }

        // The TV's screen size, as Apple's senders encode at, no taller than the height set. Android fits the
        // phone's screen inside it, bars at the sides when upright, so a landscape video or game fills the TV.
        val height = minOf(canvas.second, s.height) and 1.inv()
        val width = canvas.first * height / canvas.second and 1.inv()
        val codec = videoEncoder(width, height, s, keyframeSeconds = 2.0)
        encoder = codec
        val surface = codec.createInputSurface()
        codec.start()
        display = projection.createVirtualDisplay("openplay", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
        thread("mirror-video") { drainVideo(codec) }
        if (sound) thread("mirror-audio") { pumpAudio() }
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var config = ByteArray(0) // SPS and PPS, which airmirror wants ahead of a keyframe
        try {
            while (running) {
                val o = codec.dequeueOutputBuffer(info, 100_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    config = listOf("csd-0", "csd-1").mapNotNull { f.getByteBuffer(it) }.fold(ByteArray(0)) { acc, b -> acc + bytes(b) }
                }
                if (o < 0) continue
                val data = bytes(codec.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) })
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = data
                } else if (info.size > 0) {
                    send('v', if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) config + data else data)
                }
            }
        } catch (e: Exception) {
            if (running) failure = "The video encoder stopped: $e"
        }
    }

    /** Sound as it's recorded: airmirror paces it and drops any backlog itself. */
    private fun pumpAudio() {
        try {
            val rec = playbackRecorder(projection, AIR_RATE)
            record = rec
            rec.startRecording()
            val buffer = ByteArray(AIR_RATE / 100 * 4) // 10 ms
            while (running) {
                val n = rec.read(buffer, 0, buffer.size)
                if (n > 0) send('a', buffer.copyOf(n))
            }
        } catch (e: Exception) {
            if (running) failure = "Sound capture stopped: $e"
        }
    }

    /** One packet on airmirror's stdin: its type, its length, then the bytes. */
    private fun send(type: Char, data: ByteArray) {
        val head = ByteBuffer.allocate(5).put(type.code.toByte()).putInt(data.size).array()
        synchronized(stdin) {
            stdin.write(head)
            stdin.write(data)
            stdin.flush()
        }
    }

    /** Why mirroring stopped, or null while it's healthy. airmirror exits 0 when the TV's remote ends it. */
    fun problem(): String? {
        failure?.let { return it }
        if (!process.isAlive && process.exitValue() != 0) return "Mirroring stopped: ${tail()}"
        return null
    }

    /** True while mirroring, null once it ended. */
    fun alive(): Boolean? = if (process.isAlive) true else null

    fun stop() {
        running = false
        runCatching { display?.release() }
        runCatching { record?.stop(); record?.release() }
        runCatching { encoder?.stop(); encoder?.release() }
        runCatching { projection.stop() }
        if (::process.isInitialized) {
            runCatching { stdin.close() } // airmirror's cue to end the TV's session
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroy()
        }
    }

    private fun remember(line: String) = synchronized(log) {
        log.addLast(line)
        if (log.size > 5) log.removeFirst()
    }

    private fun tail() = synchronized(log) { log.joinToString(" / ") }.ifEmpty { "no output" }

    private fun bytes(b: ByteBuffer) = ByteArray(b.remaining()).also { b.duplicate().get(it) }

    private fun thread(name: String, body: () -> Unit) = Thread(body, name).apply { isDaemon = true }.start()
}
