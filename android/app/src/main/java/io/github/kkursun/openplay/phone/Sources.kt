package io.github.kkursun.openplay.phone

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale
import kotlin.coroutines.coroutineContext

/** A video file the phone serves to the TV, and reads itself to remux. */
interface Source {
    val name: String
    val size: Long
    val episodes: List<String> get() = emptyList() // names of the videos to pick from, for a torrent
    val episode: Int get() = 0

    /** Reads bytes at position, waiting for a torrent to download them; -1 past the end. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Subtitle files that came with it: (file name, contents). */
    suspend fun subtitleFiles(): List<Pair<String, ByteArray>> = emptyList()
    fun describe() = ""
    fun problem(): String? = null
    fun close()
}

/** A source as MediaExtractor reads it. */
class SourceData(private val source: Source) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        if (size == 0) 0 else try { source.readAt(position, buffer, offset, size) } catch (_: IOException) { -1 }
    override fun getSize() = source.size
    override fun close() {}
}

/** A video on the phone, chosen in the file picker (a content:// URI). */
class LocalFile(context: Context, uri: Uri) : Source {
    private val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Can't open $uri")
    private val channel: FileChannel = FileInputStream(fd.fileDescriptor).channel
    override val size = channel.size()
    override val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "Video"

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        if (position >= size) -1 else channel.read(ByteBuffer.wrap(buffer, offset, length), position)

    override fun close() {
        runCatching { channel.close() }
        runCatching { fd.close() }
    }
}

/** The BitTorrent session, started on first use. */
val torrents: SessionManager by lazy {
    SessionManager().apply { start(SessionParams(SettingsPack().listenInterfaces("0.0.0.0:6881,[::]:6881"))) }
}

/** One video of a torrent (an episode, for a season), downloaded in the order it's read, into downloads. Kept
 * there after playing, so playing it again resumes instead of starting over. */
class Torrent(private val context: Context, private val source: String, private val downloads: File) : Source {
    private lateinit var handle: TorrentHandle
    private var index = -1
    override var episodes = emptyList<String>()
    override var episode = 0
    override var name = "Torrent"
    override var size = 0L
    private var offset = 0L
    private var piece = 1
    private var last = 0
    private lateinit var path: File
    private var subtitles = emptyList<Triple<File, Int, Long>>() // path, file index, size
    private var file: RandomAccessFile? = null
    @Volatile private var hot = -1 // first piece of the latest read
    @Volatile private var closed = false

