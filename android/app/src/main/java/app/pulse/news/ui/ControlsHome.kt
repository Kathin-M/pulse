package app.pulse.news.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.pulse.news.BuildConfig
import app.pulse.news.data.Api
import app.pulse.news.data.Controls
import app.pulse.news.data.OkResponse
import app.pulse.news.data.Store
import app.pulse.news.update.Updater
import app.pulse.news.work.BriefWorker
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/* Feed mix presets: how much local vs national vs world news. */
private val MIXES = listOf(
    Triple("local", "Local first", mapOf("city" to 0.95f, "state" to 0.75f, "india" to 0.5f, "world" to 0.25f)),
    Triple("balanced", "Balanced", mapOf("city" to 0.7f, "state" to 0.5f, "india" to 0.6f, "world" to 0.4f)),
    Triple("world", "India & world", mapOf("city" to 0.35f, "state" to 0.3f, "india" to 0.75f, "world" to 0.8f)),
)
private val SURPRISE = listOf(0f to "Off", 0.1f to "A little", 0.25f to "Lots")
private val TRUST = listOf("Low" to 0.3f, "Normal" to 0.6f, "High" to 0.9f)

private enum class Page { Home, Topics, Keywords, Location, Sources, Look, Brief, Account }

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun NavRow(title: String, detail: String, last: Boolean = false, onClick: () -> Unit) {
    val c = LocalColors.current
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.summary, color = c.text, modifier = Modifier.weight(1f))
        Text(detail, style = Type.meta, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false))
        Text("  ›", style = Type.summary, color = c.text2)
    }
    if (!last) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(c.divider.copy(alpha = 0.6f)))
}

