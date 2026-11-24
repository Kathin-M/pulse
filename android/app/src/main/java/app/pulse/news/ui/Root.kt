package app.pulse.news.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.pulse.news.BuildConfig
import app.pulse.news.R
import app.pulse.news.data.Api
import app.pulse.news.data.Card
import app.pulse.news.data.Controls
import app.pulse.news.data.OkResponse
import app.pulse.news.data.Store
import app.pulse.news.update.Updater
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Composable
fun PulseRoot() {
    val c = LocalColors.current
    var authed by remember { mutableStateOf(Store.loggedIn && Store.server.isNotBlank()) }
    DisposableEffect(Unit) {
        Api.onAuthLost = { Store.logout(); authed = false }
        onDispose { Api.onAuthLost = null }
    }
    Box(Modifier.fillMaxSize().background(c.bg)) {
        if (!authed) LoginScreen { authed = true } else MainScreen(onLogout = { Store.logout(); authed = false })
        ToastHost()
    }
}

@Composable
fun LoginScreen(onDone: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(Store.server.ifBlank { BuildConfig.DEFAULT_SERVER }) }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current

    fun submit() {
        var url = server.trim().trimEnd('/')
        if (url.isEmpty()) { error = "Enter your Pulse server address"; return }
        if (!url.startsWith("http")) url = "https://$url"
        busy = true; error = null
        scope.launch {
            runCatching { Api.login(url, password) }
                .onSuccess { r ->
                    Store.server = url
                    Store.token = r.token.orEmpty()
                    Store.loggedIn = true
                    onDone()
                }
                .onFailure { error = if ((it as? app.pulse.news.data.ApiException)?.code == 401) "Wrong password" else (it.message ?: "Can't reach the server") }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.ic_pulse_line), null, Modifier.size(84.dp))
            Text("Pulse", style = Type.title.copy(fontSize = Type.title.fontSize * 1.25f), color = c.text)
        }
        Text("Your private news. One story at a time.", style = Type.summary, color = c.text2)
        Spacer(Modifier.height(8.dp))
        Text("Server", style = Type.chip, color = c.text2)
        SearchField(
            server, { server = it }, "your-pulse.vercel.app",
            keyboard = androidx.compose.ui.text.input.KeyboardType.Uri,
            imeAction = androidx.compose.ui.text.input.ImeAction.Next,
            onAction = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Down) },
        )
        Text("Password", style = Type.chip, color = c.text2)
        SearchField(
            password, { password = it }, "Password", password = true,
            imeAction = androidx.compose.ui.text.input.ImeAction.Go,
            onAction = { if (password.isNotEmpty() && !busy) submit() },
        )
        error?.let { Text(it, style = Type.meta, color = c.less) }
        PrimaryButton(if (busy) "Checking…" else "Open Pulse", Modifier.fillMaxWidth(), enabled = !busy && password.isNotEmpty()) { submit() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<Controls?>(null) }
    var step by remember { mutableStateOf(0) }
    var city by remember { mutableStateOf("Bengaluru") }
    val topics = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { Api.get<Controls>("/api/controls") }.onSuccess { d ->
            data = d; city = d.prefs.city
            topics.clear(); topics.addAll(d.rules.filter { it.kind == "topic" && it.mode == "follow" }.map { it.label })
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(c.divider)) {
            Box(Modifier.fillMaxWidth(if (step == 0) 0.5f else 1f).height(3.dp).background(c.accent))
        }
        val d = data
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp)) {
            if (d == null) return@Column
            Text(if (step == 0) "Where are you?" else "What do you follow?", style = Type.headlineXl, color = c.text)
            Text(
                if (step == 0) "Local news comes from your city and state. You can change this any time."
                else "Pick at least five. Pulse keeps learning from every swipe after this.",
                style = Type.summary, color = c.text2, modifier = Modifier.padding(top = 8.dp, bottom = 22.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step == 0) d.cities.forEach { ct -> Pill(ct.name, selected = city == ct.name) { city = ct.name } }
                else d.topics.forEach { t -> Pill(t, selected = t in topics) { if (t in topics) topics.remove(t) else topics.add(t) } }
            }
        }
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step == 1) Pill("Back") { step = 0 }
            val ready = step == 0 || topics.size >= 5
            PrimaryButton(
                when { step == 0 -> "Next"; topics.size < 5 -> "Pick ${5 - topics.size} more"; busy -> "Setting up…"; else -> "Start reading" },
                Modifier.weight(1f), enabled = ready && !busy,
            ) {
                if (step == 0) step = 1 else {
                    busy = true
                    scope.launch {
                        runCatching {
                            Api.post<OkResponse>("/api/onboard", buildJsonObject {
                                put("city", city)
                                putJsonArray("topics") { topics.forEach { add(it) } }
                            })
                        }.onSuccess { onDone() }.onFailure { Toaster.show(it.message ?: "Couldn't save"); busy = false }
                    }
                }
            }
        }
    }
}

