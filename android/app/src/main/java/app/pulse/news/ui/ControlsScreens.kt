package app.pulse.news.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.pulse.news.BuildConfig
import app.pulse.news.data.Api
import app.pulse.news.data.Controls
import app.pulse.news.data.Insights
import app.pulse.news.data.OkResponse
import app.pulse.news.data.Store
import app.pulse.news.update.Updater
import app.pulse.news.work.BriefWorker
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    val c = LocalColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp)) {
        SectionTitle(title, hint)
        content()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

@Composable
private fun LabeledSlider(label: String, value: Float, right: String? = null, onDone: (Float) -> Unit) {
    val c = LocalColors.current
    var v by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(110.dp))
        Slider(
            value = v, onValueChange = { v = it }, onValueChangeFinished = { onDone(v) },
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.raised),
            modifier = Modifier.weight(1f),
        )
        Text(right ?: "${(v * 100).toInt()}", style = Type.chip, color = c.text2, modifier = Modifier.width(40.dp).padding(start = 6.dp))
    }
}

fun hourLabel(h: Int) = if (h < 12) "$h am" else if (h == 12) "12 pm" else "${h - 12} pm"

/** What Pulse thinks you like, as a ranked list you can correct. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsightsScreen(onBack: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<Insights?>(null) }
    fun load() = scope.launch { runCatching { Api.get<Insights>("/api/insights") }.onSuccess { d = it } }
    LaunchedEffect(Unit) { load() }

    LazyColumn(Modifier.fillMaxSize().background(c.bg), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        item {
            TextLink("‹ Controls", modifier = Modifier.padding(top = 8.dp), onClick = onBack)
            Text("Insights", style = Type.title, color = c.text, modifier = Modifier.padding(top = 4.dp))
        }
        val data = d ?: return@LazyColumn
        item {
            Text("Learned from ${data.interactions} interactions. Interests fade over about a month unless you keep engaging.", style = Type.meta, color = c.text2, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            Text("You like", style = Type.meta, color = c.text)
        }
        val pos = data.clusters.filter { it.polarity > 0 }
        val neg = data.clusters.filter { it.polarity < 0 }
        if (pos.isEmpty()) item { Text("Nothing learned yet. Swipe right on stories you want more of.", style = Type.meta, color = c.text2, modifier = Modifier.padding(vertical = 8.dp)) }
        pos.forEach { cl -> item(key = "p${cl.id}") { ClusterRow(cl.label, cl.strength, "${cl.signals} signals", c.accent) { scope.launch { runCatching { Api.delete("/api/insights/${cl.id}") }; load() } } } }
        if (neg.isNotEmpty()) item { Text("You'd rather see less of", style = Type.meta, color = c.text, modifier = Modifier.padding(top = 20.dp)) }
        neg.forEach { cl -> item(key = "n${cl.id}") { ClusterRow(cl.label, cl.strength, null, c.less) { scope.launch { runCatching { Api.delete("/api/insights/${cl.id}") }; load() } } } }
        item {
            Text("Per-topic learning", style = Type.meta, color = c.text, modifier = Modifier.padding(top = 28.dp))
            Text("Pause learning if Pulse over-learned something you liked once. Reset forgets it.", style = Type.chip, color = c.text2, modifier = Modifier.padding(bottom = 10.dp))
        }
        data.topics.forEach { t ->
            item(key = "t$t") {
                val paused = t in data.pausedTopics
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t, style = Type.meta, color = c.text, modifier = Modifier.weight(1f))
                    Pill(if (paused) "Paused" else "Pause", selected = paused, style = Type.chip, padding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                        val next = if (paused) data.pausedTopics - t else data.pausedTopics + t
                        scope.launch {
                            runCatching { Api.put<app.pulse.news.data.Prefs>("/api/prefs", buildJsonObject { putJsonArray("paused_topics") { next.forEach { add(it) } } }) }
                            d = data.copy(pausedTopics = next)
                        }
                    }
                    Pill("Reset", style = Type.chip, padding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                        scope.launch {
                            runCatching { Api.post<OkResponse>("/api/insights/reset-topic", buildJsonObject { put("topic", t) }) }
                                .onSuccess { Toaster.show("Forgot ${it.removed} learned interest(s) for $t"); load() }
                        }
                    }
                }
            }
        }
        item {
            Pill("Rebuild profile from history", modifier = Modifier.padding(top = 24.dp)) {
                scope.launch {
                    runCatching { Api.post<OkResponse>("/api/insights/rebuild") }.onSuccess { Toaster.show("Rebuilt from ${it.replayed} interactions"); load() }
                }
            }
        }
    }
}

@Composable
private fun ClusterRow(label: String, strength: Float, meta: String?, color: Color, onForget: () -> Unit) {
    val c = LocalColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(c.raised)) {
                Box(Modifier.fillMaxWidth(strength.coerceIn(0.02f, 1f)).height(6.dp).background(color))
            }
        }
        meta?.let { Text(it, style = Type.chip, color = c.text2) }
        Pill("Forget", style = Type.chip, padding = PaddingValues(horizontal = 10.dp, vertical = 5.dp), onClick = onForget)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}
