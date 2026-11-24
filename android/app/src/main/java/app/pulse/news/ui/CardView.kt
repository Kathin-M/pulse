package app.pulse.news.ui

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pulse.news.data.Card
import app.pulse.news.data.Store
import coil.compose.AsyncImage
import coil.request.ImageRequest

data class ChipRef(val label: String, val kind: String)

@Composable
fun NewsImage(url: String?, lqip: String?, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val ctx = LocalContext.current
    val preview = remember(lqip) { decodeLqip(lqip) }
    Box(modifier.background(c.raised)) {
        // Blur-up: tiny preview first, the real image sharpens in on top.
        if (preview != null) {
            Image(
                preview, null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier),
            )
        }
        if (url != null) {
            AsyncImage(
                model = remember(url) { ImageRequest.Builder(ctx).data(url).crossfade(300).build() },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun Badge(card: Card) {
    val c = LocalColors.current
    val badge = card.badge ?: return
    val text = if (badge == "Thread") "Thread · ${card.threadCount ?: 0} stories" else badge
    val explore = badge == "Something new"
    Text(
        text, style = Type.chip, color = if (explore) c.accent else Color.Black,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .then(if (explore) Modifier.background(Color.Black.copy(alpha = 0.35f)).border(1.dp, c.accent, RoundedCornerShape(50)) else Modifier.background(c.accent))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Chips(card: Card, onChip: (ChipRef) -> Unit, light: Boolean) {
    val c = LocalColors.current
    val chips = buildList {
        card.places.firstOrNull { it != "India" }?.let { add(ChipRef(it, "place")) }
        card.topics.take(2).forEach { add(ChipRef(it, "topic")) }
    }
    if (chips.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            Text(
                chip.label, style = Type.chip, color = if (light) Color.White else c.text,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (light) Color.White.copy(alpha = 0.16f) else c.raised)
                    // long-press a chip to follow or mute it right there
                    .combinedClickable(onClick = {}, onLongClick = { onChip(chip) })
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SourceRow(card: Card, light: Boolean) {
    val c = LocalColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(card.publisher, style = Type.meta.copy(fontWeight = FontWeight.SemiBold), color = if (light) Color.White else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (card.sourceCount > 1) {
            val n = card.sourceCount - 1
            Text(
                "+$n ${if (n == 1) "source" else "sources"}", style = Type.chip, color = if (light) Color.White else c.text,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (light) Color.White.copy(alpha = 0.16f) else c.raised).padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(ago(card.firstSeen), style = Type.meta, color = if (light) Color.White.copy(alpha = 0.75f) else c.text2)
    }
}

@Composable
private fun Why(card: Card, light: Boolean, onWhy: () -> Unit) {
    val c = LocalColors.current
    if (card.why.isBlank()) return
    Text(
        "Why: ${card.why}", style = Type.meta, color = if (light) Color.White.copy(alpha = 0.72f) else c.text2,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onWhy() },
    )
}

@Composable
private fun UpdateLine(card: Card, light: Boolean) {
    val c = LocalColors.current
    if (card.type != "update" || card.updateTitle == null) return
    Column {
        Text("Since you saw this", style = Type.chip, color = c.accent)
        Text(card.updateTitle, style = Type.meta, color = if (light) Color.White else c.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ThreadHeadlines(card: Card) {
    val c = LocalColors.current
    if (card.type != "thread") return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        card.headlines.orEmpty().drop(1).take(3).forEach {
            Text("· $it", style = Type.meta, color = c.text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** One story card in one of three layouts, picked from the lead image's quality at ingest. */
@Composable
fun CardView(card: Card, modifier: Modifier = Modifier, onChip: (ChipRef) -> Unit = {}, onWhy: () -> Unit = {}, rail: Boolean = false, fullBleed: Boolean = false) {
    val railPad = if (rail) 64.dp else 20.dp
    // full-screen cards sit under the floating glass top bar and tab bar: keep content clear of both
    val topClear = if (fullBleed) WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 80.dp else 0.dp
    val bottomClear = if (fullBleed) WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 88.dp else 0.dp
    val c = LocalColors.current
    val shape = if (fullBleed) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(20.dp)
    val edge = if (card.badge == "Breaking") Modifier.border(2.dp, c.accent, shape) else Modifier
    val title = card.displayTitle
    // The whole headline, always: long ones step down a size instead of being cut off.
    val headStyle = if (title.length > 110) Type.headline else Type.headlineXl

    when {
        card.layout == "full" && card.image != null -> Box(modifier.fillMaxSize().clip(shape).background(c.surface).then(edge)) {
            NewsImage(card.image, card.lqip, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxWidth().fillMaxHeight(0.72f).align(Alignment.BottomCenter).background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.85f))
                )
            )
            if (fullBleed) Box(Modifier.fillMaxWidth().height(topClear + 40.dp).align(Alignment.TopCenter).background(
                Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.45f), 1f to Color.Transparent)))
            Box(Modifier.padding(start = 16.dp, top = topClear + 16.dp).align(Alignment.TopStart)) { Badge(card) }
            Column(Modifier.fillMaxSize().padding(start = 20.dp, end = railPad, bottom = bottomClear + 20.dp, top = topClear + 56.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)) {
                SourceRow(card, light = true)
                Text(title, style = headStyle, color = Color.White)
                UpdateLine(card, light = true)
                if (card.type != "update" && !card.summary.isNullOrBlank())
                    Text(card.summary, style = Type.summary, color = Color.White.copy(alpha = 0.86f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Chips(card, onChip, light = true)
                Why(card, light = true, onWhy)
            }
        }

        card.layout == "photo" && card.image != null -> Column(modifier.fillMaxSize().clip(shape).background(c.surface).then(edge)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight(if (title.length > 90) 0.34f else 0.42f)) {
                NewsImage(card.image, card.lqip, Modifier.fillMaxSize())
                Box(Modifier.padding(start = 16.dp, top = topClear + 16.dp)) { Badge(card) }
            }
            Column(Modifier.weight(1f).padding(start = 20.dp, top = 20.dp, bottom = bottomClear + 12.dp, end = railPad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceRow(card, light = false)
                Text(title, style = Type.headline, color = c.text)
                UpdateLine(card, light = false)
                if (card.type != "update" && !card.summary.isNullOrBlank())
                    Text(card.summary, style = Type.summary, color = c.text.copy(alpha = 0.86f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                ThreadHeadlines(card)
                Spacer(Modifier.weight(1f))
                Chips(card, onChip, light = false)
                Why(card, light = false, onWhy)
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                Text(
                    "Tap for summary and all ${card.sourceCount} ${if (card.sourceCount == 1) "source" else "sources"}",
                    style = Type.meta, color = c.text2, modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }

        // Text-only editorial card: the outlet name set large is the visual.
        else -> Column(modifier.fillMaxSize().clip(shape).background(c.surface).then(edge).padding(start = 24.dp, top = topClear + 24.dp, bottom = bottomClear + 24.dp, end = if (rail) 64.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Badge(card)
            Text(
                card.publisher, fontFamily = Newsreader, fontWeight = FontWeight.SemiBold,
                fontSize = ((if (title.length > 90) 28 else 40) * Store.textScale).sp, lineHeight = ((if (title.length > 90) 32 else 44) * Store.textScale).sp,
                color = c.text2.copy(alpha = 0.55f), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.width(56.dp).height(2.dp).background(c.accent))
            Text(title, style = headStyle, color = c.text)
            UpdateLine(card, light = false)
            if (card.type != "update" && !card.summary.isNullOrBlank())
                Text(card.summary, style = Type.summary, color = c.text.copy(alpha = 0.86f), maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            ThreadHeadlines(card)
            Spacer(Modifier.weight(1f))
            SourceRow(card, light = false)
            Chips(card, onChip, light = false)
            Why(card, light = false, onWhy)
        }
    }
}
