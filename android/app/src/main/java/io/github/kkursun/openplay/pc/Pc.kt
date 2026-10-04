package io.github.kkursun.openplay.pc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.kkursun.openplay.Finder
import io.github.kkursun.openplay.Found
import io.github.kkursun.openplay.phone.Device
import io.github.kkursun.openplay.phone.HistoryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The dashboard's form: what the phone sends with every action, as the dashboard does. */
data class PcSettings(
    val device: String = "",
    val display: Int = 0,
    val height: Int = 1080,
    val fps: Int = 30,
    val bitrate: Int = 6,
    val segment: Double = 0.5,
    val audio: Boolean = true,
    val speaker: String = "",
    val source: String = "",
) {
    fun json(): JSONObject = JSONObject().put("device", device).put("display", display).put("height", height).put("fps", fps)
        .put("bitrate", bitrate).put("segment", segment).put("audio", audio).put("speaker", speaker).put("source", source)

    companion object {
        fun from(j: JSONObject) = PcSettings(
            j.optString("device"), j.optInt("display"), j.optInt("height", 1080), j.optInt("fps", 30), j.optInt("bitrate", 6),
            j.optDouble("segment", 0.5), j.optBoolean("audio", true), j.optString("speaker"), j.optString("source"),
        )
    }
}

/** mirror.py's /api/state. */
data class PcState(
    val status: String,
    val error: String,
    val uptime: Int,
    val detail: String,
    val subtitles: List<String>,
    val subtitle: Int,
    val episodes: List<String>,
    val episode: Int,
    val uploaded: List<String>,
    val downloads: Long,
    val history: List<HistoryItem>,
    val settings: PcSettings,
    val speakers: List<String>,
    val running: PcSettings?,
    val job: String?, // "mirror" or "media" while one runs
    val devices: List<Device>,
    val computer: String,
) {
    companion object {
        private fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }

        fun from(j: JSONObject): PcState {
            val running = j.optJSONObject("running")
            val history = j.optJSONArray("history") ?: JSONArray()
            val devices = j.optJSONArray("devices") ?: JSONArray()
            return PcState(
                j.optString("status", "idle"), j.optString("error"), j.optInt("uptime"), j.optString("detail"),
                strings(j.optJSONArray("subtitles")), j.optInt("subtitle", -1), strings(j.optJSONArray("episodes")), j.optInt("episode"),
                strings(j.optJSONArray("uploaded")), j.optLong("downloads"),
                (0 until history.length()).map { history.getJSONObject(it) }
                    .map { HistoryItem(it.optString("source"), it.optInt("episode"), it.optString("name"), it.optLong("time")) },
                PcSettings.from(j.optJSONObject("settings") ?: JSONObject()), strings(j.optJSONArray("speakers")),
                running?.let { PcSettings.from(it) }, running?.optString("job"),
                (0 until devices.length()).map { devices.getJSONObject(it) }
                    .map { Device(it.optString("name"), it.optString("model"), it.optString("address")) },
                j.optString("computer"),
            )
        }
    }
}

/** The code didn't match: the PC's dashboard made a new one, or it was mistyped. */
class NotPaired : IOException("The pairing code doesn't match. The PC's dashboard shows the current one under Phone.")

/** mirror.py's dashboard API on a PC, reached with its pairing code. */
class PcApi(val base: String, private val code: String) {
    fun state(): PcState = PcState.from(request("/api/state", null))

    fun post(path: String, body: JSONObject = JSONObject()): PcState = PcState.from(request(path, body))

    private fun request(path: String, body: JSONObject?): JSONObject = try {
        send(path, body)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) { // a reply that isn't openplay's, or an address that can't be one
        throw IOException(if (e is org.json.JSONException) "That isn't openplay answering." else e.message ?: e.toString(), e)
    }

