package io.github.kkursun.openplay.phone

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// The volume slider, as mirror.py's gain() and louder() do it on the PC.
class SoundTest {
    private fun pcm(vararg samples: Short): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()

    @Test
    fun untouchedAtFull() {
        val data = pcm(16000, -16000)
        assertSame(data, louder(data, 100))
        assertArrayEquals(pcm(16000, -16000), data)
    }

    @Test
    fun silentAtZero() = assertArrayEquals(pcm(0, 0, 0), louder(pcm(32767, -32768, 1234), 0))

    @Test
    fun quieterOnACurve() {
        assertEquals(0.25f, gain(50), 1e-6f) // a quarter of the amplitude: -12 dB
        assertArrayEquals(pcm(8000, -8000, 0, -1), louder(pcm(32000, -32000, 0, -4), 50))
        assertEquals(1f, gain(150), 0f) // kept within 0-100
        assertEquals(0f, gain(-5), 0f)
    }
}
