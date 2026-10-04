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
