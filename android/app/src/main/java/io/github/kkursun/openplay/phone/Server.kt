package io.github.kkursun.openplay.phone

import io.github.kkursun.openplay.phone.AirPlayTv.Companion.readLine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/** What's being streamed: the files the TV may fetch, and the one TV that may fetch them. */
class Stream(
    val client: InetAddress,
    val dir: File,
    val token: String,
    val file: Source? = null, // served at /media/<token>.<ext>
    val fileType: String = "video/mp4",
    val subtitles: List<Subtitle> = emptyList(), // served from subN.vtt in dir
    val writer: HlsWriter? = null,
)

/** Serves the stream to the TV over HTTP, as mirror.py's Handler does on the PC. */
class StreamServer {
    private val socket = ServerSocket(0)
    private val pool = Executors.newCachedThreadPool()
    @Volatile var stream: Stream? = null

    init {
        Thread({
            while (true) {
                val c = try { socket.accept() } catch (_: IOException) { break }
                pool.execute { c.use { runCatching { handle(it) } } } // a bad request mustn't take the app down
            }
        }, "server").apply { isDaemon = true }.start()
    }

    /** URL the TV fetches something from this server at. */
    fun url(tv: Tv, path: String): String {
        val ip = DatagramSocket().use { it.connect(InetAddress.getByName(tv.address), 7000); it.localAddress.hostAddress }
        return "http://$ip:${socket.localPort}$path"
    }

    private class Reply(val out: OutputStream) {
        fun send(code: Int, type: String = "text/plain", body: ByteArray = ByteArray(0), extra: Map<String, String> = emptyMap(), head: Boolean = false) {
            val status = mapOf(200 to "OK", 204 to "No Content", 206 to "Partial Content", 403 to "Forbidden", 404 to "Not Found", 416 to "Range Not Satisfiable")
            val headers = StringBuilder("HTTP/1.1 $code ${status[code] ?: ""}\r\n")
            val all = mapOf("Content-Type" to type, "Content-Length" to body.size.toString(), "Cache-Control" to "no-store",
                "Access-Control-Allow-Origin" to "*", "Connection" to "close") + extra // Cast receivers fetch HLS from script
            all.forEach { (k, v) -> headers.append("$k: $v\r\n") }
            out.write(headers.append("\r\n").toString().toByteArray())
            if (!head) out.write(body)
            out.flush()
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 30_000
        val input = BufferedInputStream(c.getInputStream())
        val request = readLine(input)?.split(" ") ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            headers[line.substringBefore(':').trim().lowercase()] = line.substringAfter(':').trim()
        }
        val reply = Reply(BufferedOutputStream(c.getOutputStream(), 1 shl 16))
        val method = request.getOrElse(0) { "" }
        val path = request.getOrElse(1) { "" }
        val st = stream
        try {
            when {
                method == "OPTIONS" -> reply.send(204, extra = mapOf("Access-Control-Allow-Methods" to "GET, HEAD", "Access-Control-Allow-Headers" to "*"))
                // Only the TV being streamed to may fetch the stream, and only the stream's own files.
                st == null || c.inetAddress != st.client -> reply.send(403)
                path.startsWith("/hls/") -> hls(st, path.removePrefix("/hls/"), reply, method == "HEAD")
                path.startsWith("/media/") -> media(st, path, headers["range"] ?: "", reply, method == "HEAD")
                else -> reply.send(404)
            }
        } catch (_: IOException) {
            // the player hung up (it does on every seek), or playback stopped
        }
    }

    private fun hls(st: Stream, name: String, reply: Reply, head: Boolean) {
        if (!Regex("live(\\d+\\.ts|\\.m3u8)|master\\.m3u8|sub\\d+(_\\d+\\.vtt|\\.m3u8)").matches(name)) return reply.send(403)
        val body = try {
            when {
                name == "master.m3u8" -> masterPlaylist(st.subtitles, st.writer?.peakBitrate() ?: 0, st.writer?.codecs() ?: "").toByteArray()
                name.startsWith("sub") -> subtitlePart(name, File(st.dir, "live.m3u8").readText()) { File(st.dir, "sub$it.vtt").readText() }.toByteArray()
                else -> File(st.dir, name).readBytes()
            }
        } catch (_: Exception) { // not written yet, or already gone; the player retries
            return reply.send(404)
        }
        val type = if (name.endsWith(".ts")) "video/mp2t" else if (name.endsWith(".vtt")) "text/vtt" else HLS
        reply.send(200, type, body, head = head)
    }

    private fun media(st: Stream, path: String, range: String, reply: Reply, head: Boolean) {
        val file = st.file
        val m = Regex("/media/([0-9a-f]+)(\\.\\w+)?").matchEntire(path)
        if (file == null || m == null || m.groupValues[1] != st.token) return reply.send(403)
        val size = file.size
        var start = 0L
        var end = size
        val r = Regex("bytes=(\\d*)-(\\d*)").matchEntire(range)
        val ranged = r != null && (r.groupValues[1].isNotEmpty() || r.groupValues[2].isNotEmpty())
        if (ranged && r!!.groupValues[1].isNotEmpty()) {
            start = r.groupValues[1].toLong()
            end = if (r.groupValues[2].isNotEmpty()) minOf(size, r.groupValues[2].toLong() + 1) else size
        } else if (ranged) { // "bytes=-N": the last N bytes
            start = maxOf(0, size - r!!.groupValues[2].toLong())
        }
        if (ranged && start >= end) return reply.send(416, extra = mapOf("Content-Range" to "bytes */$size"))
        val headers = StringBuilder("HTTP/1.1 ${if (ranged) "206 Partial Content" else "200 OK"}\r\n")
        if (ranged) headers.append("Content-Range: bytes $start-${end - 1}/$size\r\n")
        headers.append("Content-Type: ${st.fileType}\r\nContent-Length: ${end - start}\r\nAccept-Ranges: bytes\r\n" +
            "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n")
        reply.out.write(headers.toString().toByteArray())
        if (!head) {
            val buffer = ByteArray(1 shl 18)
            while (start < end) {
                val n = file.readAt(start, buffer, 0, minOf(buffer.size.toLong(), end - start).toInt())
                if (n <= 0) break
                reply.out.write(buffer, 0, n)
                start += n
            }
        }
        reply.out.flush()
    }
}