    suspend fun ready(episode: Int) {
        val info = if (source.startsWith("magnet:")) {
            // A torrent played before is still in the session, paused, with its metadata and pieces known.
            val hash = AddTorrentParams.parseMagnetUri(source).infoHashes.best
            if (torrents.find(hash)?.isValid != true) torrents.download(source, downloads, TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle = torrents.find(hash) ?: throw IOException("libtorrent didn't take the magnet link")
            handle.resume()
            while (!handle.status().hasMetadata()) delay(500) // magnet links get it from peers first
            handle.torrentFile()
        } else {
            runInterruptible {
                val data = if (source.startsWith("content:")) context.contentResolver.openInputStream(source.toUri())!!.use { it.readBytes() }
                else URL(source).openStream().use { it.readBytes() }
                TorrentInfo(data)
            }
        }
        val files = info.files()
        val every = (0 until files.numFiles()).map { files.filePath(it) to files.fileSize(it) }
        val videos = episodes(every)
        val index = if (episode in videos.indices) videos[episode] else videos[0]
        // Names without the torrent's own folder.
        episodes = videos.map { i -> every[i].first.split('/', '\\').drop(1).joinToString("/").ifEmpty { every[i].first } }
        this.episode = videos.indexOf(index)
        var subs = every.indices.filter { File(every[it].first).extension.lowercase(Locale.ROOT) in SUBTITLE_FILES }
        if (videos.size > 1) { // a season's subtitles are named, or sit in folders named, after their episode
            val stem = File(every[index].first).nameWithoutExtension.lowercase(Locale.ROOT)
            val key = Regex("s\\d+e\\d+").find(stem)?.value ?: stem
            subs = subs.filter { key in every[it].first.lowercase(Locale.ROOT) }
        }
        // Subtitle files first (they're tiny), then the video; nothing else.
        val priorities = Array(every.size) { if (it == index) Priority.DEFAULT else if (it in subs) Priority.TOP_PRIORITY else Priority.IGNORE }
        if (torrents.find(info.infoHash())?.isValid != true) {
            torrents.download(info, downloads, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        }
        handle = torrents.find(info.infoHash()) ?: throw IOException("libtorrent didn't take the torrent")
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED) // else libtorrent resumes it once paused
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        handle.clearPieceDeadlines() // the last episode's
        handle.prioritizeFiles(priorities)
        handle.resume()
        subtitles = subs.map { Triple(File(downloads, every[it].first), it, every[it].second) }
        path = File(downloads, every[index].first)
        name = path.name
        size = every[index].second
        offset = files.fileOffset(index)
        piece = info.pieceLength()
        last = ((offset + size - 1) / piece).toInt()
        this.index = index
    }

    /** Waits until bytes start..end-1 of the file are downloaded. Seeking TVs jump around, so each read makes its
     * pieces, and the few after, the most urgent. (Deadlines on many pieces at once make libtorrent fetch them out
     * of order, stalling playback.) */
    private fun wait(start: Long, end: Long) {
        val first = ((offset + start) / piece).toInt()
        val lastNeeded = ((offset + end - 1) / piece).toInt()
        if (first != hot) {
            hot = first
            for ((n, p) in (first..minOf(last, lastNeeded + 8)).withIndex()) handle.setPieceDeadline(p, 100 * n)
        }
        while (!closed) {
            if ((first..lastNeeded).all { handle.havePiece(it) }) return
            Thread.sleep(100)
        }
        throw IOException("torrent stopped")
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val n = minOf(length.toLong(), size - position).toInt()
        wait(position, position + n) // the file only exists once a piece is in
        val f = file ?: RandomAccessFile(path, "r").also { file = it }
        synchronized(f) {
            f.seek(position)
            return f.read(buffer, offset, n)
        }
    }

    /** The torrent's subtitle files, once downloaded (or whichever are, after timeout seconds). */
    override suspend fun subtitleFiles(): List<Pair<String, ByteArray>> {
        val until = System.currentTimeMillis() + 10_000
        while (true) {
            val progress = handle.fileProgress()
            val done = subtitles.filter { (_, i, size) -> progress[i] == size }
            if (done.size == subtitles.size || System.currentTimeMillis() > until) {
                return done.map { (path, _, _) -> path.name to path.readBytes() }
            }
            coroutineContext.ensureActive()
            delay(500)
        }
    }

    override fun describe(): String {
        if (!::handle.isInitialized) return "Getting the torrent"
        val st = handle.status()
        if (index < 0) return "Getting torrent info · ${st.numPeers()} peers"
        val done = handle.fileProgress()[index].toDouble() / maxOf(1, size)
        return String.format(Locale.ROOT, "%.0f%% downloaded · %.1f MB/s · %d peers", done * 100, st.downloadRate() / 1e6, st.numPeers())
    }

    override fun problem(): String? {
        if (!::handle.isInitialized) return null
        val e = handle.status().errorCode()
        return if (e.isError) "Torrent error: ${e.message}" else null
    }

    /** Takes the torrent out of the session, which lets go of its files. */
    fun remove() {
        if (::handle.isInitialized && handle.isValid) torrents.remove(handle)
    }

    override fun close() {
        closed = true
        runCatching { file?.close() }
        // Paused, not removed: playing it again (another episode, say) needn't fetch and check it again.
        if (::handle.isInitialized) handle.pause()
    }
}

/** What a video holds, as far as deciding how to send it goes. */
data class Probe(
    val mp4: Boolean,
    val video: String?, // "h264", "hevc", ... or null for none
    val audio: String?,
    val height: Int,
    val fps: Double,
    val tenBit: Boolean,
    val duration: Double, // seconds, 0 if unknown
    val videoTrack: Int,
    val audioTrack: Int,
    val videoFormat: MediaFormat?,
    val audioFormat: MediaFormat?,
)

private val CODECS = mapOf(
    "video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp9" to "vp9", "video/x-vnd.on2.vp8" to "vp8",
    "video/av01" to "av1", "video/mp4v-es" to "mpeg4", "audio/mp4a-latm" to "aac", "audio/mpeg" to "mp3",
    "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/vnd.dts" to "dts", "audio/opus" to "opus",
    "audio/vorbis" to "vorbis", "audio/flac" to "flac",
)

/** Opens a source in MediaExtractor: a Source, or a URL it fetches itself. */
fun extractor(source: Source?, url: String): MediaExtractor = MediaExtractor().apply {
    if (source != null) setDataSource(SourceData(source)) else setDataSource(url, emptyMap())
}

fun probe(source: Source?, url: String): Probe {
    val head = ByteArray(12)
    val got = if (source != null) source.readAt(0, head, 0, 12) else try {
        (URL(url).openConnection().apply { setRequestProperty("Range", "bytes=0-11"); connectTimeout = 15_000; readTimeout = 15_000 })
            .getInputStream().use { input ->
                var n = 0
                while (n < 12) n += input.read(head, n, 12 - n).takeIf { it > 0 } ?: break
                n
            }
    } catch (_: IOException) {
        0
    }
    val mp4 = got >= 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
    val ex = try {
        extractor(source, url)
    } catch (e: IOException) {
        throw IllegalStateException("The phone can't read that video${e.message?.let { ": $it" } ?: ""}.")
    }
    try {
        var video: Int = -1
        var audio: Int = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && video < 0) video = i
            if (mime.startsWith("audio/") && audio < 0) audio = i // only the first audio track
        }
        val vf = if (video >= 0) ex.getTrackFormat(video) else null
        val af = if (audio >= 0) ex.getTrackFormat(audio) else null
        val name = { f: MediaFormat? -> f?.getString(MediaFormat.KEY_MIME)?.let { CODECS[it] ?: it.substringAfter('/') } }
        val number = { f: MediaFormat?, key: String -> if (f != null && f.containsKey(key)) f.getNumber(key)?.toDouble() ?: 0.0 else 0.0 }
        // 10-bit H.264 (High 10, 4:2:2 or 4:4:4 profiles), which no TV decodes, from the profile byte of its SPS.
        val sps = vf?.getByteBuffer("csd-0")?.let { b -> ByteArray(b.remaining()).also { b.duplicate().get(it) } }
            ?.let { TsMuxer.nalUnits(it).firstOrNull { n -> TsMuxer.nalType(n) == 7 } }
        val tenBit = name(vf) == "h264" && sps != null && sps.size > 1 && (sps[1].toInt() and 0xFF) in setOf(110, 122, 244)
        val durations = listOfNotNull(vf, af).map { number(it, MediaFormat.KEY_DURATION) }
        return Probe(
            mp4, name(vf), name(af), number(vf, MediaFormat.KEY_HEIGHT).toInt(), number(vf, MediaFormat.KEY_FRAME_RATE),
            tenBit, (durations.maxOrNull() ?: 0.0) / 1e6, video, audio, vf, af,
        )
    } finally {
        ex.release()
    }
}

/** Whether the TV can play the video's picture and sound as they are, and if not the picture, why. */
fun plan(info: Probe, tv: Tv): Triple<Boolean, Boolean, String> {
    val v = info.video
    val why = when {
        v == null -> ""
        v !in tv.video -> "${tv.name} can't play ${v.uppercase(Locale.ROOT)} video"
        info.height > tv.maxHeight -> "the video is ${info.height}p, more than ${tv.name} plays"
        info.fps > tv.maxFps + 0.5 -> "the video runs at ${info.fps.toInt()} fps, more than ${tv.name} plays"
        v == "h264" && info.tenBit -> "the video is 10-bit H.264, which no TV plays" // no TV decodes 10-bit H.264
        else -> ""
    }
    return Triple(why.isEmpty(), info.audio == null || info.audio in tv.audio, why)
}
