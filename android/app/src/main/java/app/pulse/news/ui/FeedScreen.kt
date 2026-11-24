package app.pulse.news.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pulse.news.data.Api
import app.pulse.news.data.Card
import app.pulse.news.data.FeedResponse
import app.pulse.news.data.Ingest
import app.pulse.news.data.SessionSummary
import app.pulse.news.data.Store
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private data class UndoEntry(val card: Card, val dir: String, val interactionId: Long)

/**
 * A stack of cards: the top one draggable, the next one peeking behind at 96% so there's always
 * a visible "more". Shared by the main feed and the thread viewer.
 */
@Composable
fun CardStack(
    cards: List<Card>,
    controller: SwipeController,
    enterFrom: String?,
    onSwiped: (Card, String, Long) -> Unit,
    onTap: (Card, Long) -> Unit,
    onDoubleTap: (Card) -> Unit,
    onDragging: (Boolean) -> Unit = {},
    onChip: (ChipRef) -> Unit = {},
    onWhy: () -> Unit = {},
    onAction: ((Card, String) -> Unit)? = null,
    empty: @Composable () -> Unit,
) {
    val top = cards.firstOrNull()
    if (top == null) { empty(); return }
    val next = cards.getOrNull(1)
    Box(Modifier.fillMaxSize()) {
        if (next != null) {
            key("peek-${next.id}") {
                Box(Modifier.fillMaxSize().graphicsLayer { scaleX = 0.96f; scaleY = 0.96f; translationY = 28f }.alpha(0.7f)) {
                    CardView(next)
                }
            }
        }
        key("top-${top.id}") {
            // the card that was peeking scales up from 96% to fill the screen
            val grow = remember { androidx.compose.animation.core.Animatable(if (enterFrom == null) 0.96f else 1f) }
            LaunchedEffect(Unit) { grow.animateTo(1f, tween(200)) }
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = grow.value; scaleY = grow.value }) {
                SwipeCard(
                    card = top,
                    controller = controller,
                    enterFrom = enterFrom,
                    onSwiped = { dir, dwell -> onSwiped(top, dir, dwell) },
                    onTap = { dwell -> onTap(top, dwell) },
                    onDoubleTap = { onDoubleTap(top) },
                    onDragging = onDragging,
                    onChip = onChip,
                    onWhy = onWhy,
                    onAction = onAction?.let { f -> { a: String -> f(top, a) } },
                )
            }
        }
    }
}

@Composable
private fun CaughtUp(since: OffsetDateTime, empty: Boolean, mode: String, ingest: Ingest?, offline: Boolean, onSeen: () -> Unit, onRefresh: () -> Unit) {
    val c = LocalColors.current
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(since) {
        note = runCatching { Api.get<SessionSummary>("/api/session-summary?since=${Api.q(since.toString())}").note }.getOrNull()
    }
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.width(56.dp).height(2.dp).background(c.accent))
        Text(if (empty) "Nothing here yet" else "You're all caught up", style = Type.headlineXl, color = c.text)
        Text(
            when {
                offline -> "You're offline. Pulse will refresh when you're back online."
                !empty -> "You've seen everything important since ${since.toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a"))}."
                mode == "following" -> "Follow topics, keywords or stories and they'll show up here."
                ingest?.at != null -> "No new stories since the last update. Fresh news is fetched every hour."
                else -> "Pulse is fetching your first stories. The first batch lands within the hour."
            },
            style = Type.summary, color = c.text2,
        )
        note?.let { Text(it, style = Type.meta, color = c.text) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Open Seen", onClick = onSeen)
            Pill("Check again", onClick = onRefresh)
        }
        ingest?.at?.let { Text("Last update ${ago(it)}", style = Type.chip, color = c.text2) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipSheet(chip: ChipRef, onDismiss: () -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    fun act(mode: String) = scope.launch {
        runCatching { Api.addRule(chip.label, chip.kind, mode) }
            .onSuccess { Toaster.show(when (mode) { "mute" -> "Muted ${chip.label}"; "always" -> "Always showing ${chip.label}"; else -> "Following ${chip.label}" }) }
            .onFailure { Toaster.show(it.message ?: "Couldn't save") }
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.raised) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chip.label, style = Type.meta, color = c.text2, modifier = Modifier.padding(bottom = 8.dp))
            TextLink("Follow ${chip.label}", color = c.text) { act("follow") }
            TextLink("Always show ${chip.label}", color = c.text) { act("always") }
            TextLink("Mute ${chip.label}", color = c.less) { act("mute") }
        }
    }
}

