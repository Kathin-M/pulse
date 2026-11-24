package app.pulse.news.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pulse.news.data.Card

/**
 * Reels-style action rail on the right of the top card: Like (= swipe right), Save, Share, Sources.
 * Buttons consume their own taps, so pressing one never opens the card or starts a swipe.
 */
@Composable
fun ActionRail(card: Card, light: Boolean, onLike: () -> Unit, onSave: (Boolean) -> Unit, onShare: () -> Unit, onSources: () -> Unit, modifier: Modifier = Modifier) {
    var saved by remember(card.id) { mutableStateOf(card.saved) }
    val c = LocalColors.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RailButton(PulseIcons.Heart, "Like", light, onLike)
        RailButton(PulseIcons.Bookmark, if (saved) "Saved" else "Save", light, tint = if (saved) c.accent else null) {
            saved = !saved
            onSave(saved)
        }
        RailButton(PulseIcons.Send, "Share", light, onShare)
        if (card.sourceCount > 1) RailButton(PulseIcons.Sources, "${card.sourceCount}", light, onSources)
    }
}

@Composable
private fun RailButton(icon: ImageVector, label: String, light: Boolean, onClick: () -> Unit) = RailButton(icon, label, light, null, onClick)

@Composable
private fun RailButton(icon: ImageVector, label: String, light: Boolean, tint: Color?, onClick: () -> Unit) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 1.15f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "rail")
    val fg = tint ?: if (light) Color.White else c.text
    Column(
        Modifier.clip(RoundedCornerShape(16.dp)).clickable(interactionSource = interaction, indication = null) { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.scale(s).size(46.dp).clip(CircleShape).background(if (light) Color.Black.copy(alpha = 0.32f) else c.raised),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = fg, modifier = Modifier.size(22.dp)) }
        Text(
            label,
            style = Type.chip.copy(
                fontWeight = FontWeight.SemiBold,
                shadow = if (light) Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 6f) else null,
            ),
            color = if (light) Color.White else c.text,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Faint markers seated on the card edges showing what each swipe does (Controls → Look & feel to hide). */
@Composable
fun SwipeHints(dragging: Boolean, modifier: Modifier = Modifier) {
    val a by animateFloatAsState(if (dragging) 0f else 1f, label = "hints")
    Box(modifier.padding(horizontal = 0.dp)) {
        // seated astride the card edges (half outside) so they never cover the story
        Hint("↑ Fine", Modifier.align(Alignment.TopCenter).offset(y = (-13).dp), a)
        Hint("↓ Less", Modifier.align(Alignment.BottomCenter).offset(y = 13.dp), a)
        Hint("‹ Seen", Modifier.align(Alignment.CenterStart).offset(x = (-8).dp), a)
        Hint("More ›", Modifier.align(Alignment.TopEnd).padding(top = 110.dp).offset(x = 8.dp), a)
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier, alpha: Float) {
    if (alpha <= 0.01f) return
    Text(
        text,
        style = Type.chip.copy(fontWeight = FontWeight.Bold),
        color = Color.White.copy(alpha = 0.9f * alpha),
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f * alpha)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