@Composable
private fun SubPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalColors.current
    BackHandler { onBack() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        item {
            TextLink("‹ Controls", modifier = Modifier.padding(top = 8.dp), onClick = onBack)
            Text(title, style = Type.title, color = c.text, modifier = Modifier.padding(bottom = 14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
private fun ToggleRow(label: String, hint: String?, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.summary, color = c.text)
            if (hint != null) Text(hint, style = Type.chip, color = c.text2, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.more, checkedThumbColor = Color.White))
    }
}

/** Controls: two quick choices up top, then plain-language rows that open focused pages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlsScreen(onInsights: () -> Unit, onOnboarding: () -> Unit, onLogout: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Controls?>(null) }
    var page by remember { mutableStateOf(Page.Home) }

    fun load() = scope.launch { runCatching { Api.get<Controls>("/api/controls") }.onSuccess { data = it }.onFailure { Toaster.show(it.message ?: "Offline") } }
    LaunchedEffect(Unit) { load() }
    fun putPrefs(body: JsonObject) = scope.launch {
        runCatching { Api.put<app.pulse.news.data.Prefs>("/api/prefs", body) }
            .onSuccess { p -> data = data?.copy(prefs = p) }
            .onFailure { Toaster.show(it.message ?: "Couldn't save") }
    }

    val d = data ?: run { Box(Modifier.fillMaxSize()); return }
    val back = { page = Page.Home }

    when (page) {
        Page.Topics -> SubPage("Topics", back) {
            Text("Tap once to follow (more of it), twice to mute (never shown), three times to reset.", style = Type.meta, color = c.text2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.topics.forEach { t ->
                    val mode = d.rules.firstOrNull { it.kind == "topic" && it.label == t }?.mode ?: "neutral"
                    val next = when (mode) { "neutral" -> "follow"; "follow" -> "mute"; else -> "neutral" }
                    Pill(
                        when (mode) { "follow" -> "✓ $t"; "mute" -> "✕ $t"; else -> t },
                        selected = mode == "follow" || mode == "always",
                        color = if (mode == "mute") c.less else null,
                        style = if (mode == "mute") Type.meta.copy(textDecoration = TextDecoration.LineThrough) else Type.meta,
                    ) { scope.launch { runCatching { Api.addRule(t, "topic", next) }; load() } }
                }
            }
        }

        Page.Keywords -> SubPage("Keywords & people", back) {
            var kw by remember { mutableStateOf("") }
            var mode by remember { mutableStateOf(0) }
            var kind by remember { mutableStateOf(0) }
            val modes = listOf("follow", "always", "mute")
            val kinds = listOf("keyword", "place", "source")
            Text("Follow a name or word to see more of it, mute it to never see it, or pin it to always show at the top.", style = Type.meta, color = c.text2)
            SearchField(kw, { kw = it }, "e.g. ISRO, Formula 1, a person")
            GlassSegmented(listOf("Follow", "Always show", "Mute"), mode, { mode = it }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSegmented(listOf("Word", "Place", "Outlet"), kind, { kind = it }, Modifier.weight(1f))
                Pill("Add", selected = true) {
                    val label = kw.trim()
                    if (label.isNotEmpty()) scope.launch { runCatching { Api.addRule(label, kinds[kind], modes[mode]) }; kw = ""; load() }
                }
            }
            val rules = d.rules.filter { it.kind != "topic" }
            listOf("follow" to "Following", "always" to "Always shown", "mute" to "Muted").forEach { (m, title) ->
                val list = rules.filter { it.mode == m }
                if (list.isNotEmpty()) {
                    Text(title, style = Type.meta, color = c.text2)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { r ->
                            Pill("${r.label}  ×", color = if (m == "mute") c.less else null) {
                                scope.launch { runCatching { Api.delete("/api/rules/${r.id}") }; load() }
                            }
                        }
                    }
                }
            }
            if (rules.isEmpty()) Text("Nothing yet. Tip: long-press a chip on any card to follow or mute it.", style = Type.meta, color = c.text2)
        }

        Page.Location -> SubPage("Location", back) {
            Text("Local news comes from your city and state.", style = Type.meta, color = c.text2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                d.cities.forEach { city -> Pill(city.name, selected = d.prefs.city == city.name) { putPrefs(buildJsonObject { put("city", city.name) }) } }
            }
        }

        Page.Sources -> SubPage("News sources", back) {
            var feedUrl by remember { mutableStateOf("") }
            Text("Switch outlets off, or set how much you trust them. Trusted outlets lead the stories they cover.", style = Type.meta, color = c.text2)
            d.sources.forEach { s ->
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = Type.meta, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                s.error ?: if (s.dead) "Quiet for 7 days" else "${s.articles3d} stories in 3 days",
                                style = Type.chip, color = if (s.error != null || s.dead) c.less else c.text2,
                            )
                        }
                        Switch(
                            s.enabled,
                            { on -> scope.launch { runCatching { Api.patch<OkResponse>("/api/sources/${s.id}", buildJsonObject { put("enabled", on) }) }; load() } },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.more, checkedThumbColor = Color.White),
                        )
                    }
                    if (s.enabled) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val nearest = TRUST.minBy { kotlin.math.abs(it.second - s.trust) }.first
                        TRUST.forEach { (l, v) ->
                            Pill("$l trust", selected = nearest == l, style = Type.chip, padding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) {
                                scope.launch { runCatching { Api.patch<OkResponse>("/api/sources/${s.id}", buildJsonObject { put("trust", v) }) }; load() }
                            }
                        }
                    }
                }
            }
            SearchField(feedUrl, { feedUrl = it }, "Add any RSS feed URL")
            Pill("Add feed") {
                scope.launch {
                    runCatching { Api.post<OkResponse>("/api/sources", buildJsonObject { put("url", feedUrl.trim()) }) }
                        .onSuccess { feedUrl = ""; Toaster.show("Feed added. It's fetched on the next update."); load() }
                        .onFailure { Toaster.show(it.message ?: "Couldn't add") }
                }
            }
        }

        Page.Look -> SubPage("Look & feel", back) {
            GlassCard {
                Text("Theme", style = Type.meta, color = c.text)
                val themes = listOf("system", "dark", "light")
                GlassSegmented(listOf("System", "Dark", "Light"), themes.indexOf(Store.theme).coerceAtLeast(0), { Store.changeTheme(themes[it]) }, Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Text size", style = Type.meta, color = c.text, modifier = Modifier.padding(top = 14.dp))
                val sizes = listOf(0.92f, 1f, 1.1f)
                GlassSegmented(listOf("Small", "Medium", "Large"), sizes.indexOf(Store.textScale).let { if (it < 0) 1 else it }, { Store.changeTextScale(sizes[it]) }, Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            GlassCard {
                Text("Glass effects", style = Type.meta, color = c.text)
                val modes = listOf("full", "light", "off")
                GlassSegmented(listOf("Full", "Light", "Off"), modes.indexOf(Store.glass).coerceAtLeast(0), { Store.changeGlass(modes[it]) }, Modifier.fillMaxWidth().padding(top = 8.dp))
                Text(
                    when (Store.glass) {
                        "full" -> "Shadows, highlights and springy lens animations. Looks best, uses the most battery."
                        "light" -> "Glass without shadows or animations. Lighter on your phone."
                        else -> "Flat, solid surfaces. Lightest on your phone."
                    },
                    style = Type.chip, color = c.text2, modifier = Modifier.padding(top = 8.dp),
                )
            }
            GlassCard {
                ToggleRow("Swipe hints", "Faint markers on the card showing what each swipe does", Store.hints) { Store.changeHints(it) }
                Spacer(Modifier.height(10.dp))
                ToggleRow("Vibration", "A light tap on every swipe", Store.haptics) { Store.changeHaptics(it) }
            }
        }

        Page.Brief -> SubPage("Morning brief", back) {
            var brief by remember { mutableStateOf(Store.briefEnabled) }
            var hour by remember { mutableStateOf(Store.briefHour) }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) { brief = true; Store.briefEnabled = true; BriefWorker.schedule(ctx) }
                else Toaster.show("Notifications are off for Pulse in system settings")
            }
            GlassCard {
                ToggleRow("Daily notification", "One note a day with how many stories are waiting. Nothing else, ever.", brief) { on ->
                    if (on) {
                        val needs = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (needs) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else { brief = true; Store.briefEnabled = true; BriefWorker.schedule(ctx) }
                    } else { brief = false; Store.briefEnabled = false; BriefWorker.cancel(ctx) }
                }
                if (brief) {
                    Text("Time", style = Type.meta, color = c.text, modifier = Modifier.padding(top = 14.dp))
                    val hours = listOf(6, 7, 8, 9)
                    GlassSegmented(hours.map { hourLabel(it) }, hours.indexOf(hour).coerceAtLeast(0), {
                        hour = hours[it]; Store.briefHour = hours[it]; BriefWorker.schedule(ctx)
                    }, Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
        }

        Page.Account -> SubPage("Account", back) {
            var cur by remember { mutableStateOf("") }
            var next by remember { mutableStateOf("") }
            var update by remember { mutableStateOf<Updater.Release?>(null) }
            var checking by remember { mutableStateOf(false) }
            GlassCard {
                Text("Signed in as", style = Type.meta, color = c.text2)
                Text(d.username ?: Store.username, style = Type.summary.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            }
            GlassCard {
                Text("Change password", style = Type.meta, color = c.text)
                SearchField(cur, { cur = it }, "Current password", Modifier.padding(top = 8.dp), password = true)
                SearchField(next, { next = it }, "New password (6+ characters)", Modifier.padding(top = 8.dp), password = true)
                Pill("Save password", selected = true, modifier = Modifier.padding(top = 10.dp)) {
                    scope.launch {
                        runCatching { Api.post<OkResponse>("/api/account/password", buildJsonObject { put("current", cur); put("new", next) }) }
                            .onSuccess { cur = ""; next = ""; Toaster.show("Password changed") }
                            .onFailure { Toaster.show(it.message ?: "Couldn't change password") }
                    }
                }
            }
            GlassCard {
                Text("Pulse ${BuildConfig.VERSION_NAME}", style = Type.meta, color = c.text)
                update?.let { rel -> PrimaryButton("Install update ${rel.versionName}", Modifier.fillMaxWidth().padding(top = 10.dp)) { Updater.install(ctx, rel) } }
                FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (Updater.enabled) Pill(if (checking) "Checking…" else "Check for updates") {
                        checking = true
                        scope.launch {
                            val r = Updater.check(force = true)
                            checking = false; update = r
                            if (r == null) Toaster.show("You're on the latest version")
                        }
                    }
                    Pill("Redo setup", onClick = onOnboarding)
                    Pill("Log out", onClick = onLogout)
                }
            }
        }

        Page.Home -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Controls", style = Type.title, color = c.text, modifier = Modifier.padding(top = 12.dp)) }
            item {
                GlassCard(Modifier.clickable { onInsights() }) {
                    Row {
                        Text("This week", style = Type.meta, color = c.text2, modifier = Modifier.weight(1f))
                        Text("What Pulse learned ›", style = Type.meta, color = c.accent)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        listOf(d.stats.readWeek to "read", d.stats.likedWeek to "liked", d.stats.saved to "saved", d.stats.interests to "interests").forEach { (n, l) ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("$n", fontFamily = Newsreader, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, color = c.text)
                                Text(l, style = Type.chip, color = c.text2, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            item {
                GlassCard {
                    Text("Feed mix", style = Type.meta, color = c.text)
                    val current = MIXES.indexOfFirst { (_, _, t) -> t.all { (k, v) -> kotlin.math.abs((d.prefs.tiers[k] ?: 0f) - v) < 0.03f } }
                    GlassSegmented(MIXES.map { it.second }, current, { i ->
                        putPrefs(buildJsonObject { putJsonObject("tiers") { MIXES[i].third.forEach { (k, v) -> put(k, v) } } })
                        Toaster.show("Feed mix: ${MIXES[i].second}")
                    }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Surprise me", style = Type.meta, color = c.text, modifier = Modifier.padding(top = 14.dp))
                    val s = SURPRISE.indices.minBy { kotlin.math.abs(SURPRISE[it].first - d.prefs.discovery) }
                    GlassSegmented(SURPRISE.map { it.second }, s, { i -> putPrefs(buildJsonObject { put("discovery", SURPRISE[i].first) }) }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("How often Pulse shows stories outside your usual interests.", style = Type.chip, color = c.text2, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item {
                val followed = d.rules.count { it.kind == "topic" && it.mode == "follow" }
                val muted = d.rules.count { it.kind == "topic" && it.mode == "mute" }
                val others = d.rules.count { it.kind != "topic" }
                val off = d.sources.count { !it.enabled }
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column {
                        NavRow("Topics", "$followed followed" + if (muted > 0) " · $muted muted" else "") { page = Page.Topics }
                        NavRow("Keywords & people", if (others > 0) "$others rules" else "Add") { page = Page.Keywords }
                        NavRow("Location", "${d.prefs.city}, ${d.prefs.state}") { page = Page.Location }
                        NavRow("News sources", "${d.sources.size - off} on" + if (off > 0) " · $off off" else "", last = true) { page = Page.Sources }
                    }
                }
            }
            item {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column {
                        NavRow("Look & feel", "${Store.theme} · glass ${Store.glass}") { page = Page.Look }
                        NavRow("Morning brief", if (Store.briefEnabled) "On at ${hourLabel(Store.briefHour)}" else "Off") { page = Page.Brief }
                        NavRow("Account", d.username ?: Store.username, last = true) { page = Page.Account }
                    }
                }
            }
            if (Updater.enabled) item {
                GlassCard(Modifier.clickable { shareText(ctx, "Pulse: news, one story at a time", "https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest") }) {
                    Text("Share Pulse", style = Type.summary, color = c.text)
                    Text("Send friends the download link. They create their own account.", style = Type.chip, color = c.text2, modifier = Modifier.padding(top = 2.dp))
                }
            }
            item {
                Text(
                    d.lastIngest?.let { li -> li.at?.let { "News updated ${ago(it)}" } } ?: "Waiting for the first news fetch.",
                    style = Type.chip, color = c.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