@Composable
fun FeedScreen(
    onOpen: (Int) -> Unit,
    onOpenThread: (Card) -> Unit,
    onNeedsOnboarding: () -> Unit,
    onDragging: (Boolean) -> Unit,
    onGoSeen: () -> Unit,
    onGoControls: () -> Unit,
) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("foryou") }
    val queue = remember { mutableStateListOf<Card>() }
    var loaded by remember { mutableStateOf(false) }
    var offline by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableIntStateOf(0) }
    val undo = remember { mutableStateListOf<UndoEntry>() }
    var enterFrom by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var chip by remember { mutableStateOf<ChipRef?>(null) }
    var ingest by remember { mutableStateOf<Ingest?>(null) }
    val sessionStart = remember { OffsetDateTime.now() }
    val swiped = remember { mutableSetOf<Int>() }
    var sinceRefresh by remember { mutableIntStateOf(0) }
    val controller = remember { SwipeController() }

    fun load(keep: List<Card> = emptyList()) = scope.launch {
        try {
            val (r, text) = Api.feed(mode, swiped + keep.map { it.id })
            if (mode == "foryou") Store.cachedFeed = text
            offline = false; error = null
            ingest = r.lastIngest
            if (!r.onboarded) onNeedsOnboarding()
            val fresh = r.cards.filter { it.id !in swiped && keep.none { k -> k.id == it.id } }
            queue.clear(); queue.addAll(keep + fresh)
        } catch (e: Exception) {
            if (!loaded && mode == "foryou") {
                Store.cachedFeed?.let { cached ->
                    runCatching { Api.json.decodeFromString<FeedResponse>(cached) }.getOrNull()?.let { r ->
                        queue.clear(); queue.addAll(r.cards.filter { it.id !in swiped }); ingest = r.lastIngest
                    }
                }
            }
            offline = true
            if (queue.isEmpty()) error = e.message
        } finally {
            loaded = true
        }
    }

    LaunchedEffect(mode) { loaded = false; done = 0; queue.clear(); load() }

    fun onSwiped(card: Card, dir: String, dwell: Long) {
        enterFrom = null
        if (queue.firstOrNull()?.id == card.id) queue.removeAt(0)
        done++
        if (card.type == "thread" && dir == "right") {
            onOpenThread(card)
            scope.launch { runCatching { Api.interact(card.id, "right", dwell, storyIds = card.storyIds) } }
            return
        }
        swiped += card.id
        scope.launch {
            runCatching { Api.interact(card.id, dir, dwell, card.articleId, if (card.type == "thread") card.storyIds else null) }
                .onSuccess { r ->
                    undo.add(UndoEntry(card, dir, r.interactionId))
                    while (undo.size > 5) undo.removeAt(0)
                }
        }
        // re-rank every 10 swipes so the feed adapts within the session
        if (++sinceRefresh >= 10) { sinceRefresh = 0; load(queue.take(2)) }
    }

    fun doUndo() {
        val last = undo.removeLastOrNull() ?: return
        scope.launch { runCatching { Api.undo(last.interactionId) } }
        swiped -= last.card.id
        enterFrom = last.dir
        queue.add(0, last.card)
        done = (done - 1).coerceAtLeast(0)
    }

    val total = done + queue.size
    val progress by animateFloatAsState(if (total > 0) done / total.toFloat() else 0f, tween(250), label = "progress")

    val modes = listOf("foryou" to "For you", "local" to "Local", "following" to "Following")
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        // top bar: glass segmented control + glass undo, with a thin progress line and counter
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).alpha(if (dragging) 0f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassSegmented(
                    options = modes.map { it.second },
                    selected = modes.indexOfFirst { it.first == mode },
                    onSelect = { mode = modes[it].first },
                    modifier = Modifier.weight(1f),
                )
                GlassIconButton(PulseIcons.Undo, "Undo last swipe", enabled = undo.isNotEmpty()) { doUndo() }
            }
            Row(Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(c.divider.copy(alpha = 0.7f))) {
                    Box(Modifier.fillMaxWidth(progress).height(3.dp).clip(RoundedCornerShape(50)).background(c.accent))
                }
                if (total > 0) Text("${minOf(done + 1, total)} / $total", style = Type.chip, color = c.text2, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (offline && queue.isNotEmpty()) {
            Text("Offline: showing your last feed", style = Type.chip, color = c.text2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
        }

        Box(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = if (Store.hints) 14.dp else 4.dp)) {
            when {
                !loaded -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(c.surface))
                error != null && queue.isEmpty() && !offline -> Column(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                ) {
                    Text("Can't reach Pulse", style = Type.headline, color = c.text)
                    Text(error ?: "", style = Type.summary, color = c.text2)
                    Pill("Try again") { load() }
                }
                else -> CardStack(
                    cards = queue,
                    controller = controller,
                    enterFrom = enterFrom,
                    onSwiped = ::onSwiped,
                    onTap = { card, _ ->
                        scope.launch { runCatching { Api.interact(card.storyIdForDetail, "tap") } }
                        onOpen(card.storyIdForDetail)
                    },
                    onDoubleTap = { card ->
                        if (card.id > 0) scope.launch {
                            runCatching { Api.interact(card.id, "save") }.onSuccess { Toaster.show("Saved for later") }
                        }
                    },
                    onDragging = { dragging = it; onDragging(it) },
                    onChip = { chip = it },
                    onWhy = onGoControls,
                    onAction = { card, action ->
                        when (action) {
                            "share" -> shareStory(ctx, card, scope)
                            "sources" -> onOpen(card.storyIdForDetail)
                            else -> if (card.id > 0) scope.launch {
                                runCatching { Api.interact(card.id, action) }
                                    .onSuccess { Toaster.show(if (action == "save") "Saved for later" else "Removed from saved") }
                            }
                        }
                    },
                    empty = { CaughtUp(sessionStart, done == 0, mode, ingest, offline && queue.isEmpty(), onGoSeen) { load() } },
                )
            }
            if (Store.hints && queue.isNotEmpty()) SwipeHints(dragging, Modifier.matchParentSize())
        }

    }

    chip?.let { ChipSheet(it) { chip = null } }
}

