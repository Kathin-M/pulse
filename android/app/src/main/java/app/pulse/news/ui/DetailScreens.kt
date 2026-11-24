package app.pulse.news.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.pulse.news.data.Api
import app.pulse.news.data.OkResponse
import app.pulse.news.data.ReaderArticle
import app.pulse.news.data.SourceItem
import app.pulse.news.data.StoryDetail
import kotlinx.coroutines.launch

@Composable
private fun SourceRowItem(s: SourceItem, onRead: (Int) -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f).clickable { openInBrowser(ctx, s.url, c.dark) }) {
            Text(s.publisher, style = Type.meta.copy(fontWeight = FontWeight.SemiBold), color = c.text)
            Text(s.title, style = Type.meta, color = c.text2, maxLines = 2)
            s.note?.let { Text(it, style = Type.chip, color = c.text2, modifier = Modifier.padding(top = 3.dp)) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ago(s.time), style = Type.chip, color = c.text2)
            if (s.hasBody) Pill("Read", style = Type.chip, padding = PaddingValues(horizontal = 10.dp, vertical = 5.dp)) { onRead(s.articleId) }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
}

/** Every outlet covering the event, a timeline of what changed, and the reader. */
@Composable
fun StoryDetailScreen(storyId: Int, onClose: () -> Unit, onRead: (Int) -> Unit) {
    val c = LocalColors.current
    val scope = rememberCoroutineScope()
    var d by remember { mutableStateOf<StoryDetail?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var followed by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("sources") }
    var showMore by remember { mutableStateOf(false) }

    LaunchedEffect(storyId) {
        runCatching { Api.get<StoryDetail>("/api/story/$storyId") }
            .onSuccess { d = it; followed = it.followed; saved = it.saved }
            .onFailure { err = it.message }
    }

    Box(Modifier.fillMaxSize().background(c.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextLink("‹ Back", onClick = onClose)
                Spacer(Modifier.weight(1f))
                d?.card?.let { card ->
                    val ctx = LocalContext.current
                    Pill("Share") { shareStory(ctx, card, scope) }
                    Spacer(Modifier.width(8.dp))
                }
                Pill(if (saved) "Saved" else "Save", selected = saved) {
                    saved = !saved
                    scope.launch { runCatching { Api.interact(storyId, if (saved) "save" else "unsave") }; Toaster.show(if (saved) "Saved for later" else "Removed from saved") }
                }
                Spacer(Modifier.width(8.dp))
                Pill(if (followed) "Following story" else "Follow story", selected = followed) {
                    followed = !followed
                    scope.launch {
                        runCatching { Api.interact(storyId, if (followed) "follow" else "unfollow") }
                        Toaster.show(if (followed) "You'll get an update card when something new happens" else "Stopped following")
                    }
                }
            }
            val detail = d
            val card = detail?.card
            when {
                err != null -> Text(err ?: "", style = Type.summary, color = c.text2, modifier = Modifier.padding(20.dp))
                detail == null || card == null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(6.dp)).background(c.surface)) }
                }
                else -> LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)) {
                    if (card.image != null) item {
                        NewsImage(card.image, card.lqip, Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(16.dp)))
                        Spacer(Modifier.height(16.dp))
                    }
                    item {
                        Text(card.title, style = Type.headlineXl, color = c.text)
                        Text(
                            "${card.sourceCount} ${if (card.sourceCount == 1) "outlet" else "outlets"} · first reported ${ago(card.firstSeen)}",
                            style = Type.meta, color = c.text2, modifier = Modifier.padding(top = 8.dp),
                        )
                        if (!card.summary.isNullOrBlank()) Text(card.summary, style = Type.summary, color = c.text.copy(alpha = 0.9f), modifier = Modifier.padding(top = 14.dp))
                    }
                    if (detail.timeline.size > 1) item {
                        Text("Timeline", style = Type.meta, color = c.text, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
                        detail.timeline.forEachIndexed { i, t ->
                            val last = i == detail.timeline.lastIndex
                            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape).background(if (last) c.accent else c.text2))
                                Text("${ago(t.time)} · ${t.title}", style = Type.meta.copy(fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium), color = if (last) c.text else c.text2)
                            }
                        }
                    }
                    item {
                        Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            TextLink("Sources", color = if (tab == "sources") c.text else c.text2) { tab = "sources" }
                            if (detail.explainers.isNotEmpty())
                                TextLink("Explainers & live (${detail.explainers.size})", color = if (tab == "explainers") c.text else c.text2) { tab = "explainers" }
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    }
                    if (tab == "sources") {
                        items(detail.sources, key = { "s${it.articleId}" }) { SourceRowItem(it, onRead) }
                        if (detail.moreSources.isNotEmpty() && !showMore) item {
                            TextLink("+${detail.moreSources.size} more sources", modifier = Modifier.padding(vertical = 8.dp)) { showMore = true }
                        }
                        if (showMore) items(detail.moreSources, key = { "m${it.articleId}" }) { SourceRowItem(it, onRead) }
                    } else {
                        items(detail.explainers, key = { "e${it.articleId}" }) {
                            SourceRowItem(it.copy(note = if (it.kind == "live") "Live blog" else "Opinion / explainer", hasBody = true), onRead)
                        }
                    }
                    detail.thread?.let { t -> item {
                        Text("Part of the thread “${t.title}” · ${t.count} stories", style = Type.meta, color = c.text2, modifier = Modifier.padding(top = 20.dp))
                    } }
                    if (detail.articleCount > 1) item {
                        Text(
                            "These are different stories", style = Type.meta.copy(textDecoration = TextDecoration.Underline), color = c.text2,
                            modifier = Modifier.padding(top = 20.dp).clickable {
                                scope.launch {
                                    runCatching { Api.post<OkResponse>("/api/story/$storyId/split") }
                                        .onSuccess { Toaster.show("Split into separate stories"); onClose() }
                                        .onFailure { Toaster.show(it.message ?: "Couldn't split") }
                                }
                            },
                        )
                    }
                }
            }
        }
        d?.card?.let { card ->
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, c.bg, c.bg)))
                    .navigationBarsPadding().padding(16.dp)
            ) {
                PrimaryButton("Read full article (reader view)", Modifier.fillMaxWidth()) { onRead(card.articleId) }
            }
        }
    }
}

