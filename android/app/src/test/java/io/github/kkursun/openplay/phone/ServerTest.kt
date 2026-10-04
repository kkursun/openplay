package io.github.kkursun.openplay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class ServerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class Bytes(private val data: ByteArray) : Source {
        override val name = "movie.mp4"
        override val size = data.size.toLong()
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val n = minOf(length.toLong(), size - position).toInt()
            data.copyInto(buffer, offset, position.toInt(), position.toInt() + n)
            return n
        }
        override fun close() {}
    }

    private val tv = object : Tv {
        override val name = "TV"
        override val address = "127.0.0.1"
        override val model = ""
        override val video = emptySet<String>()
        override val audio = emptySet<String>()
        override val maxHeight = 0
        override val maxFps = 0.0
        override val playlistSeconds = 0.0
        override val picksSubtitles = true
        override fun play(url: String, contentType: String, live: Boolean) {}
        override fun alive() = true
        override fun close() {}
    }

    private fun get(url: String, range: String? = null): Triple<Int, Map<String, String>, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        range?.let { c.setRequestProperty("Range", it) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return Triple(code, c.headerFields.filterKeys { it != null }.mapValues { it.value.first() }, body)
    }

    @Test
    fun servesTheVideoInRanges() {
        val data = ByteArray(1_000_000) { (it % 251).toByte() }
        val server = StreamServer()
        server.stream = Stream(InetAddress.getByName("127.0.0.1"), tmp.newFolder(), "abc123", Bytes(data))
        val url = server.url(tv, "/media/abc123.mp4")
        assertTrue(url, Regex("http://127\\.0\\.0\\.1:\\d+/media/abc123\\.mp4").matches(url))

        val (code, headers, body) = get(url)
        assertEquals(200, code)
        assertEquals("video/mp4", headers["Content-Type"])
        assertTrue(body.contentEquals(data))

        val (partial, h, slice) = get(url, "bytes=100-199")
        assertEquals(206, partial)
        assertEquals("bytes 100-199/1000000", h["Content-Range"])
        assertTrue(slice.contentEquals(data.copyOfRange(100, 200)))

        assertTrue(get(url, "bytes=-10").third.contentEquals(data.copyOfRange(999_990, 1_000_000)))
        assertEquals(416, get(url, "bytes=2000000-").first)
        assertEquals(403, get(url.replace("abc123", "abc124")).first)

        server.stream = Stream(InetAddress.getByName("10.9.9.9"), tmp.newFolder(), "abc123", Bytes(data)) // another TV's
        assertEquals(403, get(url).first)
    }

    @Test
    fun servesTheStreamAndSubtitles() {
        val dir = tmp.newFolder()
        File(dir, "live.m3u8").writeText("#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:4.000000,\nlive0.ts\n")
        File(dir, "live0.ts").writeBytes(ByteArray(188) { 0x47 })
        File(dir, "sub0.vtt").writeText("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n\n")
        val server = StreamServer()
        server.stream = Stream(InetAddress.getByName("127.0.0.1"), dir, "t", subtitles = listOf(Subtitle("1. English", "en")))
        val base = server.url(tv, "/hls/")

        assertEquals("application/vnd.apple.mpegurl", get(base + "live.m3u8").second["Content-Type"])
        assertEquals(188, get(base + "live0.ts").third.size)
        assertTrue(String(get(base + "master.m3u8").third).contains("NAME=\"1. English\",LANGUAGE=\"en\""))
        assertTrue(String(get(base + "sub0.m3u8").third).endsWith("sub0_0.vtt\n"))
        assertTrue(String(get(base + "sub0_0.vtt").third).endsWith("00:00:01.000 --> 00:00:02.000\nHello"))
        assertEquals(404, get(base + "live1.ts").first) // not written yet
        assertEquals(403, get(base + "../secret").first)
        assertEquals("*", get(base + "live.m3u8").second["Access-Control-Allow-Origin"])
    }
}