    private fun send(path: String, body: JSONObject?): JSONObject {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000
            c.readTimeout = 60_000 // a scan or clearing downloads takes seconds
            c.setRequestProperty("Authorization", "Bearer $code")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json") // exactly: the server checks
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = c.responseCode
            if (status == 403) throw NotPaired()
            val text = (if (status < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (status >= 400) {
                throw IOException(runCatching { JSONObject(text).getString("error") }.getOrElse { "The PC answered HTTP $status." })
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}

data class PcUi(
    val address: String = "", // "http://192.168.1.5:8000", once paired
    val name: String = "",
    val paired: Boolean = false,
    val state: PcState? = null,
    val form: PcSettings = PcSettings(), // the controls as set on the phone; sent with each action
    val busy: Boolean = false,
    val error: String = "", // from the last action
    val offline: String = "", // why the PC can't be reached, while it can't
    val finding: Boolean = false,
    val found: List<Found> = emptyList(),
)

/** The PC remote: pairs with openplay on a PC and drives its dashboard API. */
object Pc {
    private lateinit var prefs: SharedPreferences
    private lateinit var finder: Finder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ui = MutableStateFlow(PcUi())
    val ui: StateFlow<PcUi> = _ui.asStateFlow()
    private var api: PcApi? = null
    private var filled = false

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.getSharedPreferences("pc", Context.MODE_PRIVATE)
        finder = Finder(context)
        val address = prefs.getString("address", "") ?: ""
        val code = prefs.getString("code", "") ?: ""
        if (address.isNotEmpty() && code.isNotEmpty()) api = PcApi(address, code)
        _ui.value = PcUi(address, prefs.getString("name", "") ?: "", api != null)
    }

    /** Looks for PCs running openplay on the network. */
    fun find() {
        if (_ui.value.finding) return
        _ui.update { it.copy(finding = true) }
        scope.launch {
            val found = runCatching { finder.find("_openplay._tcp", 4) }.getOrElse { emptyList() }
            _ui.update { it.copy(finding = false, found = found) }
        }
    }

    /** "192.168.1.5", "192.168.1.5:8000" or a full URL, as a base URL. */
    fun address(typed: String): String {
        val t = typed.trim().trimEnd('/')
        val withScheme = if (Regex("https?://.*").matches(t)) t else "http://$t"
        return if (Regex("https?://[^/]+:\\d+.*").matches(withScheme)) withScheme else "$withScheme:8000"
    }

    fun pair(typedAddress: String, code: String) {
        val base = address(typedAddress)
        _ui.update { it.copy(busy = true, error = "") }
        scope.launch {
            try {
                val candidate = PcApi(base, code.trim())
                val st = candidate.state()
                api = candidate
                filled = false
                prefs.edit { putString("address", base).putString("code", code.trim()).putString("name", st.computer) }
                _ui.update { it.copy(address = base, name = st.computer, paired = true, busy = false) }
                show(st)
            } catch (e: IOException) {
                _ui.update { it.copy(busy = false, error = if (e is NotPaired) e.message!! else "Can't reach openplay at $base: ${e.message}") }
            }
        }
    }

    fun unpair() {
        api = null
        prefs.edit { remove("code") }
        _ui.update { PcUi(address = it.address, found = it.found) }
    }

    private fun show(st: PcState) {
        _ui.update {
            // The form is filled from the PC once; after that the phone's own choices stand, as on the dashboard.
            val form = if (filled) it.form else st.settings
            filled = true
            it.copy(state = st, form = form, offline = "")
        }
    }

    /** Fetches the PC's state; the screen calls this every second while it's shown. */
    suspend fun poll() {
        val a = api ?: return
        try {
            val st = withContext(Dispatchers.IO) { a.state() }
            if (!_ui.value.busy) show(st)
        } catch (e: NotPaired) {
            unpair()
            _ui.update { it.copy(error = e.message!!) }
        } catch (e: IOException) {
            _ui.update { it.copy(offline = "Can't reach ${it.name.ifEmpty { "the PC" }}: ${e.message}") }
        }
    }

    private fun act(path: String, body: JSONObject = JSONObject()) {
        val a = api ?: return
        _ui.update { it.copy(busy = true, error = "") }
        scope.launch {
            try {
                show(a.post(path, body))
            } catch (e: IOException) {
                if (e is NotPaired) unpair()
                _ui.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(busy = false) }
            }
        }
    }

    /** Changes the form and saves it on the PC, as the dashboard does on every change. */
    fun set(change: (PcSettings) -> PcSettings) {
        val form = change(_ui.value.form)
        _ui.update { it.copy(form = form) }
        val a = api ?: return
        scope.launch { runCatching { show(a.post("/api/settings", form.json())) } }
    }

    /** Changes the form without saving it yet (text being typed). */
    fun edit(change: (PcSettings) -> PcSettings) = _ui.update { it.copy(form = change(it.form)) }

    fun start() = act("/api/start", _ui.value.form.json())
    fun stop() = act("/api/stop")
    // Without an episode, a torrent plays the one picked last time (the first, for a new video).
    fun play(episode: Int? = null) = act("/api/play", _ui.value.form.json().apply { if (episode != null) put("episode", episode) })
    fun subtitle(index: Int) = act("/api/subtitle", JSONObject().put("index", index))
    fun upload(name: String, base64: String) = act("/api/upload-subtitle", _ui.value.form.json().put("name", name).put("data", base64))
    fun clearSubtitles() = act("/api/clear-subtitles", _ui.value.form.json())
    fun clearDownloads() = act("/api/clear-downloads")
    fun scan() = act("/api/scan")
    fun dismiss() = _ui.update { it.copy(error = "") }
}