private enum class Tab(val label: String) { Feed("Feed"), Seen("Seen"), Liked("Liked"), Controls("Controls") }

@Composable
fun MainScreen(onLogout: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(Tab.Feed) }
    var detail by remember { mutableStateOf<Int?>(null) }
    var reader by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var thread by remember { mutableStateOf<Card?>(null) }
    var insights by remember { mutableStateOf(false) }
    var onboarding by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<Updater.Release?>(null) }

    LaunchedEffect(Unit) { update = Updater.check(force = false) }

    // Back closes the top-most layer first.
    BackHandler(enabled = reader != null) { reader = null }
    BackHandler(enabled = reader == null && detail != null) { detail = null }
    BackHandler(enabled = reader == null && detail == null && thread != null) { thread = null }
    BackHandler(enabled = insights && detail == null) { insights = false }
    BackHandler(enabled = !insights && detail == null && thread == null && tab != Tab.Feed) { tab = Tab.Feed }

    if (onboarding) { OnboardingScreen { onboarding = false; tab = Tab.Feed }; return }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    Tab.Feed -> FeedScreen(
                        onOpen = { detail = it },
                        onOpenThread = { thread = it },
                        onNeedsOnboarding = { onboarding = true },
                        onDragging = { dragging = it },
                        onGoSeen = { tab = Tab.Seen },
                        onGoControls = { tab = Tab.Controls },
                    )
                    Tab.Seen -> SeenScreen(onOpen = { detail = it })
                    Tab.Liked -> LikedScreen(onOpen = { detail = it })
                    Tab.Controls -> ControlsScreen(onInsights = { insights = true }, onOnboarding = { onboarding = true }, onLogout = onLogout)
                }
            }
            // bottom navigation, within thumb reach; fades while you swipe
            Column(Modifier.fillMaxWidth().background(c.bg).alpha(if (dragging) 0f else 1f).navigationBarsPadding()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Tab.entries.forEach { t ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            TextLink(t.label, color = if (tab == t) c.text else c.text2) { tab = t }
                            Box(Modifier.width(24.dp).height(2.dp).background(if (tab == t) c.accent else androidx.compose.ui.graphics.Color.Transparent))
                        }
                    }
                }
            }
        }

        Overlay(visible = insights, fromRight = true) { Box(Modifier.statusBarsPadding()) { InsightsScreen(onBack = { insights = false }) } }
        Overlay(visible = thread != null, fromRight = false) {
            thread?.let { t -> Box(Modifier.statusBarsPadding()) { ThreadScreen(t, onClose = { thread = null }, onOpen = { detail = it }) } }
        }
        Overlay(visible = detail != null, fromRight = true) {
            detail?.let { id -> Box(Modifier.statusBarsPadding()) { StoryDetailScreen(id, onClose = { detail = null }, onRead = { reader = it to id }) } }
        }
        Overlay(visible = reader != null, fromRight = false) {
            reader?.let { (aid, sid) -> Box(Modifier.statusBarsPadding()) { ReaderScreen(aid, sid, onClose = { reader = null }) } }
        }
    }

    update?.let { rel ->
        AlertDialog(
            onDismissRequest = { update = null },
            containerColor = c.raised,
            title = { Text("Update available", style = Type.headline, color = c.text) },
            text = { Text("Pulse ${rel.versionName} is ready. It downloads in the background and Android asks you to install it.", style = Type.summary, color = c.text2) },
            confirmButton = { TextButton(onClick = { Updater.install(ctx, rel); update = null }) { Text("Update", color = c.accent) } },
            dismissButton = { TextButton(onClick = { Store.skippedVersion = rel.versionCode; update = null }) { Text("Later", color = c.text2) } },
        )
    }
}

@Composable
private fun Overlay(visible: Boolean, fromRight: Boolean, content: @Composable () -> Unit) {
    val c = LocalColors.current
    AnimatedVisibility(
        visible = visible,
        enter = if (fromRight) slideInHorizontally(tween(260)) { it } else slideInVertically(tween(280)) { it },
        exit = if (fromRight) slideOutHorizontally(tween(220)) { it } else slideOutVertically(tween(240)) { it },
    ) {
        Box(Modifier.fillMaxSize().background(c.bg)) { content() }
    }
}
