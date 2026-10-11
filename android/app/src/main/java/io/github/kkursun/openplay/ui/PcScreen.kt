package io.github.kkursun.openplay.ui

import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.kkursun.openplay.pc.Pc
import io.github.kkursun.openplay.pc.PcUi

fun pcLabel(ui: PcUi): Pair<String, String> {
    val st = ui.state ?: return "idle" to (if (ui.paired) "Connecting…" else "Not paired")
    val label = when (st.status) {
        "starting" -> "Starting…"
        "live" -> "${if (st.job == "media") "Playing" else "Live"} · ${clock(st.uptime.toLong())}"
        "error" -> "Error"
        else -> "Idle"
    }
    return st.status to label
}

@Composable
fun PcPairing(ui: PcUi) {
    var address by rememberSaveable { mutableStateOf(ui.address.removePrefix("http://").removeSuffix(":8000")) }
    var code by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { Pc.find() }
    Section("Pair with a PC") {
        Hint("Run openplay on the PC (python mirror.py) and open its dashboard: the Phone tab there shows the pairing code. " +
            "The phone and the PC need to be on the same network.")
        Spacer(Modifier.size(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PCs found", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            TextButton(onClick = { Pc.find() }, enabled = !ui.finding) { Text(if (ui.finding) "Looking…" else "Look again") }
        }
        if (ui.found.isEmpty() && !ui.finding) Hint("None yet. Type the address the dashboard shows instead.")
        ui.found.forEach { pc ->
            val at = "${pc.host}:${pc.port}"
            Row(Modifier.fillMaxWidth().clickable { address = at }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(pc.name, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Hint(at)
            }
        }
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Address") },
            placeholder = { Text("192.168.1.5:8000") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
        OutlinedTextField(code, { code = it.uppercase() }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Pairing code") },
            placeholder = { Text("ABCD-EFGH-JKLM") }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (address.isNotBlank() && code.isNotBlank()) Pc.pair(address, code) }))
        Button(onClick = { Pc.pair(address, code) }, Modifier.fillMaxWidth().padding(top = 12.dp),
            enabled = address.isNotBlank() && code.isNotBlank() && !ui.busy) { Text(if (ui.busy) "Pairing…" else "Pair") }
    }
    ErrorBox(ui.error) { Pc.dismiss() }
}

@Composable
fun PcHome(ui: PcUi, notify: (String) -> Unit) {
    val context = LocalContext.current
    val st = ui.state
    val f = ui.form
    val running = st != null && (st.status == "live" || st.status == "starting")
    val job = if (running) st!!.job else null
    var confirm by rememberSaveable { mutableStateOf(false) }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        // base64: the PC works out the text encoding
        try {
            readPicked(context, uris).forEach { (name, data) -> Pc.upload(name, Base64.encodeToString(data, Base64.NO_WRAP)) }
        } catch (e: Exception) {
            notify(e.message ?: e.toString())
        }
    }

    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Controlling ${ui.name.ifEmpty { "your PC" }}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { Pc.unpair() }) { Text("Unpair") }
    }
    ErrorBox(ui.offline)
    if (st == null) return

    Section("Device") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val devices = st.devices.map { it.name to "${it.name} · ${it.model}" }.toMutableList()
            if (f.device.isNotEmpty() && st.devices.none { it.name == f.device }) devices.add(0, f.device to "${f.device} (not found)")
            Choice(devices, f.device, modifier = Modifier.weight(1f)) { name -> Pc.set { it.copy(device = name) } }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { Pc.scan() }, enabled = !ui.busy) { Text("Scan") }
        }
    }

    Section("Play a video") {
        OutlinedTextField(f.source, { text -> Pc.edit { it.copy(source = text) } }, Modifier.fillMaxWidth(),
            placeholder = { Text("File path on the PC, URL, magnet link or .torrent") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { Pc.play() }))
        Button(onClick = { Pc.play() }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !ui.busy && f.source.isNotBlank()) { Text("Play") }
        if (st.episodes.size > 1) {
            HorizontalDivider(Modifier.padding(top = 10.dp))
            Setting("Episode") { Choice(st.episodes.mapIndexed { i, n -> i to n }, st.episode, enabled = !ui.busy) { Pc.play(it) } }
        }
        if (st.subtitles.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(top = 4.dp))
            Setting("Subtitles", "Your pick's language is picked again next time") {
                Choice(listOf(-1 to "Off") + st.subtitles.mapIndexed { i, n -> i to n }, st.subtitle) { Pc.subtitle(it) }
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
        Setting("Subtitle files", if (st.uploaded.isNotEmpty()) "${st.uploaded.joinToString(", ")} · used from the next Play" else "Add .srt files for the video above") {
            if (st.uploaded.isNotEmpty()) TextButton(onClick = { Pc.clearSubtitles() }) { Text("Remove") }
            OutlinedButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = !ui.busy && f.source.isNotBlank()) { Text("Add…") }
        }
        HorizontalDivider()
        Setting("Downloads", if (st.downloads > 0) "${bytes(st.downloads)} kept to replay without downloading again" else "Empty") {
            OutlinedButton(onClick = { confirm = true }, enabled = !ui.busy && st.downloads > 0 && job != "media") { Text("Delete") }
        }
        Hint("Torrents play while they download, into the PC's downloads folder. Anything the TV can't play as it is " +
            "gets converted with the resolution and bitrate below.", Modifier.padding(top = 6.dp))
    }

    Section("Picture") {
        Setting("Display", "0 is usually the PC's main screen") {
            Choice((0..8).map { it to "$it" }, f.display) { d -> Pc.set { it.copy(display = d) } }
        }
        Setting("Resolution") { Choice(HEIGHTS, f.height) { h -> Pc.set { it.copy(height = h) } } }
        Setting("Frame rate", "Use 25 if your TV runs at 50 Hz") { Choice(RATES, f.fps) { r -> Pc.set { it.copy(fps = r) } } }
        Setting("Bitrate", "Higher is sharper, needs better Wi-Fi") { BitrateSlider(f.bitrate) { b -> Pc.set { it.copy(bitrate = b) } } }
        Setting("Segment length", "Longer can be smoother, adds a little delay") { Choice(SEGMENTS, f.segment) { g -> Pc.set { it.copy(segment = g) } } }
    }

    Section("Sound") {
        Setting("Send sound") { Toggle(f.audio) { a -> Pc.set { it.copy(audio = a) } } }
        Setting("Capture from", "Default follows the PC's output; switches live") {
            Choice(listOf("" to "Default output") + st.speakers.map { it to it }, f.speaker, enabled = f.audio) { sp -> Pc.set { it.copy(speaker = sp) } }
        }
        Setting("Volume", "Mirroring changes live; a video takes it from the next Play. The TV's own volume still applies") {
            VolumeSlider(f.volume) { v -> Pc.set { it.copy(volume = v) } }
        }
    }

    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val big = Modifier.weight(1f)
        val stop = ButtonDefaults.buttonColors(containerColor = Err)
        when (job) {
            "mirror" -> {
                OutlinedButton(onClick = { Pc.start() }, big, enabled = !ui.busy) { Text("Restart") }
                Button(onClick = { Pc.stop() }, big, enabled = !ui.busy, colors = stop) { Text("Stop") }
            }
            "media" -> Button(onClick = { Pc.stop() }, big, enabled = !ui.busy, colors = stop) { Text("Stop") }
            else -> Button(onClick = { Pc.start() }, big, enabled = !ui.busy) { Text("Start mirroring") }
        }
    }
    ErrorBox(ui.error.ifEmpty { st.error }) { Pc.dismiss() }
    // The sound source and volume switch live; everything else needs a restart.
    val r = st.running
    val changed = job == "mirror" && r != null && f.copy(speaker = r.speaker, volume = r.volume, source = r.source) != r
    // A video is converted ahead of the TV, so a new volume only reaches it when it's played again.
    val quieter = job == "media" && r != null && f.volume != r.volume
    val note = if (changed) "Press Restart to apply changes." else if (quieter) "Press Play to apply the volume. The video starts over." else st.detail
    if (note.isNotEmpty()) Hint(note, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    Hint("Mirroring lags about 3 s on an Apple TV, more on Cast. Back or Menu on the TV remote also stops it.", Modifier.padding(bottom = 16.dp))

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            confirmButton = { TextButton(onClick = { confirm = false; Pc.clearDownloads() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
            text = { Text("Delete everything in the PC's downloads folder? Torrents download from scratch next time.") },
        )
    }
}
