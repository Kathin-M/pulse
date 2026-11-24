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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(onInsights: () -> Unit, onOnboarding: () -> Unit, onLogout: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Controls?>(null) }
    var kw by remember { mutableStateOf("") }
    var kwKind by remember { mutableStateOf("keyword") }
    var kwMode by remember { mutableStateOf("follow") }
    var feedUrl by remember { mutableStateOf("") }
    var showSources by remember { mutableStateOf(false) }
    var cityMenu by remember { mutableStateOf(false) }
    var brief by remember { mutableStateOf(Store.briefEnabled) }
    var briefHour by remember { mutableStateOf(Store.briefHour) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun load() = scope.launch { runCatching { Api.get<Controls>("/api/controls") }.onSuccess { data = it }.onFailure { Toaster.show(it.message ?: "Offline") } }
    LaunchedEffect(Unit) { load() }

    fun putPrefs(body: kotlinx.serialization.json.JsonObject) = scope.launch {
        runCatching { Api.put<app.pulse.news.data.Prefs>("/api/prefs", body) }
            .onSuccess { p -> data = data?.copy(prefs = p) }
            .onFailure { Toaster.show(it.message ?: "Couldn't save") }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { brief = true; Store.briefEnabled = true; BriefWorker.schedule(ctx) }
        else Toaster.show("Notifications are off for Pulse in system settings")
    }

    val d = data
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        item {
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Controls", style = Type.title, color = c.text)
                Spacer(Modifier.weight(1f))
                Pill("Insights →", onClick = onInsights)
            }
        }
        if (d == null) return@LazyColumn

        item {
            Section("Location", "Each tier has its own slider. Local stories always get a minimum share of the feed.") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    Text("City", style = Type.meta, color = c.text, modifier = Modifier.width(110.dp))
                    Box {
                        Pill("${d.prefs.city} ▾") { cityMenu = true }
                        DropdownMenu(expanded = cityMenu, onDismissRequest = { cityMenu = false }) {
                            d.cities.forEach { city ->
                                DropdownMenuItem(text = { Text("${city.name}, ${city.state}") }, onClick = {
                                    cityMenu = false
                                    putPrefs(buildJsonObject { put("city", city.name) })
                                })
                            }
                        }
                    }
                }
                listOf("city" to d.prefs.city, "state" to d.prefs.state, "india" to "India", "world" to "World").forEach { (k, label) ->
                    LabeledSlider(label, d.prefs.tiers[k] ?: 0.5f) { v -> putPrefs(buildJsonObject { putJsonObject("tiers") { put(k, v) } }) }
                }
            }
        }

        item {
            Section("Topics", "Tap to cycle: neutral → follow → mute.") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.topics.forEach { t ->
                        val mode = d.rules.firstOrNull { it.kind == "topic" && it.label == t }?.mode ?: "neutral"
                        val next = when (mode) { "neutral" -> "follow"; "follow" -> "mute"; else -> "neutral" }
                        Pill(
                            (if (mode == "follow") "✓ " else "") + t,
                            selected = mode == "follow" || mode == "always",
                            color = if (mode == "mute") c.less else null,
                            style = if (mode == "mute") Type.meta.copy(textDecoration = TextDecoration.LineThrough) else Type.meta,
                        ) { scope.launch { runCatching { Api.addRule(t, "topic", next) }; load() } }
                    }
                }
            }
        }

        item {
            Section("Keywords, people, places, outlets", "Follow boosts it, mute hides it completely, always show pins it to the top.") {
                SearchField(kw, { kw = it }, "e.g. Formula 1, ISRO, celebrity")
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("keyword" to "Keyword", "place" to "Place", "source" to "Outlet").forEach { (k, l) ->
                        Pill(l, selected = kwKind == k, style = Type.chip, padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { kwKind = k }
                    }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("follow" to "Follow", "mute" to "Mute", "always" to "Always show").forEach { (k, l) ->
                        Pill(l, selected = kwMode == k, style = Type.chip, padding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) { kwMode = k }
                    }
                    Spacer(Modifier.weight(1f))
                    Pill("Add", selected = true) {
                        val label = kw.trim()
                        if (label.isNotEmpty()) scope.launch { runCatching { Api.addRule(label, kwKind, kwMode) }; kw = ""; load() }
                    }
                }
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.rules.filter { it.kind != "topic" }.forEach { r ->
                        val prefix = when (r.mode) { "mute" -> "Muted"; "always" -> "Always"; else -> "Following" }
                        Pill("$prefix: ${r.label}  ×", color = if (r.mode == "mute") c.less else null, style = Type.chip,
                            padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            scope.launch { runCatching { Api.delete("/api/rules/${r.id}") }; load() }
                        }
                    }
                }
            }
        }

        item {
            Section("Discovery", "How much of the feed explores outside your known interests.") {
                LabeledSlider("Exploration", d.prefs.discovery * 2, "${(d.prefs.discovery * 100).toInt()}%") { v ->
                    putPrefs(buildJsonObject { put("discovery", v / 2) })
                }
            }
        }

        item {
            val attention = d.sources.count { it.dead || it.error != null }
            Section("Sources (${d.sources.size})", if (attention > 0) "$attention feed(s) need attention" else "Trust decides which outlet leads a story.") {
                TextLink(if (showSources) "Hide sources" else "Show sources") { showSources = !showSources }
                if (showSources) {
                    d.sources.forEach { s ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, style = Type.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (s.dead || s.error != null) Text(s.error ?: "quiet 7 days", style = Type.chip, color = c.less, modifier = Modifier.padding(horizontal = 6.dp))
                                Text("${s.articles3d}", style = Type.chip, color = c.text2, modifier = Modifier.padding(horizontal = 6.dp))
                                Switch(
                                    checked = s.enabled,
                                    onCheckedChange = { on -> scope.launch { runCatching { Api.patch<OkResponse>("/api/sources/${s.id}", buildJsonObject { put("enabled", on) }) }; load() } },
                                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.Black),
                                )
                            }
                            if (s.kind in listOf("outlet", "custom", "city", "google"))
                                LabeledSlider("Trust", s.trust) { v -> scope.launch { runCatching { Api.patch<OkResponse>("/api/sources/${s.id}", buildJsonObject { put("trust", v) }) } } }
                        }
                    }
                    SearchField(feedUrl, { feedUrl = it }, "Add any RSS feed URL", Modifier.padding(top = 10.dp))
                    Pill("Add feed", modifier = Modifier.padding(top = 8.dp)) {
                        scope.launch {
                            runCatching { Api.post<OkResponse>("/api/sources", buildJsonObject { put("url", feedUrl.trim()) }) }
                                .onSuccess { feedUrl = ""; Toaster.show("Feed added. It's polled on the next update."); load() }
                                .onFailure { Toaster.show(it.message ?: "Couldn't add") }
                        }
                    }
                }
            }
        }

        item {
            Section("Morning brief", "One optional notification a day with how many stories are waiting.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (brief) "On at ${hourLabel(briefHour)}" else "Off", style = Type.meta, color = c.text, modifier = Modifier.weight(1f))
                    Switch(
                        checked = brief,
                        onCheckedChange = { on ->
                            if (on) {
                                val needs = Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                if (needs) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else { brief = true; Store.briefEnabled = true; BriefWorker.schedule(ctx) }
                            } else { brief = false; Store.briefEnabled = false; BriefWorker.cancel(ctx) }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = Color.Black),
                    )
                }
                if (brief) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(6, 7, 8, 9).forEach { h ->
                        Pill(hourLabel(h), selected = briefHour == h, style = Type.chip, padding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                            briefHour = h; Store.briefHour = h; BriefWorker.schedule(ctx)
                        }
                    }
                }
            }
        }

        item {
            Section("Appearance") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "dark" to "Dark", "light" to "Light").forEach { (k, l) ->
                        Pill(l, selected = Store.theme == k) { Store.changeTheme(k) }
                    }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.92f to "Small text", 1f to "Medium", 1.1f to "Large").forEach { (k, l) ->
                        Pill(l, selected = Store.textScale == k) { Store.changeTextScale(k) }
                    }
                }
            }
        }

        item {
            Section("App") {
                Text("Pulse ${BuildConfig.VERSION_NAME}", style = Type.meta, color = c.text)
                Text(
                    d.lastIngest?.let { li -> li.at?.let { "News last fetched ${ago(it)} · ${li.new} new articles" } } ?: "Waiting for the first news fetch.",
                    style = Type.chip, color = c.text2, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                )
                update?.let { rel ->
                    PrimaryButton("Install update ${rel.versionName}", Modifier.fillMaxWidth().padding(bottom = 10.dp)) { Updater.install(ctx, rel) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Updater.enabled) Pill(if (checking) "Checking…" else "Check for updates") {
                        checking = true
                        scope.launch {
                            val r = Updater.check(force = true)
                            checking = false
                            update = r
                            if (r == null) Toaster.show("You're on the latest version")
                        }
                    }
                    Pill("Redo setup", onClick = onOnboarding)
                    Pill("Log out", onClick = onLogout)
                }
                Text("Server: ${Store.server}", style = Type.chip, color = c.text2, modifier = Modifier.padding(top = 12.dp))
            }
        }
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
