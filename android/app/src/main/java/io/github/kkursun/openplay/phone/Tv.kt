package io.github.kkursun.openplay.phone

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/** A TV the phone can play to. play() starts playback; alive() says true while playing, false while it looks
 * stopped (TVs also say so while loading, so only a long spell counts), null once it's surely gone; close() stops
 * it. The values list what its player takes. */
interface Tv {
    val name: String
    val address: String
    val model: String
    val video: Set<String>
    val audio: Set<String>
    val maxHeight: Int
    val maxFps: Double
    val playlistSeconds: Double // how much of a live stream its playlist lists
    val picksSubtitles: Boolean // true if the app switches subtitles; false if the TV's own menu does

    fun play(url: String, contentType: String, live: Boolean)
    fun alive(): Boolean?
    fun subtitle(index: Int?) {}
    fun close()
}

/** An AirPlay 1 device like the Apple TV 3. AirPlay 2 ones (newer Apple TVs, Macs) want pairing, which only
 * openplay on a PC does. */
class AirPlayTv(
    override val name: String,
    override val address: String,
    private val port: Int,
    override val model: String,
    val needsPairing: Boolean,
) : Tv {
    // ponytail: H.264 level isn't checked; 30 fps stands in for level 4.0
    override val video = setOf("h264")
    override val audio = setOf("aac", "mp3")
    override val maxHeight = 1080
    override val maxFps = 30.0
    // Measured on an Apple TV 3: a playlist shorter than the player's ~2 s buffer makes it start further back.
    override val playlistSeconds = 1.5
    override val picksSubtitles = false // it shows the one in its own language; its remote's menu switches them

    private var socket: Socket? = null
    private var input: InputStream? = null

    override fun play(url: String, contentType: String, live: Boolean) {
        if (needsPairing) {
            throw IllegalStateException("$name only takes AirPlay 2 with pairing, which the phone can't do yet. " +
                "Play it from openplay on a PC instead (the PC tab).")
        }
        // The TV keeps playing only while this connection stays open.
        val s = Socket()
        s.connect(InetSocketAddress(address, port), 10_000)
        s.soTimeout = 10_000
        socket = s
        input = BufferedInputStream(s.getInputStream())
        val body = Plist.write(mapOf("Content-Location" to url, "Start-Position" to 0.0, "X-Apple-Session-ID" to UUID.randomUUID().toString()))
        val (code, _) = request("POST", "/play", body)
        if (code >= 400) throw IllegalStateException("$name refused to play (HTTP $code).")
    }

    override fun alive(): Boolean? {
        val info = try {
            request("GET", "/playback-info", null).second
        } catch (_: IOException) {
            return null // the TV dropped the connection: stopped from the remote
        }
        val map = info as? Map<*, *> ?: return false
        map["error"]?.let { throw IllegalStateException("Apple TV playback error: $it") }
        // The TV leaves out "duration" while loading and during brief rebuffers, not just after Menu is pressed.
        return "duration" in map
    }

    override fun close() {
        runCatching { socket?.close() }
    }

    /** One AirPlay 1 request (the same calls pyatv makes for an unpaired Apple TV 3), on the open connection.
     * Returns the status code and the decoded plist reply. */
    @Synchronized
    private fun request(method: String, path: String, body: ByteArray?): Pair<Int, Any?> {
        val s = socket ?: throw IOException("not connected")
        val head = StringBuilder("$method $path HTTP/1.1\r\nHost: $address:$port\r\nUser-Agent: AirPlay/550.10\r\n" +
            "X-Apple-ProtocolVersion: 1\r\nX-Apple-Stream-ID: 1\r\nContent-Length: ${body?.size ?: 0}\r\n")
        if (body != null) head.append("Content-Type: application/x-apple-binary-plist\r\n")
        head.append("\r\n")
        val out = s.getOutputStream()
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        body?.let { out.write(it) }
        out.flush()
        return readResponse(input!!)
    }

    companion object {
        fun readResponse(input: InputStream): Pair<Int, Any?> {
            val status = readLine(input) ?: throw IOException("connection closed")
            val code = status.split(" ").getOrNull(1)?.toIntOrNull() ?: throw IOException("bad reply: $status")
            var length = 0
            while (true) {
                val line = readLine(input) ?: throw IOException("connection closed")
                if (line.isEmpty()) break
                if (line.startsWith("content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
            }
            val data = ByteArray(length)
            var got = 0
            while (got < length) {
                val n = input.read(data, got, length - got)
                if (n < 0) throw IOException("connection closed")
                got += n
            }
            val reply = if (length == 0) emptyMap<String, Any?>() else try {
                Plist.read(data)
            } catch (_: RuntimeException) {
                emptyMap<String, Any?>()
            }
            return code to reply
        }

        fun readLine(input: InputStream): String? {
            val line = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                line.write(b)
            }
        }

        /** Whether an AirPlay device's mDNS "features" ("0x5A7FFFF7,0x1E") ask for pairing (AirPlay 2), as pyatv
         * reads them: system pairing (bit 43) or CoreUtils pairing and encryption (bit 48). */
        fun needsPairing(features: String): Boolean {
            val parts = features.split(",").map { it.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16) ?: 0L }
            val flags = (parts.getOrElse(1) { 0L } shl 32) or parts[0]
            return flags and (1L shl 43) != 0L || flags and (1L shl 48) != 0L
        }
    }
}