/** Swipe through the stories of one big event (a thread card). */
@Composable
fun ThreadScreen(thread: Card, onClose: () -> Unit, onOpen: (Int) -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val cards = remember { mutableStateListOf<Card>() }
    var loaded by remember { mutableStateOf(false) }
    val controller = remember { SwipeController() }
    LaunchedEffect(thread.threadId) {
        runCatching { Api.get<app.pulse.news.data.ThreadResponse>("/api/thread/${thread.threadId}") }
            .onSuccess { cards.addAll(it.cards) }
        loaded = true
    }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextLink("‹ Back to feed", onClick = onClose)
            Spacer(Modifier.weight(1f))
            Text("${thread.threadTitle ?: "Thread"}${if (loaded) " · ${cards.size} left" else ""}", style = Type.meta, color = c.text, modifier = Modifier.padding(end = 8.dp))
        }
        Box(Modifier.weight(1f).padding(12.dp).navigationBarsPadding()) {
            if (loaded) CardStack(
                cards = cards,
                controller = controller,
                enterFrom = null,
                onSwiped = { card, dir, dwell ->
                    if (cards.firstOrNull()?.id == card.id) cards.removeAt(0)
                    scope.launch { runCatching { Api.interact(card.id, dir, dwell, card.articleId) } }
                },
                onTap = { card, _ -> scope.launch { runCatching { Api.interact(card.id, "tap") } }; onOpen(card.id) },
                onDoubleTap = { card -> scope.launch { runCatching { Api.interact(card.id, "save") }.onSuccess { Toaster.show("Saved for later") } } },
                empty = {
                    Column(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(28.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    ) {
                        Text("Thread done", style = Type.headline, color = c.text)
                        Pill("Back to feed", onClick = onClose)
                    }
                },
            )
        }
    }
}
