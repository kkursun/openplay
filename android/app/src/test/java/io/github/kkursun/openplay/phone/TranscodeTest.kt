package io.github.kkursun.openplay.phone

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** What converting HEVC for the Apple TV 3 relies on that can be checked off a phone. */
class TranscodeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Which of count frames, fps apart and starting at first µs, a FrameRate of maxFps keeps. */
    private fun kept(fps: Double, maxFps: Double, count: Int = 12, first: Long = 0): List<Int> {
        val rate = FrameRate(maxFps)
        return (0 until count).filter { rate.keep(first + Math.round(it * 1e6 / fps)) }
    }

    @Test
    fun keepsEveryFrameAtOrBelowTheRate() {
        for (fps in listOf(23.976, 24.0, 25.0, 29.97, 30.0)) assertEquals("$fps fps", (0 until 12).toList(), kept(fps, 30.0))
    }

    @Test
    fun keepsEveryOtherFrameOf60() {
        assertEquals(listOf(0, 2, 4, 6, 8, 10), kept(60.0, 30.0))
        assertEquals(listOf(0, 2, 4, 6, 8, 10), kept(59.94, 30.0))
    }

    @Test
    fun keepsFramesOfAStreamStartedPartway() {
        assertEquals((0 until 12).toList(), kept(24.0, 30.0, first = 600_000_000))
    }

    @Test
    fun dropsFramesBeforeTheStart() {
        assertEquals((3 until 12).toList(), kept(30.0, 30.0, first = -100_000))
    }

    @Test
    fun capsTheLevelOfEverySps() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x33, 0xAC.toByte(), 0x2B)
        val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())
        val data = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps + byteArrayOf(0, 0, 1) + sps
        assertEquals(0x33, TsMuxer.capLevel(data, 40))
        val nals = TsMuxer.nalUnits(data)
        assertEquals(listOf(40, 40), nals.filter { TsMuxer.nalType(it) == 7 }.map { it[3].toInt() })
        assertEquals(pps.toList(), nals[1].toList())
        assertEquals(0x28, TsMuxer.capLevel(byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0xAC.toByte()), 40)) // left as it is
        assertEquals(0, TsMuxer.capLevel(byteArrayOf(0, 0, 0, 1) + pps, 40))
    }

    /** An encoder's level 5.1 stream, capped, reads as 4.0 and still decodes, by ffmpeg (skipped where it's missing). */
    @Test
    fun cappedStreamStillDecodes() {
        assumeTrue("ffmpeg isn't installed", runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() == 0 }.getOrDefault(false))
        val dir = tmp.newFolder()
        val run = { args: List<String> ->
            val p = ProcessBuilder(args).directory(dir).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(out, 0, p.waitFor())
            out
        }
        run(listOf("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30:duration=2", "-pix_fmt", "yuv420p", "-c:v", "libx264",
            "-profile:v", "high", "-level", "5.1", "-bf", "0", "-g", "30", "-f", "h264", "in.h264"))
        val data = File(dir, "in.h264").readBytes()
        assertEquals(51, TsMuxer.capLevel(data, 40))
        File(dir, "out.h264").writeBytes(data)
        assertEquals("40", run(listOf("ffprobe", "-v", "error", "-show_entries", "stream=level", "-of", "csv=p=0", "out.h264")).trim())
        assertEquals("60", run(listOf("ffprobe", "-v", "error", "-count_frames", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", "out.h264")).trim())
        assertEquals("", run(listOf("ffmpeg", "-v", "error", "-i", "out.h264", "-f", "null", "-")).trim())
    }
}
