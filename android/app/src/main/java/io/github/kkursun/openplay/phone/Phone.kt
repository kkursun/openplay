package io.github.kkursun.openplay.phone

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.projection.MediaProjection
import androidx.core.content.edit
import androidx.core.net.toUri
import android.provider.OpenableColumns
import io.github.kkursun.openplay.Finder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

data class PhoneSettings(
    val device: String = "",
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrate: Int = 6, // Mbps
    val segment: Double = 0.5, // seconds per segment of the mirrored screen
    val audio: Boolean = true,
    val volume: Int = 100, // percent of the sound sent
    val source: String = "",
    val subtitle: String = "", // language last picked on a Cast device, picked again next time
    val episode: Int = 0, // which of a torrent's videos plays, in episodes() order
) {
    fun json(): JSONObject = JSONObject().put("device", device).put("height", height).put("fps", fps).put("bitrate", bitrate)
        .put("segment", segment).put("audio", audio).put("volume", volume).put("source", source).put("subtitle", subtitle).put("episode", episode)

    companion object {
        /** Saved settings, clamped to sane ranges. */
        fun from(j: JSONObject) = PhoneSettings(
            j.optString("device"), j.optInt("height", 1080).coerceIn(360, 1080), j.optInt("fps", 30).coerceIn(10, 30),
            j.optInt("bitrate", 6).coerceIn(1, 20), j.optDouble("segment", 0.5).coerceIn(0.5, 2.0), j.optBoolean("audio", true),
            j.optInt("volume", 100).coerceIn(0, 100), j.optString("source"), j.optString("subtitle"), j.optInt("episode", 0).coerceAtLeast(0),
        )
    }
}

data class HistoryItem(
    val source: String, val episode: Int, val name: String, val time: Long,
    val position: Long = 0, // seconds in it was stopped at, 0 if nowhere worth resuming from
)

data class Device(val name: String, val model: String, val address: String)

data class PhoneState(
    val status: String = "idle", // idle, starting, live or error
    val error: String = "",
    val since: Long = 0, // when it went live, ms
    val job: String? = null, // "mirror" or "media" while one runs
    val detail: String = "",
    val devices: List<Device> = emptyList(),
    val scanning: Boolean = false,
    val subtitles: List<String> = emptyList(), // ones the app can switch between (on a Cast device)
    val subtitle: Int = -1,
    val episodes: List<String> = emptyList(),
    val episode: Int = 0,
    val uploaded: List<String> = emptyList(),
    val downloads: Long = 0,
    val history: List<HistoryItem> = emptyList(),
    val settings: PhoneSettings = PhoneSettings(),
    val volume: Int? = null, // the volume the video playing was started at, null if it plays at full whatever is set
)

/** Plays to a TV from the phone itself: mirrors its screen, or sends a video file, URL or torrent. The phone's
 * counterpart of mirror.py's Mirror. */
@SuppressLint("StaticFieldLeak") // the application's context, which lives as long as the app
object Phone {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    var settings = PhoneSettings()
        private set
    private var history = emptyList<HistoryItem>()
    private var devices = emptyMap<String, Tv>()
    private var scanning = false
    private var status = "idle"
    private var error = ""
    private var since = 0L
    private var job: String? = null
    private var task: Job? = null
    private var tv: Tv? = null // the device of the current job
    private var file: Source? = null
    private var capture: ScreenCapture? = null
    private var air: AirMirror? = null
    private var remux: Remuxer? = null
    private var subtitles = emptyList<Subtitle>() // of each subtitle rendition, served from subN.vtt
    private var subtitle = -1 // the one a Cast device is showing, -1 for none
    private var volume: Int? = null // the video playing's, null if it plays at full whatever is set
    private val server by lazy { StreamServer() }
    private val added = mutableListOf<Torrent>()
    private val downloads get() = context.getExternalFilesDir("downloads") ?: File(context.filesDir, "downloads")
    private val historyFile get() = File(context.filesDir, "history.json")

