package io.github.kkursun.openplay.phone

import android.annotation.SuppressLint
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** A message of Google Cast's protocol (CastV2): protobuf CastMessage with a JSON payload. */
data class CastMessage(val source: String, val destination: String, val namespace: String, val payload: String) {
    fun encode(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x08); out.write(0) // protocol_version CASTV2_1_0
        field(out, 2, source)
        field(out, 3, destination)
        field(out, 4, namespace)
        out.write(0x28); out.write(0) // payload_type STRING
        field(out, 6, payload)
        return out.toByteArray()
    }

    companion object {
        private fun field(out: ByteArrayOutputStream, number: Int, value: String) {
            val bytes = value.toByteArray()
            out.write(number shl 3 or 2)
            var n = bytes.size
            while (n >= 0x80) { out.write(n and 0x7F or 0x80); n = n ushr 7 }
            out.write(n)
            out.write(bytes)
        }

        fun decode(data: ByteArray): CastMessage {
            val strings = HashMap<Int, String>()
            var i = 0
            fun varint(): Long {
                var v = 0L
                var shift = 0
                while (true) {
                    val b = data[i++].toInt() and 0xFF
                    v = v or ((b and 0x7F).toLong() shl shift)
                    if (b < 0x80) return v
                    shift += 7
                }
            }
            while (i < data.size) {
                val tag = varint().toInt()
                when (tag and 7) {
                    0 -> varint()
                    2 -> {
                        val n = varint().toInt()
                        strings[tag ushr 3] = String(data, i, n)
                        i += n
                    }
                    5 -> i += 4
                    1 -> i += 8
                    else -> throw IOException("bad Cast message")
                }
            }
            return CastMessage(strings[2] ?: "", strings[3] ?: "", strings[4] ?: "", strings[6] ?: "")
        }
    }
}

private const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
private const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
private const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
private const val MEDIA = "urn:x-cast:com.google.cast.media"
const val MEDIA_RECEIVER = "CC1AD845" // Google's Default Media Receiver

/** A Google Cast device (a Chromecast, or an Android TV box with Chromecast built-in like the Xiaomi TV Box), playing
 * through Google's Default Media Receiver. Speaks the protocol itself, as pychromecast does, so no Google Play
 * services are needed. */
class CastTv(override val name: String, override val address: String, private val port: Int, override val model: String) : Tv {
    // ponytail: what a Xiaomi TV Box S plays; drop "hevc" for Chromecasts that lack it
    override val video = setOf("h264", "hevc", "vp8", "vp9")
    override val audio = setOf("aac")
    override val maxHeight = 2160
    override val maxFps = 60.0
    // ponytail: not tuned for delay; Cast players start ~3 segments behind live
    override val playlistSeconds = 6.0
    override val picksSubtitles = true // from the app: the receiver has no subtitle menu of its own

    private var socket: SSLSocket? = null
    private var out: OutputStream? = null
    private val requests = AtomicInteger(1)
    private val sender = "sender-openplay"
    @Volatile private var app: String? = null // id of the receiver app running, null until known
    @Volatile private var transport: String? = null
    @Volatile private var session: String? = null
    @Volatile private var mediaSession = 0
    @Volatile private var playerState = ""
    @Volatile private var idleReason = ""
    @Volatile private var tracks = emptyList<Int>() // ids of the text tracks, in playlist order
    @Volatile private var failed: String? = null
    @Volatile private var closed = false
    @Volatile private var dropped = false
    private val lock = Object()

    // Cast devices present certificates of their own making; pychromecast doesn't check them either.
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustCastDevices : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun send(destination: String, namespace: String, payload: JSONObject) {
        val data = CastMessage(sender, destination, namespace, payload.toString()).encode()
        val o = out ?: throw IOException("not connected")
        synchronized(o) {
            o.write(byteArrayOf((data.size ushr 24).toByte(), (data.size ushr 16).toByte(), (data.size ushr 8).toByte(), data.size.toByte()))
            o.write(data)
            o.flush()
        }
    }

    private fun request(destination: String, namespace: String, payload: JSONObject) =
        send(destination, namespace, payload.put("requestId", requests.getAndIncrement()))

