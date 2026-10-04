package io.github.kkursun.openplay.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.kkursun.openplay.PlayService
import io.github.kkursun.openplay.phone.Phone
import io.github.kkursun.openplay.phone.PhoneState

fun phoneLabel(st: PhoneState, now: Long): String = when (st.status) {
    "starting" -> "Starting…"
    "live" -> "${if (st.job == "media") "Playing" else "Live"} · ${clock((now - st.since) / 1000)}"
    "error" -> "Error"
    else -> "Idle"
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

fun fileName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment ?: "file"

/** Reads files picked in the system picker: (name, contents). */
fun readPicked(context: Context, uris: List<Uri>): List<Pair<String, ByteArray>> =
    uris.mapNotNull { uri -> context.contentResolver.openInputStream(uri)?.use { fileName(context, uri) to it.readBytes() } }

@Composable
fun PhoneHome(st: PhoneState, notify: (String) -> Unit) {
    val context = LocalContext.current
    val s = st.settings
    val running = st.status == "starting" || st.status == "live"
    var confirm by rememberSaveable { mutableStateOf(false) }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    try { Phone.clearDownloads() } catch (e: Exception) { notify(e.message ?: e.toString()) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            text = { Text("Delete the phone's downloaded torrents? They download from scratch next time.") },
        )
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            PlayService.mirror(context, r.resultCode, data, s.audio && granted(context, Manifest.permission.RECORD_AUDIO))
        }
    }
    val share = { projection.launch(context.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent()) }
    val askMicrophone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) notify("Mirroring without sound: sending it needs the microphone permission.")
        share()
    }
    val chooseVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Kept, so the history can play it again later.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            Phone.update { it.copy(source = uri.toString()) }
        }
    }
    val addSubtitles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        try {
            readPicked(context, uris).forEach { (name, data) -> Phone.addSubtitle(name, data) }
        } catch (e: Exception) {
            notify(e.message ?: e.toString())
        }
    }
    val beforeStart = {
        if (Build.VERSION.SDK_INT >= 33 && !granted(context, Manifest.permission.POST_NOTIFICATIONS)) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val play = { episode: Int? ->
        if (episode != null) Phone.update { it.copy(episode = episode) }
        beforeStart()
        PlayService.play(context)
    }
    val mirror = {
        beforeStart()
        if (s.audio && !granted(context, Manifest.permission.RECORD_AUDIO)) askMicrophone.launch(Manifest.permission.RECORD_AUDIO) else share()
    }

    Section("TV") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val devices = st.devices.map { it.name to "${it.name} · ${it.model}" }.toMutableList()
            if (s.device.isNotEmpty() && st.devices.none { it.name == s.device }) devices.add(0, s.device to "${s.device} (not found)")
            Choice(devices, s.device, modifier = Modifier.weight(1f)) { name -> Phone.update { it.copy(device = name) } }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = { Phone.scan() }, enabled = !st.scanning) { Text(if (st.scanning) "Scanning…" else "Scan") }
        }
        if (st.devices.isEmpty() && !st.scanning) Hint("No TVs found yet. The phone needs to be on the same Wi-Fi as the TV.")
    }

    Section("Play a video") {
        if (s.source.startsWith("content:")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Phone.displayName(s.source), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                TextButton(onClick = { Phone.update { it.copy(source = "") } }) { Text("Clear") }
            }
        } else {
            OutlinedTextField(
                s.source, { text -> Phone.update { it.copy(source = text) } }, Modifier.fillMaxWidth(),
                placeholder = { Text("URL or magnet link") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!running) play(null) }),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseVideo.launch(arrayOf("video/*", "application/x-bittorrent")) }, Modifier.weight(1f)) { Text("Choose file…") }
            Button(onClick = { play(null) }, Modifier.weight(1f), enabled = s.source.isNotBlank() && st.status != "starting") { Text("Play") }
        }
        if (st.episodes.size > 1) {
            HorizontalDivider(Modifier.padding(top = 10.dp))
            Setting("Episode") {
                Choice(st.episodes.mapIndexed { i, name -> i to name }, st.episode, enabled = st.status != "starting") { play(it) }
            }
        }
        if (st.subtitles.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(top = 4.dp))
            Setting("Subtitles", "Your pick's language is picked again next time") {
                Choice(listOf(-1 to "Off") + st.subtitles.mapIndexed { i, name -> i to name }, st.subtitle) { Phone.choose(it) }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
        Setting("Subtitle files", if (st.uploaded.isNotEmpty()) "${st.uploaded.joinToString(", ")} · used from the next Play" else "Add .srt files for the video above") {
            if (st.uploaded.isNotEmpty()) TextButton(onClick = { Phone.clearSubtitles() }) { Text("Remove") }
            OutlinedButton(onClick = { addSubtitles.launch(arrayOf("*/*")) }, enabled = s.source.isNotBlank()) { Text("Add…") }
        }
        HorizontalDivider()
        Setting("Downloads", if (st.downloads > 0) "${bytes(st.downloads)} kept to replay without downloading again" else "Empty") {
            OutlinedButton(onClick = { confirm = true }, enabled = st.downloads > 0 && st.job != "media") { Text("Delete") }
        }
        Hint("Files on the phone, web videos and torrents (which play while they download) go to the TV as they " +
            "are when it can play them, or repackaged for it when they're H.264. Subtitles come from the torrent and " +
            "the files you add. Videos that need converting play from openplay on a PC (the PC tab).", Modifier.padding(top = 6.dp))
    }

    Section("Mirror this phone") {
        Setting("Resolution") { Choice(HEIGHTS, s.height, enabled = !running) { h -> Phone.update { it.copy(height = h) } } }
        Setting("Frame rate", "Use 25 if your TV runs at 50 Hz") { Choice(RATES, s.fps, enabled = !running) { f -> Phone.update { it.copy(fps = f) } } }
        Setting("Bitrate", "Higher is sharper, needs better Wi-Fi") { BitrateSlider(s.bitrate) { b -> Phone.update { it.copy(bitrate = b) } } }
        Setting("Segment length", "Longer can be smoother, adds a little delay") {
            Choice(SEGMENTS, s.segment, enabled = !running) { g -> Phone.update { it.copy(segment = g) } }
        }
        Setting("Send sound", "From apps that allow it to be captured") { Toggle(s.audio, !running) { a -> Phone.update { it.copy(audio = a) } } }
    }

    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val big = Modifier.weight(1f)
        when (if (running) st.job else null) {
            "mirror" -> {
                OutlinedButton(onClick = mirror, big) { Text("Restart") }
                Button(onClick = { Phone.stop() }, big, colors = ButtonDefaults.buttonColors(containerColor = Err)) { Text("Stop") }
            }
            "media" -> Button(onClick = { Phone.stop() }, big, colors = ButtonDefaults.buttonColors(containerColor = Err)) { Text("Stop") }
            else -> Button(onClick = mirror, big) { Text("Start mirroring") }
        }
    }
    ErrorBox(st.error) { Phone.dismiss() }
    if (st.detail.isNotEmpty()) Hint(st.detail, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    Hint("Mirroring lags a few seconds behind the phone. Back or Menu on the TV remote also stops it.", Modifier.padding(bottom = 16.dp))
}

@Composable
fun History(items: List<io.github.kkursun.openplay.phone.HistoryItem>, enabled: Boolean, play: (String, Int) -> Unit) {
    Section("Recently played") {
        if (items.isEmpty()) Hint("Videos you play show up here.")
        items.forEachIndexed { i, h ->
            if (i > 0) HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(h.name, fontWeight = FontWeight.Medium)
                    Hint(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(h.time * 1000)))
                }
                OutlinedButton(onClick = { play(h.source, h.episode) }, enabled = enabled) { Text("Play") }
            }
        }
    }
}