    fun init(app: Context) {
        if (::context.isInitialized) return
        context = app
        prefs = app.getSharedPreferences("phone", Context.MODE_PRIVATE)
        settings = PhoneSettings.from(runCatching { JSONObject(prefs.getString("settings", "{}")!!) }.getOrElse { JSONObject() })
        history = runCatching {
            val a = JSONArray(historyFile.readText())
            (0 until a.length()).map { a.getJSONObject(it) }
                .map { HistoryItem(it.getString("source"), it.getInt("episode"), it.getString("name"), it.getLong("time"), it.optLong("position")) }
        }.getOrElse { emptyList() }
        refresh()
        scan()
    }

    @Synchronized
    fun refresh() {
        val file = file
        val pickOnTv = subtitles.isNotEmpty() && tv?.picksSubtitles == false
        _state.value = PhoneState(
            status, error, since, job,
            listOf(file?.describe() ?: "", if (pickOnTv) "Subtitles: hold the center button on the Apple TV remote to pick" else "")
                .filter { it.isNotEmpty() }.joinToString(" · "),
            devices.values.map { Device(it.name, it.model, it.address) }, scanning,
            if (tv?.picksSubtitles == true) subtitles.map { it.name } else emptyList(), subtitle,
            file?.episodes ?: emptyList(), file?.episode ?: 0,
            uploads(settings.source, settings.episode).list()?.sorted() ?: emptyList(),
            downloads.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            history, settings, volume,
        )
    }

    /** Changes the settings. Another video starts at its first episode unless the change names one. */
    fun update(episodeGiven: Boolean = false, change: (PhoneSettings) -> PhoneSettings) {
        val old = settings
        var new = change(old)
        if (new.source != old.source && !episodeGiven) new = new.copy(episode = 0)
        settings = new
        prefs.edit { putString("settings", new.json().toString()) }
        refresh()
    }

    fun scan() {
        if (scanning) return
        scanning = true
        refresh()
        scope.launch {
            try {
                val finder = Finder(context)
                val casts = async { finder.find("_googlecast._tcp", 5) }
                val airplays = async { finder.find("_airplay._tcp", 5) }
                val found = casts.await().map { CastTv(it.txt["fn"]?.ifEmpty { null } ?: it.name, it.host, it.port, it.txt["md"]?.ifEmpty { null } ?: "Google Cast") } +
                    airplays.await().map {
                        AirPlayTv(it.name, it.host, it.port, it.txt["model"] ?: "AirPlay", AirPlayTv.needsPairing(it.txt["features"] ?: it.txt["ft"] ?: "0x0"))
                    }
                // Devices found before stay listed: some boxes answer mDNS only now and then.
                devices = devices + found.associateBy { it.name }
            } catch (e: Exception) {
                error = "Couldn't look for TVs: ${e.message ?: e}"
            } finally {
                scanning = false
                refresh()
            }
        }
    }

    /** Starts mirroring (with a projection) or playing settings.source start seconds in, stopping whatever ran. */
    fun start(job: String, projection: MediaProjection? = null, sound: Boolean = false, start: Double = 0.0) {
        val previous = task
        status = "starting"
        error = ""
        this.job = job
        refresh()
        task = scope.launch {
            previous?.let { stop(it) }
            run(settings, job, projection, sound, start)
        }
    }

    fun stop() {
        val t = task ?: return
        scope.launch { stop(t) }
    }

    private suspend fun stop(t: Job) {
        tv?.let { runCatching { it.close() } } // unblocks a TV still connecting
        air?.stop() // and airmirror waiting on one
        t.cancelAndJoin()
    }

    /** The screen-sharing permission ended, from the system's own "Stop sharing" or ours. */
    fun ended(projection: MediaProjection) {
        if (capture?.projection === projection || air?.projection === projection) stop()
    }

    /** Whether a job is running (or about to). */
    val busy get() = task?.isActive == true

