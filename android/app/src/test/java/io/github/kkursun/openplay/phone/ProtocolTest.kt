package io.github.kkursun.openplay.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

class ProtocolTest {
    @Test
    fun binaryPlistRoundTrip() {
        val url = "http://192.168.1.20:41234/hls/" + "x".repeat(300) + "/live.m3u8"
        val dict = mapOf("Content-Location" to url, "Start-Position" to 0.0, "Count" to 7, "On" to true, "Name" to "Şimdi")
        assertEquals(mapOf("Content-Location" to url, "Start-Position" to 0.0, "Count" to 7L, "On" to true, "Name" to "Şimdi"),
            Plist.read(Plist.write(dict)))
    }

    @Test
    fun xmlPlist() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>duration</key>
              <real>1513.45</real>
              <key>loadedTimeRanges</key>
              <array>
                <dict><key>start</key><real>0.0</real></dict>
              </array>
              <key>playbackBufferEmpty</key>
              <true/>
              <key>rate</key>
              <integer>1</integer>
              <key>uuid</key>
              <string>A &amp; B</string>
            </dict>
            </plist>"""
        val info = Plist.read(xml.toByteArray()) as Map<*, *>
        assertEquals(1513.45, info["duration"])
        assertEquals(listOf(mapOf("start" to 0.0)), info["loadedTimeRanges"])
        assertEquals(true, info["playbackBufferEmpty"])
        assertEquals(1L, info["rate"])
        assertEquals("A & B", info["uuid"])
    }

    @Test
    fun airPlayFeatures() {
        assertFalse(AirPlayTv.needsPairing("0x5A7FFFF7,0x1E")) // Apple TV 3
        assertTrue(AirPlayTv.needsPairing("0x4A7FDFD5,0xBC157FDE")) // Apple TV 4K
        assertFalse(AirPlayTv.needsPairing("0x0"))
    }

    /** The calls an Apple TV 3 gets, against a stand-in that answers as one does. */
    @Test
    fun airPlayPlaysAndPolls() {
        val server = ServerSocket(0)
        val requests = mutableListOf<Pair<String, Any?>>()
        thread(isDaemon = true) {
            server.accept().use { c ->
                val input = c.getInputStream().buffered()
                val out = c.getOutputStream()
                repeat(2) {
                    val line = AirPlayTv.readLine(input)!!
                    var length = 0
                    while (true) {
                        val h = AirPlayTv.readLine(input)!!
                        if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:")) length = h.substringAfter(':').trim().toInt()
                    }
                    val body = ByteArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }
                    synchronized(requests) { requests += line to if (length > 0) Plist.read(body) else null }
                    val reply = if (line.startsWith("GET")) "<plist><dict><key>duration</key><real>60</real></dict></plist>".toByteArray() else ByteArray(0)
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${reply.size}\r\n\r\n".toByteArray() + reply)
                    out.flush()
                }
                Thread.sleep(200)
            }
        }
        val tv = AirPlayTv("Living room", "127.0.0.1", server.localPort, "AppleTV3,2", needsPairing = false)
        tv.play("http://10.0.0.2:5000/hls/live.m3u8", HLS, live = true)
        assertEquals(true, tv.alive())
        tv.close()
        assertEquals("POST /play HTTP/1.1", requests[0].first)
        val body = requests[0].second as Map<*, *>
        assertEquals("http://10.0.0.2:5000/hls/live.m3u8", body["Content-Location"])
        assertEquals(0.0, body["Start-Position"])
        assertEquals("GET /playback-info HTTP/1.1", requests[1].first)
        assertNull(tv.alive()) // closed: gone
    }

    @Test
    fun httpReplies() {
        val plist = "<plist><dict><key>error</key><string>x</string></dict></plist>"
        val reply = "HTTP/1.1 200 OK\r\nContent-Type: text/x-apple-plist+xml\r\ncontent-length: ${plist.length}\r\n\r\n$plist"
        val (code, body) = AirPlayTv.readResponse(ByteArrayInputStream(reply.toByteArray()))
        assertEquals(200, code)
        assertEquals(mapOf("error" to "x"), body)
    }

    @Test
    fun castMessageRoundTrip() {
        val m = CastMessage("sender-0", "receiver-0", "urn:x-cast:com.google.cast.receiver", "{\"type\":\"LAUNCH\",\"appId\":\"CC1AD845\",\"x\":\"" + "y".repeat(200) + "\"}")
        val bytes = m.encode()
        // protocol_version 0, then source_id as field 2
        assertEquals(listOf(0x08, 0x00, 0x12, 8), bytes.take(4).map { it.toInt() and 0xFF })
        assertEquals(m, CastMessage.decode(bytes))
    }
}
