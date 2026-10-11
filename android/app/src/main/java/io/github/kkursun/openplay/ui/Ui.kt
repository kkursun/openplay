package io.github.kkursun.openplay.ui

import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

val Live = Color(0xFF17A34A)
val Warn = Color(0xFFD98A06)
val Err = Color(0xFFD93636)

@Composable
fun OpenplayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF4D86FF))
        else -> lightColorScheme(primary = Color(0xFF2F6FED))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** A card of related controls, like the dashboard's sections. */
@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            content()
        }
    }
}

/** A labelled control, with an optional hint under the label. */
@Composable
fun Setting(label: String, hint: String = "", control: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            if (hint.isNotEmpty()) Hint(hint)
        }
        Spacer(Modifier.width(12.dp))
        control()
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** A button showing the chosen option that opens a menu of the others. */
@Composable
fun <T> Choice(options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, enabled = enabled && options.isNotEmpty(), modifier = Modifier.widthIn(max = 280.dp)) {
            Text(options.firstOrNull { it.first == selected }?.second ?: "Choose…", maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(" ▾")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onSelect(value) })
            }
        }
    }
}

@Composable
fun Toggle(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) = Switch(checked, onChange, enabled = enabled)

/** A slider with its value beside it: changes are sent once the thumb is let go. */
@Composable
fun ValueSlider(value: Int, range: IntRange, steps: Int, unit: String, onDone: (Int) -> Unit) {
    var dragging by remember { mutableFloatStateOf(-1f) }
    val shown = if (dragging >= 0) dragging else value.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(shown, { dragging = it }, Modifier.width(140.dp), valueRange = range.first.toFloat()..range.last.toFloat(), steps = steps,
            onValueChangeFinished = { onDone(Math.round(dragging)); dragging = -1f })
        Text("${Math.round(shown)}$unit", Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun BitrateSlider(value: Int, onDone: (Int) -> Unit) = ValueSlider(value, 2..12, 9, " Mbps", onDone)

/** The sound's volume, in steps of 5. */
@Composable
fun VolumeSlider(value: Int, onDone: (Int) -> Unit) = ValueSlider(value, 0..100, 19, "%", onDone)

/** Status like the dashboard's pill: a coloured dot and a word. */
@Composable
fun StatusPill(status: String, label: String) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(1f, 0.3f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "dot")
    val color = when (status) { "live" -> Live; "starting" -> Warn; "error" -> Err; else -> MaterialTheme.colorScheme.outline }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).alpha(if (status == "starting") pulse else 1f).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun ErrorBox(text: String, onDismiss: (() -> Unit)? = null) {
    if (text.isEmpty()) return
    Surface(Modifier.fillMaxWidth().padding(bottom = 12.dp), shape = MaterialTheme.shapes.medium, color = Err.copy(alpha = 0.12f)) {
        Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f).padding(vertical = 8.dp), color = Err, style = MaterialTheme.typography.bodyMedium)
            if (onDismiss != null) TextButton(onDismiss) { Text("OK") }
        }
    }
}

fun clock(seconds: Long) = String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)

fun bytes(n: Long) = if (n >= 1e9) String.format(Locale.ROOT, "%.1f GB", n / 1e9) else "${Math.ceil(n / 1e6).toLong()} MB"

val HEIGHTS = listOf(1080 to "1080p", 720 to "720p")
val RATES = listOf(30 to "30 fps", 25 to "25 fps", 24 to "24 fps", 15 to "15 fps")
val SEGMENTS = listOf(0.5 to "0.5 s", 1.0 to "1 s", 2.0 to "2 s")