    private suspend fun run(s: PhoneSettings, job: String, projection: MediaProjection?, sound: Boolean, start: Double) {
        status = "starting"
        error = ""
        this.job = job
        refresh()
        val token = UUID.randomUUID().toString().replace("-", "")
        val out = File(context.cacheDir, "stream-$token").apply { mkdirs() }
        var tv: Tv? = null
        var played = false
        var episode = 0
        // Where the TV is in the video, and its length: a stream repackaged from partway starts that far in, and
        // grows as it's repackaged, so the TV only knows its length so far.
        val position = { (remux?.offset ?: 0.0) + (tv?.position ?: 0.0) }
        val length = { remux?.info?.duration?.takeIf { it > 0 } ?: tv?.duration ?: 0.0 }
        try {
            tv = devices[s.device] ?: throw IllegalStateException("Pick a TV first (Scan if the list is empty).")
            this.tv = tv
            val (url, type, problem) = if (job == "mirror") mirror(s, tv, projection!!, sound, out, token) else media(s, tv, out, token, start)
            // The TV starts there itself unless the stream already does.
            val at = if (remux != null) 0.0 else start
            if (url.isNotEmpty()) runInterruptible { tv.play(url, type, job == "mirror", at) } // none: airmirror plays
            status = "live"
            since = System.currentTimeMillis()
            played = job == "media"
            episode = file?.episode ?: 0
            if (job == "media") remember(s.source, episode, file?.name ?: s.source.substringAfterLast('/').substringBefore('?'), start)
            var saved = System.currentTimeMillis()
            val same = subtitles.indexOfFirst { it.lang.isNotEmpty() && it.lang == s.subtitle }
            if (same >= 0 && tv.picksSubtitles) {
                runCatching { runInterruptible { pickSubtitle(same, remember = false) } } // a convenience; they can still be picked
            }
            refresh()
            var missing = 0
            while (missing < 8) {
                delay(1000)
                problem()?.let { throw IllegalStateException(it) }
                val alive = (if (url.isEmpty()) air?.alive() else runInterruptible { tv.alive() }) ?: break
                missing = if (alive) 0 else missing + 1
                if (played && alive && System.currentTimeMillis() - saved > 15_000) {
                    leftOff(s.source, episode, position(), length())
                    saved = System.currentTimeMillis()
                }
                refresh()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "error"
            error = e.message ?: e.toString()
        } finally {
            withContext(NonCancellable) {
                // Where the TV was last seen playing.
                if (played && tv!!.duration > 0) leftOff(s.source, episode, position(), length())
                if (status != "error") status = "idle"
                server.stream = null
                this@Phone.job = null
                this@Phone.tv = null
                subtitles = emptyList()
                subtitle = -1
                volume = null
                tv?.let { runCatching { it.close() } }
                capture?.stop() ?: air?.stop() ?: projection?.stop()
                capture = null
                air = null
                remux?.stop()
                remux = null
                file?.close()
                file = null
                out.deleteRecursively()
                refresh()
            }
        }
    }

    private suspend fun mirror(s: PhoneSettings, tv: Tv, projection: MediaProjection, sound: Boolean, out: File, token: String): Triple<String, String, () -> String?> {
        // ponytail: arm64 phones only (the ABI airmirror is built for) and the Apple TV 3; others keep HLS
        val exe = File(context.applicationInfo.nativeLibraryDir, "libairmirror.so")
        if (tv is AirPlayTv && !tv.needsPairing && exe.exists()) {
            val air = AirMirror(projection, exe, tv.address, s, context.resources.displayMetrics.densityDpi, sound) { settings.volume }
            this.air = air
            runInterruptible { air.start() }
            return Triple("", "", air::problem)
        }
        val capture = ScreenCapture(projection, out, s, tv.playlistSeconds, context.resources.displayMetrics.densityDpi, sound) { settings.volume }
        this.capture = capture
        capture.start()
        ready(capture.writer, capture::problem)
        server.stream = Stream(InetAddress.getByName(tv.address), out, token, writer = capture.writer)
        return Triple(server.url(tv, "/hls/live.m3u8"), HLS, capture::problem)
    }

    private fun isTorrent(source: String): Boolean = source.startsWith("magnet:") ||
        source.substringBefore('?').lowercase(Locale.ROOT).endsWith(".torrent") ||
        source.startsWith("content:") && (context.contentResolver.getType(source.toUri()) == "application/x-bittorrent" ||
            displayName(source).lowercase(Locale.ROOT).endsWith(".torrent"))

    fun displayName(source: String): String = runCatching {
        context.contentResolver.query(source.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: source.substringAfterLast('/')

    private suspend fun media(s: PhoneSettings, tv: Tv, out: File, token: String, start: Double): Triple<String, String, () -> String?> {
        val source = s.source.trim().trim('"')
        val file: Source? = when {
            isTorrent(source) -> Torrent(context, source, downloads).also {
                this.file = it // shows its progress while it gets ready
                synchronized(added) { added += it }
                refresh()
                it.ready(s.episode)
            }
            source.startsWith("content:") -> LocalFile(context, source.toUri())
            Regex("https?://.+").matches(source) -> null
            else -> throw IllegalStateException("Enter an http(s) URL or a magnet link, or choose a file.")
        }
        this.file = file
        val problem = { file?.problem() }
        if (file == null && source.substringBefore('?').lowercase(Locale.ROOT).endsWith(".m3u8")) {
            return Triple(source, HLS, problem) // a stream from the web: the TV fetches it itself
        }
        val info = runInterruptible { probe(file, source) }
        // Subtitle files that came with it, then ones added in the app.
        val found = file?.subtitleFiles() ?: emptyList()
        val added = uploads(s.source, s.episode).listFiles()?.sortedBy { it.name }?.map { it.name to it.readBytes() } ?: emptyList()
        val subs = mutableListOf<Subtitle>()
        for ((name, data) in found + added) {
            val vtt = toWebVtt(data, File(name).extension) ?: continue
            File(out, "sub${subs.size}.vtt").writeText(vtt)
            val stem = file?.name?.substringBeforeLast('.') ?: ""
            val base = File(name).nameWithoutExtension
            val label = (if (stem.isNotEmpty() && base.lowercase().startsWith(stem.lowercase())) base.substring(stem.length) else base)
                .trim(' ', '.', '_', '-').ifEmpty { "Subtitles" }
            val (code, language) = language(label)
            subs += Subtitle("${subs.size + 1}. ${if (language.isNotEmpty() && label.length <= 3) language else label}", code) // "tr" reads better as Turkish
        }
        val (copyVideo, canCopyAudio, why, convertVideo) = plan(info, tv)
        // Sound below full volume is converted, which only happens in the app's own HLS: H.264 only (as it is or
        // converted from HEVC).
        val adjustable = info.audio != null && (info.video in setOf(null, "h264") || convertVideo)
        val copyAudio = canCopyAudio && !(adjustable && s.volume < 100)
        volume = if (adjustable) s.volume else null
        if (!copyVideo && !convertVideo) throw IllegalStateException("The phone can't send this one: $why. openplay on a PC converts it (the PC tab).")
        val client = InetAddress.getByName(tv.address)
        // An MP4 the TV plays is sent as it is. Subtitles only travel in the app's own HLS, which carries H.264 only.
        if (copyVideo && copyAudio && info.mp4 && (subs.isEmpty() || info.video !in setOf(null, "h264"))) {
            if (file == null) return Triple(source, "video/mp4", problem)
            server.stream = Stream(client, out, token, file, "video/mp4")
            return Triple(server.url(tv, "/media/$token.mp4"), "video/mp4", problem)
        }
        if (!convertVideo && info.video != null && info.video != "h264") {
            throw IllegalStateException("The phone only repackages H.264 video, and this is ${info.video.uppercase(Locale.ROOT)} " +
                "in a file the TV can't open as it is. openplay on a PC converts it (the PC tab).")
        }
        subtitles = subs
        val remux = Remuxer({ extractor(file, source) }, info, out, copyAudio, volume ?: 100, start, if (convertVideo) tv else null)
        this.remux = remux
        remux.start()
        val trouble = { remux.failure ?: file?.problem() }
        ready(remux.writer, trouble)
        server.stream = Stream(client, out, token, file, subtitles = subs, writer = remux.writer, offset = { remux.offset })
        return Triple(server.url(tv, if (subs.isNotEmpty()) "/hls/master.m3u8" else "/hls/live.m3u8"), HLS, trouble)
    }

    /** Lets a couple of segments build up (or all of a short video) so the player has something to buffer. */
    private suspend fun ready(writer: HlsWriter, problem: () -> String?) {
        while (writer.count < 2 && !writer.ended) {
            problem()?.let { throw IllegalStateException(it) }
            refresh()
            delay(200)
        }
    }

    /** Shows subtitle rendition index (-1: none) on the Cast device playing, and picks its language again next time. */
    fun pickSubtitle(index: Int, remember: Boolean = true) {
        val tv = tv
        if (tv == null || !tv.picksSubtitles || index !in -1 until subtitles.size) throw IllegalArgumentException("No such subtitle")
        tv.subtitle(if (index < 0) null else index)
        subtitle = index
        if (remember) update { it.copy(subtitle = if (index >= 0) subtitles[index].lang else "") }
        refresh()
    }

    /** pickSubtitle off the main thread, errors shown like the rest. */
    fun choose(index: Int) = scope.launch {
        try { pickSubtitle(index) } catch (e: Exception) { error = e.message ?: e.toString(); refresh() }
    }

    /** Puts a video first in the history, once per source (a torrent keeps its latest episode). */
    private fun remember(source: String, episode: Int, name: String, position: Double) {
        history = (listOf(HistoryItem(source, episode, name, System.currentTimeMillis() / 1000, Math.round(position))) +
            history.filter { it.source != source }).take(30)
        saveHistory()
    }

    /** Keeps where a video playing got to, for playing it again from there. */
    private fun leftOff(source: String, episode: Int, position: Double, duration: Double) {
        history = history.map { if (it.source == source && it.episode == episode) it.copy(position = resumePoint(position, duration)) else it }
        saveHistory()
    }

    private fun saveHistory() = historyFile.writeText(JSONArray(history.map {
        JSONObject().put("source", it.source).put("episode", it.episode).put("name", it.name).put("time", it.time).put("position", it.position)
    }).toString())

    /** Folder of the subtitle files added for a video (each episode of a torrent gets its own). */
    private fun uploads(source: String, episode: Int): File {
        val hash = MessageDigest.getInstance("SHA-1").digest("$source#$episode".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "subtitles/${hash.take(16)}")
    }

    /** Keeps a subtitle file for the video in the settings, to play with it from then on. */
    fun addSubtitle(name: String, data: ByteArray) {
        val file = File(name).name // no folders
        if (File(file).extension.lowercase(Locale.ROOT) !in SUBTITLE_FILES) throw IllegalArgumentException("Pick a .srt, .ass, .ssa or .vtt file.")
        if (settings.source.isEmpty()) throw IllegalArgumentException("Enter the video first.")
        uploads(settings.source, settings.episode).apply { mkdirs() }.resolve(file).writeBytes(data)
        refresh()
    }

    fun clearSubtitles() {
        uploads(settings.source, settings.episode).deleteRecursively()
        refresh()
    }

    fun clearDownloads() {
        if (job == "media") throw IllegalStateException("Stop playing first.")
        synchronized(added) {
            added.forEach { t -> runCatching { t.remove() } }
            added.clear()
        }
        scope.launch {
            repeat(20) { // libtorrent lets go of a removed torrent's files a moment later
                downloads.deleteRecursively()
                if (!downloads.exists() || downloads.list().isNullOrEmpty()) return@launch refresh()
                delay(500)
            }
            error = "Some files in the downloads folder are in use and weren't deleted."
            refresh()
        }
    }

    /** Shows an error that stopped a job from starting. */
    fun fail(message: String) {
        status = "error"
        error = message
        refresh()
    }

    /** Clears the last error. */
    fun dismiss() {
        if (status == "error") status = "idle"
        error = ""
        refresh()
    }
}
