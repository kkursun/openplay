package io.github.kkursun.openplay.phone

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/** CastTv against a stand-in receiver that answers as Google's Default Media Receiver does. */
class CastTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun tlsServer(): SSLServerSocket {
        val keys = File(tmp.root, "keys.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        assumeTrue("needs the JDK's keytool", keytool.exists())
        val made = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "cast", "-keyalg", "RSA", "-dname", "CN=cast",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", keys.path, "-storepass", "secret").redirectErrorStream(true).start()
        made.inputStream.readBytes()
        assertEquals(0, made.waitFor())
        val store = KeyStore.getInstance("PKCS12").apply { keys.inputStream().use { load(it, "secret".toCharArray()) } }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "secret".toCharArray()) }
        val tls = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        return tls.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    }

    @Test
    fun launchesLoadsSwitchesSubtitlesAndStops() {
        val server = tlsServer()
        val seen = mutableListOf<CastMessage>()
        var app: String? = null
        var home: (() -> Unit)? = null // the remote's Home button: another app takes over
        thread(isDaemon = true) {
            server.accept().use { c ->
                val input = DataInputStream(c.getInputStream())
                val out = DataOutputStream(c.getOutputStream())
                fun send(src: String, dst: String, ns: String, p: JSONObject) {
                    val data = CastMessage(src, dst, ns, p.toString()).encode()
                    synchronized(out) { out.writeInt(data.size); out.write(data); out.flush() }
                }
                fun mediaStatus(dst: String, state: String) = send("web-7", dst, "urn:x-cast:com.google.cast.media", JSONObject()
                    .put("type", "MEDIA_STATUS").put("status", JSONArray().put(JSONObject().put("mediaSessionId", 5).put("playerState", state)
                        .put("media", JSONObject().put("tracks", JSONArray().put(JSONObject().put("trackId", 11).put("type", "TEXT"))
                            .put(JSONObject().put("trackId", 12).put("type", "TEXT")).put(JSONObject().put("trackId", 1).put("type", "VIDEO")))))))
                fun receiverStatus(dst: String) = send("receiver-0", dst, "urn:x-cast:com.google.cast.receiver", JSONObject()
                    .put("type", "RECEIVER_STATUS").put("status", JSONObject().put("applications", JSONArray().apply {
                        app?.let { put(JSONObject().put("appId", it).put("transportId", "web-7").put("sessionId", "sess-1")) }
                    })))
                home = { app = "Netflix"; receiverStatus("sender-openplay") }
                try {
                    while (true) {
                        val m = CastMessage.decode(ByteArray(input.readInt()).also { input.readFully(it) })
                        synchronized(seen) { seen += m }
                        val p = JSONObject(m.payload)
                        when (p.getString("type")) {
                            "LAUNCH" -> { app = p.getString("appId"); receiverStatus(m.source) }
                            "LOAD" -> mediaStatus(m.source, "BUFFERING")
                            "GET_STATUS" -> mediaStatus(m.source, "PLAYING")
                            "STOP" -> { app = null; receiverStatus(m.source) }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        val tv = CastTv("Fake", "127.0.0.1", server.localPort, "Fake")
        tv.play("http://10.0.0.2:5000/hls/master.m3u8", HLS, live = false)
        assertEquals(true, tv.alive())
        tv.subtitle(1)
        tv.subtitle(null)
        Thread.sleep(300)
        home!!()
        Thread.sleep(300)
        assertNull(tv.alive()) // gone: stopped from the TV
        tv.close()
        Thread.sleep(300)

        val payloads = synchronized(seen) { seen.filter { "PING" !in it.payload }.map { it.destination to JSONObject(it.payload) } }
        assertEquals(listOf("CONNECT", "LAUNCH", "CONNECT", "LOAD"), payloads.take(4).map { it.second.getString("type") })
        assertEquals("CC1AD845", payloads[1].second.getString("appId"))
        val load = payloads[3]
        assertEquals("web-7", load.first)
        assertEquals("http://10.0.0.2:5000/hls/master.m3u8", load.second.getJSONObject("media").getString("contentId"))
        assertEquals("BUFFERED", load.second.getJSONObject("media").getString("streamType"))
        assertEquals(0, load.second.getInt("currentTime")) // a growing playlist starts at its beginning
        val tracks = payloads.filter { it.second.getString("type") == "EDIT_TRACKS_INFO" }.map { it.second.getJSONArray("activeTrackIds").toString() }
        assertEquals(listOf("[12]", "[]"), tracks) // the second text track, then none
        assertNull(payloads.firstOrNull { it.second.getString("type") == "STOP" }) // not ours any more: left alone
    }
}