    private fun listen(input: DataInputStream) {
        try {
            while (!closed) {
                val size = input.readInt()
                if (size !in 0..(1 shl 20)) throw IOException("bad Cast message")
                val data = ByteArray(size)
                input.readFully(data)
                handle(CastMessage.decode(data))
            }
        } catch (_: Exception) {
            dropped = true
        }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun handle(m: CastMessage) {
        val p = try { JSONObject(m.payload) } catch (_: Exception) { return }
        when (p.optString("type")) {
            "PING" -> send(m.source, HEARTBEAT, JSONObject().put("type", "PONG"))
            "RECEIVER_STATUS" -> {
                val apps = p.optJSONObject("status")?.optJSONArray("applications") ?: JSONArray()
                val running = (0 until apps.length()).map { apps.getJSONObject(it) }.firstOrNull()
                app = running?.optString("appId") ?: ""
                if (running != null && running.optString("appId") == MEDIA_RECEIVER) {
                    transport = running.optString("transportId")
                    session = running.optString("sessionId")
                }
            }
            "MEDIA_STATUS" -> {
                val status = p.optJSONArray("status")?.optJSONObject(0) ?: return
                mediaSession = status.optInt("mediaSessionId", mediaSession)
                playerState = status.optString("playerState", playerState)
                idleReason = status.optString("idleReason", "")
                status.optJSONObject("media")?.optJSONArray("tracks")?.let { t ->
                    tracks = (0 until t.length()).map { t.getJSONObject(it) }.filter { it.optString("type") == "TEXT" }.map { it.getInt("trackId") }
                }
            }
            "LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST" -> failed = p.optString("detailedErrorCode", p.optString("reason", "unknown"))
        }
        synchronized(lock) { lock.notifyAll() }
    }

    private fun await(seconds: Int, what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000
        synchronized(lock) {
            while (!done()) {
                failed?.let { throw IllegalStateException("$name couldn't play it (Cast error $it).") }
                if (dropped) throw IllegalStateException("$name dropped the connection.")
                val left = until - System.currentTimeMillis()
                if (left <= 0) throw IllegalStateException("$name didn't $what in time.")
                lock.wait(left)
            }
        }
    }

    override fun play(url: String, contentType: String, live: Boolean) {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustCastDevices), null) }
        val raw = Socket()
        raw.connect(InetSocketAddress(address, port), 10_000)
        val s = tls.socketFactory.createSocket(raw, address, port, true) as SSLSocket
        s.startHandshake()
        socket = s
        out = s.outputStream
        Thread({ listen(DataInputStream(s.inputStream.buffered())) }, "cast-$name").apply { isDaemon = true }.start()
        Thread({
            try {
                while (!closed) {
                    send("receiver-0", HEARTBEAT, JSONObject().put("type", "PING"))
                    Thread.sleep(5000)
                }
            } catch (_: Exception) {}
        }, "cast-ping").apply { isDaemon = true }.start()

        send("receiver-0", CONNECTION, JSONObject().put("type", "CONNECT"))
        app = null
        request("receiver-0", RECEIVER, JSONObject().put("type", "LAUNCH").put("appId", MEDIA_RECEIVER))
        await(20, "open its media player") { app == MEDIA_RECEIVER && transport != null }
        val t = transport!!
        send(t, CONNECTION, JSONObject().put("type", "CONNECT"))
        val media = JSONObject().put("contentId", url).put("contentType", contentType)
            .put("streamType", if (live) "LIVE" else "BUFFERED")
        val load = JSONObject().put("type", "LOAD").put("media", media).put("autoplay", true).put("sessionId", session)
        if (!live) load.put("currentTime", 0) // start growing (EVENT) playlists at the beginning, not the live edge
        playerState = ""
        request(t, MEDIA, load)
        await(20, "start playing") { playerState.isNotEmpty() && playerState != "IDLE" || idleReason == "ERROR" }
    }

    override fun alive(): Boolean? {
        failed?.let { throw IllegalStateException("$name couldn't play it (Cast error $it).") }
        val running = app
        // Back or Home on the remote, or another app took over
        if (dropped || running != null && running != MEDIA_RECEIVER) return null
        if (idleReason == "ERROR") throw IllegalStateException("$name hit a playback error.")
        transport?.let { runCatching { request(it, MEDIA, JSONObject().put("type", "GET_STATUS")) } }
        return running != null && playerState != "IDLE"
    }

    override fun subtitle(index: Int?) {
        val t = transport ?: throw IllegalStateException("Nothing is playing.")
        // The receiver numbers renditions from 1, in playlist order, once it has read them.
        val ids = if (index == null) emptyList() else listOf(tracks.getOrElse(index) { index + 1 })
        request(t, MEDIA, JSONObject().put("type", "EDIT_TRACKS_INFO").put("mediaSessionId", mediaSession)
            .put("activeTrackIds", JSONArray(ids)))
    }

    override fun close() {
        try {
            if (app == MEDIA_RECEIVER && session != null) { // leave other apps alone
                request("receiver-0", RECEIVER, JSONObject().put("type", "STOP").put("sessionId", session))
            }
        } catch (_: IOException) {
        }
        closed = true
        runCatching { socket?.close() }
        socket = null
        out = null
    }
}