/** Clean reader view: extracted text, no ads. Reading time feeds the ranking. */
@Composable
fun ReaderScreen(articleId: Int, storyId: Int, onClose: () -> Unit) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    var a by remember { mutableStateOf<ReaderArticle?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(articleId) {
        runCatching { Api.get<ReaderArticle>("/api/article/$articleId/reader") }.onSuccess { a = it }.onFailure { err = it.message }
    }
    DisposableEffect(articleId) {
        val opened = SystemClock.elapsedRealtime()
        onDispose {
            val dwell = SystemClock.elapsedRealtime() - opened
            if (dwell > 3000) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    runCatching { Api.interact(storyId, "read", dwell, articleId) }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextLink("‹ Close", onClick = onClose)
            Spacer(Modifier.weight(1f))
            a?.let { art -> TextLink("Original site ↗") { openInBrowser(ctx, art.url, c.dark) } }
        }
        val art = a
        when {
            err != null -> Text(err ?: "", style = Type.summary, color = c.text2, modifier = Modifier.padding(20.dp))
            art == null -> Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(6) { Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(6.dp)).background(c.surface)) }
            }
            else -> LazyColumn(contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 60.dp), modifier = Modifier.navigationBarsPadding()) {
                item {
                    Text("${art.publisher} · ${ago(art.publishedAt)}", style = Type.meta, color = c.text2)
                    Text(art.title, style = Type.headlineXl, color = c.text, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                    if (art.image != null) {
                        NewsImage(art.image, null, Modifier.fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(16.dp)))
                        Spacer(Modifier.height(22.dp))
                    }
                }
                if (art.paragraphs.isNotEmpty()) {
                    items(art.paragraphs) { p -> Text(p, style = Type.reader, color = c.text, modifier = Modifier.padding(bottom = 18.dp)) }
                } else item {
                    art.summary?.let { Text(it, style = Type.reader, color = c.text, modifier = Modifier.padding(bottom = 18.dp)) }
                    Text("The full text isn't available here (it may be paywalled).", style = Type.summary, color = c.text2)
                }
                item {
                    Pill("Read on ${art.publisher} ↗", modifier = Modifier.padding(top = 12.dp)) { openInBrowser(ctx, art.url, c.dark) }
                }
            }
        }
    }
}
