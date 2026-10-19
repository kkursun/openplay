package io.github.kkursun.openplay.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRateTest {
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
}
