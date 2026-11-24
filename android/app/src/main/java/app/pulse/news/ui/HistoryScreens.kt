package app.pulse.news.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.pulse.news.data.Api
import app.pulse.news.data.Card
import app.pulse.news.data.CardsResponse
import app.pulse.news.data.SeenResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun StoryRow(card: Card, onOpen: (Int) -> Unit, onSwipeRight: ((Card) -> Unit)? = null, badge: String? = null, trailing: @Composable () -> Unit = {}) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    Box {
        if (dx.value > 8f) Text("♥ Like", style = Type.meta, color = c.more, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
        Row(
            Modifier.fillMaxWidth()
                .offset { IntOffset(dx.value.roundToInt(), 0) }
                .background(c.bg)
                .then(if (onSwipeRight != null) Modifier.pointerInput(card.id) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (dx.value > 220f) onSwipeRight(card)
                            scope.launch { dx.animateTo(0f) }
                        },
                        onHorizontalDrag = { ch, amount -> ch.consume(); scope.launch { dx.snapTo((dx.value + amount).coerceIn(0f, 400f)) } },
                    )
                } else Modifier)
                .clickable { onOpen(card.id) }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(card.title, style = Type.summary.copy(fontWeight = FontWeight.Medium), color = c.text)
                val extra = if (card.sourceCount > 1) " +${card.sourceCount - 1}" else ""
                val topic = card.topics.firstOrNull()?.let { " · $it" } ?: ""
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${card.publisher}$extra · ${ago(card.`when` ?: card.firstSeen)}$topic", style = Type.meta, color = c.text2, modifier = Modifier.weight(1f, fill = false))
                    if (badge != null) Pill(badge, style = Type.chip, padding = PaddingValues(horizontal = 7.dp, vertical = 2.dp), modifier = Modifier.padding(start = 6.dp))
                }
            }
            if (card.image != null) NewsImage(card.image, card.lqip, Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)))
            trailing()
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

@Composable
private fun FilterButton(label: String, options: List<String>, allLabel: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Pill("$label ▾", style = Type.chip, padding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(allLabel) }, onClick = { onPick(""); open = false })
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}

private val WHEN_FILTERS = listOf(0 to "All time", 1 to "Today", 2 to "2 days", 7 to "This week", 30 to "This month")
private val REACTION_FILTERS = listOf("" to "All", "liked" to "Liked", "fine" to "Fine", "seen" to "Seen", "less" to "Less")
private val STATUS_LABELS = mapOf("liked" to "Liked", "fine" to "Fine", "seen" to "Seen", "hidden" to "Less")

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Pill(label, selected = active, style = Type.chip, padding = PaddingValues(horizontal = 12.dp, vertical = 7.dp), onClick = onClick)
}

/** Everything you've swiped past: search, then narrow by when, how you reacted, topic, outlet or local. */
@Composable
fun SeenScreen(onOpen: (Int) -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var days by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var local by remember { mutableStateOf(false) }
    var oldest by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<SeenResponse?>(null) }

    LaunchedEffect(q, topic, source, status, days, local, oldest) {
        delay(250)
        data = runCatching {
            Api.get<SeenResponse>(
                "/api/seen?q=${Api.q(q)}&topic=${Api.q(topic)}&source=${Api.q(source)}&status=$status&days=$days&local=$local&sort=${if (oldest) "old" else "new"}"
            )
        }.getOrElse { SeenResponse() }
    }
    val filtered = q.isNotBlank() || days != 0 || status.isNotEmpty() || topic.isNotEmpty() || source.isNotEmpty() || local

    fun toggleLike(card: Card) {
        val liked = card.status == "liked"
        scope.launch {
            runCatching { Api.interact(card.id, if (liked) "unlike" else "like") }
            data = data?.copy(cards = data!!.cards.map { if (it.id == card.id) it.copy(status = if (liked) "seen" else "liked") else it })
            Toaster.show(if (liked) "Removed from Liked" else "Liked")
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            Row(Modifier.padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
                Text("Seen", style = Type.title, color = c.text)
                data?.let { Text("${it.cards.size} stories", style = Type.chip, color = c.text2, modifier = Modifier.padding(start = 10.dp, bottom = 6.dp)) }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                if (filtered) TextLink("Clear filters", color = c.accent) {
                    q = ""; days = 0; status = ""; topic = ""; source = ""; local = false; oldest = false
                }
            }
            SearchField(q, { q = it }, "Search titles, summaries and outlets")
            LazyRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(WHEN_FILTERS) { (d, l) -> FilterChip(l, days == d) { days = d } }
            }
            LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(REACTION_FILTERS) { (k, l) ->
                    val n = data?.counts?.get(k)
                    FilterChip(if (k.isNotEmpty() && status.isEmpty() && n != null) "$l $n" else l, status == k) { status = k }
                }
                item { FilterChip("Local only", local) { local = !local } }
            }
            Row(Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterButton(topic.ifBlank { "All topics" }, data?.topics.orEmpty(), "All topics") { topic = it }
                FilterButton(source.ifBlank { "All outlets" }, data?.sources.orEmpty(), "All outlets") { source = it }
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                FilterChip(if (oldest) "Oldest" else "Newest", false) { oldest = !oldest }
            }
        }
        val cards = data?.cards.orEmpty()
        if (data != null && cards.isEmpty()) item {
            Text(
                if (filtered) "Nothing matches these filters." else "Nothing here yet. Stories you swipe past land here.",
                style = Type.summary, color = c.text2, modifier = Modifier.padding(top = 20.dp),
            )
        }
        var lastBucket = ""
        cards.forEach { card ->
            val b = dayBucket(card.`when`)
            if (b != lastBucket) {
                lastBucket = b
                item(key = "h-$b") { Text(b, style = Type.meta, color = c.text2, modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)) }
            }
            item(key = card.id) {
                StoryRow(card, onOpen, onSwipeRight = ::toggleLike, badge = STATUS_LABELS[card.status]) {
                    Text(
                        if (card.status == "liked") "♥" else "♡", style = Type.summary,
                        color = if (card.status == "liked") c.more else c.text2,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { toggleLike(card) }.padding(8.dp),
                    )
                }
            }
        }
    }
}

/** Your right-swipes and saves: a personal archive. */
@Composable
fun LikedScreen(onOpen: (Int) -> Unit) {
    val c = LocalColors.current
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var cards by remember { mutableStateOf<List<Card>?>(null) }
    LaunchedEffect(q) {
        delay(250)
        cards = runCatching { Api.get<CardsResponse>("/api/liked?q=${Api.q(q)}").cards }.getOrElse { emptyList() }
    }
    val shown = cards.orEmpty().filter { filter == "all" || (filter == "saved" && it.saved) || (filter == "liked" && it.liked) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            Text("Liked", style = Type.title, color = c.text, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
            SearchField(q, { q = it }, "Search your library")
            LazyRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("all" to "All", "liked" to "Liked", "saved" to "Saved for later")) { (k, l) ->
                    Pill(l, selected = filter == k, style = Type.chip, padding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)) { filter = k }
                }
            }
        }
        if (cards != null && shown.isEmpty()) item {
            Text("Swipe right on a story to keep it here. Double-tap saves without liking.", style = Type.summary, color = c.text2, modifier = Modifier.padding(top = 20.dp))
        }
        items(shown, key = { it.id }) { card ->
            StoryRow(card, onOpen) {
                if (card.saved) Pill("Saved", style = Type.chip, padding = PaddingValues(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}
