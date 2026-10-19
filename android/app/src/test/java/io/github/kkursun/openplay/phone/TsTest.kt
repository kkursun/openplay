package io.github.kkursun.openplay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Streams made by the muxer, checked by ffmpeg (skipped where it isn't installed). */
class TsTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var dir: File

    @Before
    fun needFfmpeg() {
        assumeTrue("ffmpeg isn't installed", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        dir = tmp.newFolder()
    }

    /** Runs ffmpeg or ffprobe; returns what it printed. */
    private fun run(vararg args: String): String {
        val p = ProcessBuilder(*args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
        return out.trim()
    }

    /** H.264 in Annex B, split at its access unit delimiters. */
    private fun accessUnits(data: ByteArray): List<ByteArray> {
        val starts = (0 until data.size - 4).filter {
            data[it].toInt() == 0 && data[it + 1].toInt() == 0 && data[it + 2].toInt() == 0 && data[it + 3].toInt() == 1 && data[it + 4].toInt() and 0x1F == 9
        }
        return starts.mapIndexed { n, s -> data.copyOfRange(s, starts.getOrElse(n + 1) { data.size }) }
    }

    private fun adtsFrames(data: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i + 7 <= data.size) {
            val length = ((data[i + 3].toInt() and 3) shl 11) or ((data[i + 4].toInt() and 0xFF) shl 3) or ((data[i + 5].toInt() and 0xFF) shr 5)
            frames += data.copyOfRange(i, i + length)
            i += length
        }
        return frames
    }

    private fun frames(playlist: String) = run("ffprobe", "-v", "error", "-count_frames", "-select_streams", "v:0",
        "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", playlist).lines().first().toInt() // HLS lists it per program too

    @Test
    fun segmentsPlay() {
        val src = arrayOf("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30:duration=5", "-f", "lavfi", "-i", "sine=duration=5:sample_rate=48000")
        run("ffmpeg", "-v", "error", *src, "-map", "0", "-c:v", "libx264", "-bf", "0", "-g", "30", "-bsf:v", "h264_metadata=aud=insert", "-f", "h264", "v.h264")
        run("ffmpeg", "-v", "error", *src, "-map", "1", "-c:a", "aac", "-f", "adts", "a.aac")
        val video = accessUnits(File(dir, "v.h264").readBytes())
        val audio = adtsFrames(File(dir, "a.aac").readBytes())
        assertEquals(150, video.size)
        val out = File(dir, "hls").apply { mkdirs() }
        val writer = HlsWriter(out, 2.0, 0, hasVideo = true, hasAudio = true)
        writer.muxer.videoConfig(video[0]) // SPS and PPS, which x264 only writes at the start
        val samples = video.mapIndexed { i, au -> Triple(i * 1_000_000L / 30, au, true) } + audio.mapIndexed { j, f -> Triple(j * 1024 * 1_000_000L / 48000, f, false) }
        for ((pts, data, isVideo) in samples.sortedBy { it.first }) {
            if (isVideo) writer.video(data, pts, pts, (pts / 1000) % 1000 == 0L) else writer.audio(data, pts)
        }
        writer.finish()

        val playlist = File(out, "live.m3u8").readText()
        assertTrue(playlist, playlist.endsWith("live2.ts\n#EXT-X-ENDLIST\n"))
        assertTrue(playlist, "#EXTINF:2.000000,\nlive0.ts\n#EXTINF:2.000000,\nlive1.ts\n" in playlist)
        assertTrue(writer.muxer.videoCodec(), Regex("avc1\\.64[0-9A-F]{4}").matches(writer.muxer.videoCodec())) // High profile
        for (n in 0..2) { // every segment stands alone: tables and SPS/PPS at its start
            assertEquals("h264,320,180\naac", run("ffprobe", "-v", "error", "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", "hls/live$n.ts"))
        }
        assertEquals("", run("ffmpeg", "-v", "warning", "-i", "hls/live.m3u8", "-f", "null", "-"))
        assertEquals(150, frames("hls/live.m3u8"))
    }

    @Test
    fun reorderedFramesGetDecodeTimes() {
        run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=30:duration=4", "-c:v", "libx264", "-bf", "3", "-g", "60", "b.mp4")
        run("ffmpeg", "-v", "error", "-i", "b.mp4", "-c:v", "copy", "-bsf:v", "h264_mp4toannexb,h264_metadata=aud=insert", "-f", "h264", "b.h264")
        // Presentation times in decode order, all MediaExtractor gives.
        val packets = run("ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "packet=pts_time,flags", "-of", "csv=p=0", "b.mp4")
            .lines().map { it.split(",") }.map { Math.round(it[0].toDouble() * 1e6) to ('K' in it[1]) }
        val video = accessUnits(File(dir, "b.h264").readBytes())
        assertEquals(packets.size, video.size)
        assertTrue("x264 reorders", packets.zipWithNext().any { (a, b) -> b.first < a.first })
        val out = File(dir, "hls").apply { mkdirs() }
        val writer = HlsWriter(out, SEGMENT, 0, hasVideo = true, hasAudio = false)
        writer.muxer.videoConfig(video[0])
        val times = DecodeTimes<Int>()
        val write = { i: Int, dts: Long -> writer.video(video[i], packets[i].first, dts, packets[i].second) }
        for (i in video.indices) times.add(i, packets[i].first).forEach { (n, dts) -> write(n, dts) }
        times.flush().forEach { (n, dts) -> write(n, dts) }
        writer.finish()

        assertEquals("", run("ffmpeg", "-v", "warning", "-i", "hls/live.m3u8", "-f", "null", "-"))
        assertEquals(120, frames("hls/live.m3u8"))
        val muxed = run("ffprobe", "-v", "error", "-show_entries", "packet=pts,dts", "-of", "csv=p=0", "hls/live0.ts")
            .lines().filter { it.isNotBlank() }.map { it.split(",").take(2).map(String::toLong) }
        assertEquals(120, muxed.size) // all 4 s: two GOPs, short of a second segment
        assertTrue(muxed.all { (pts, dts) -> dts <= pts })
        assertTrue(muxed.zipWithNext().all { (a, b) -> b[1] > a[1] })
    }

    /** Converted video as the phone muxes it: frames out of the encoder in order, without B-frames, but well behind
     * the sound read alongside the source (the decoder and encoder hold frames back), at 23.976 fps with times
     * rounded to the millisecond like an MKV's. */
    @Test
    fun convertedVideoLateBehindTheSound() {
        val src = arrayOf("-f", "lavfi", "-i", "testsrc2=size=320x180:rate=24000/1001:duration=10", "-f", "lavfi", "-i", "sine=duration=10:sample_rate=48000")
        run("ffmpeg", "-v", "error", *src, "-map", "0", "-c:v", "libx264", "-bf", "0", "-g", "48", "-bsf:v", "h264_metadata=aud=insert", "-f", "h264", "v.h264")
        run("ffmpeg", "-v", "error", *src, "-map", "1", "-c:a", "aac", "-f", "adts", "a.aac")
        val video = accessUnits(File(dir, "v.h264").readBytes())
        val audio = adtsFrames(File(dir, "a.aac").readBytes())
        assertEquals(240, video.size)
        val out = File(dir, "hls").apply { mkdirs() }
        val writer = HlsWriter(out, SEGMENT, 0, hasVideo = true, hasAudio = true)
        writer.muxer.videoConfig(video[0])
        val mux = Interleaver(writer, hasVideo = true)
        val pts = { i: Int -> i * 1_001_000_000L / 24000 / 1000 * 1000 } // whole milliseconds
        // The source as read: video and sound in time order. Each video frame comes out of the encoder 20 later.
        val read = video.indices.map { pts(it) to it } + audio.indices.map { it * 1024 * 1_000_000L / 48000 to -1 - it }
        val held = ArrayDeque<Int>()
        for ((t, n) in read.sortedBy { it.first }) {
            if (n < 0) { mux.audio(audio[-1 - n], t); continue }
            held.addLast(n)
            if (held.size > 20) held.removeFirst().let { mux.video(video[it], pts(it), it % 48 == 0) }
        }
        held.forEach { mux.video(video[it], pts(it), it % 48 == 0) }
        mux.finish()

        assertEquals("", run("ffmpeg", "-v", "warning", "-i", "hls/live.m3u8", "-f", "null", "-"))
        assertEquals(240, frames("hls/live.m3u8"))
        val segments = Regex("live\\d+\\.ts").findAll(File(out, "live.m3u8").readText()).map { it.value }.toList()
        assertEquals(listOf("live0.ts", "live1.ts", "live2.ts"), segments) // a keyframe every 2 s, cut after 4
        var sound = 0
        for (segment in segments) {
            // Each segment holds the sound of its own stretch of the video (players fetch them one by one), to within
            // 0.1 s (ffprobe's AAC parser also puts sound a frame later than it is).
            val packets = run("ffprobe", "-v", "error", "-show_entries", "packet=codec_type,pts,dts", "-of", "csv=p=0", "hls/$segment")
                .lines().filter { it.isNotBlank() }.map { it.split(",") }
            val v = packets.filter { it[0] == "video" }.map { it[2].toLong() }
            val a = packets.filter { it[0] == "audio" }.map { it[1].toLong() }
            assertTrue(segment, v.zipWithNext().all { (x, y) -> y > x })
            assertTrue("$segment: sound starts at ${a.first()}, video at ${v.first()}", a.first() in v.first()..v.first() + 9000)
            assertTrue("$segment: sound ends at ${a.last()}, video at ${v.last()}", a.last() in v.last() - 9000..v.last() + 9000)
            sound += a.size
        }
        assertTrue("$sound of ${audio.size} sound frames", sound >= audio.size - 2) // only what came before the first frame left out
    }

    @Test
    fun decodeTimesStayBehindAndRise() {
        val pts = listOf(0L, 3, 1, 2, 6, 4, 5, 9, 7, 8).map { it * 40_000 }
        val times = DecodeTimes<Int>(window = 4)
        val out = pts.indices.flatMap { times.add(it, pts[it]) } + times.flush()
        assertEquals(pts.indices.toList(), out.map { it.first })
        assertTrue(out.all { (i, dts) -> dts <= pts[i] })
        assertTrue(out.zipWithNext().all { (a, b) -> b.second > a.second })
    }
}
