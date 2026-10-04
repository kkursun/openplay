package io.github.kkursun.openplay

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.kkursun.openplay.pc.Pc
import io.github.kkursun.openplay.phone.Phone
import io.github.kkursun.openplay.ui.History
import io.github.kkursun.openplay.ui.OpenplayTheme
import io.github.kkursun.openplay.ui.PcHome
import io.github.kkursun.openplay.ui.PcPairing
import io.github.kkursun.openplay.ui.PhoneHome
import io.github.kkursun.openplay.ui.StatusPill
import io.github.kkursun.openplay.ui.pcLabel
import io.github.kkursun.openplay.ui.phoneLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Phone.init(this)
        Pc.init(this)
    }
}

class MainActivity : ComponentActivity() {
    private val mode = mutableStateOf("phone") // "phone": this phone plays to the TV; "pc": remote for openplay on a PC
    private val message = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        mode.value = prefs.getString("mode", "phone") ?: "phone"
        if (savedInstanceState == null) take(intent)
        setContent {
            OpenplayTheme {
                Screen(mode.value, { mode.value = it; prefs.edit { putString("mode", it) } }, message.value) { message.value = "" }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    /** A link or video shared to the app, or opened with it: put in the Play box, ready to play. */
    private fun take(intent: Intent?) {
        val stream = when {
            intent?.action != Intent.ACTION_SEND -> null
            Build.VERSION.SDK_INT >= 33 -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else -> @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> stream?.toString() ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        if (text.startsWith("content:")) { // a file on the phone: only the phone can play it
            runCatching { contentResolver.takePersistableUriPermission(text.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            mode.value = "phone"
            Phone.update { it.copy(source = text) }
        } else {
            val link = Regex("magnet:\\?\\S+|https?://\\S+").find(text)?.value ?: return
            if (mode.value == "pc" && Pc.ui.value.paired) Pc.set { it.copy(source = link) } else Phone.update { it.copy(source = link) }
        }
        message.value = "Ready to play: pick the TV and press Play."
    }
}

@Composable
private fun Screen(mode: String, setMode: (String) -> Unit, message: String, clearMessage: () -> Unit) {
    val phone by Phone.state.collectAsStateWithLifecycle()
    val pc by Pc.ui.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify = { text: String -> scope.launch { snackbar.showSnackbar(text) }; Unit }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(mode, pc.paired) {
        if (mode == "pc" && pc.paired) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                Pc.poll()
                delay(1000)
            }
        }
    }
    LaunchedEffect(message) {
        if (message.isNotEmpty()) {
            snackbar.showSnackbar(message)
            clearMessage()
        }
    }
    val (status, label) = if (mode == "pc") pcLabel(pc) else phone.status to phoneLabel(phone, now)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("openplay", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                StatusPill(status, label)
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(mode == "phone", { setMode("phone") }, { Icon(painterResource(R.drawable.ic_phone), null) }, label = { Text("This phone") })
                NavigationBarItem(mode == "pc", { setMode("pc") }, { Icon(painterResource(R.drawable.ic_pc), null) }, label = { Text("PC remote") })
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 600.dp).padding(horizontal = 16.dp)) {
                val showTabs = mode == "phone" || pc.paired
                if (showTabs) {
                    PrimaryTabRow(tab, Modifier.padding(bottom = 12.dp)) {
                        Tab(tab == 0, { tab = 0 }, text = { Text("Home") })
                        Tab(tab == 1, { tab = 1 }, text = { Text("History") })
                    }
                }
                when {
                    mode == "phone" && tab == 0 -> PhoneHome(phone, notify)
                    mode == "phone" -> History(phone.history, phone.status != "starting") { source, episode ->
                        tab = 0
                        Phone.update(episodeGiven = true) { it.copy(source = source, episode = episode) }
                        PlayService.play(context)
                    }
                    !pc.paired -> PcPairing(pc)
                    tab == 0 -> PcHome(pc, notify)
                    else -> History(pc.state?.history ?: emptyList(), !pc.busy) { source, episode ->
                        tab = 0
                        Pc.edit { it.copy(source = source) }
                        Pc.play(episode)
                    }
                }
            }
        }
    }
}
