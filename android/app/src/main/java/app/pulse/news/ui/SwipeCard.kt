package app.pulse.news.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pulse.news.data.Card
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Lets buttons and the Undo flow drive the top card. */
@Stable
class SwipeController {
    var request by mutableStateOf<String?>(null)
    fun swipe(dir: String) { request = dir }
}

/**
 * The draggable top card. Right = more like this, left = seen, up = fine, down = less like this.
 * It follows the finger, tilts up to 8°, shows the decision label as you drag, and commits
 * past ~35% of the width (or on a fast flick); otherwise it springs back.
 */
@Composable
fun SwipeCard(
    card: Card,
    controller: SwipeController,
    enterFrom: String?,
    onSwiped: (dir: String, dwellMs: Long) -> Unit,
    onTap: (dwellMs: Long) -> Unit,
    onDoubleTap: () -> Unit,
    onDragging: (Boolean) -> Unit,
    onChip: (ChipRef) -> Unit,
    onWhy: () -> Unit,
) {
    val c = LocalColors.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val shownAt = remember { SystemClock.elapsedRealtime() }
    val start = when (enterFrom) {
        "right" -> Offset(2000f, 0f)
        "left" -> Offset(-2000f, 0f)
        "down" -> Offset(0f, 2600f)
        "up" -> Offset(0f, -2600f)
        else -> Offset.Zero
    }
    val offset = remember { Animatable(start, Offset.VectorConverter) }
    var committed by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        fun springBack() = scope.launch { offset.animateTo(Offset.Zero, spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) }

        fun fly(dir: String) {
            if (committed) return
            committed = true
            haptic(view, strong = true)
            val target = when (dir) {
                "right" -> Offset(w * 1.5f, offset.value.y)
                "left" -> Offset(-w * 1.5f, offset.value.y)
                "up" -> Offset(offset.value.x, -h * 1.3f)
                else -> Offset(offset.value.x, h * 1.3f)
            }
            scope.launch {
                offset.animateTo(target, tween(180, easing = FastOutLinearInEasing))
                onSwiped(dir, SystemClock.elapsedRealtime() - shownAt)
            }
        }

        // Undo flies the card back in with the same spring.
        LaunchedEffect(Unit) {
            if (enterFrom != null) offset.animateTo(Offset.Zero, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        }
        LaunchedEffect(controller.request) {
            controller.request?.let { fly(it); controller.request = null }
        }

        val x = offset.value.x
        val y = offset.value.y
        Box(
            Modifier
                .fillMaxSize()
                // gestures are read in the parent's frame, before the card is moved/rotated
                .pointerInput(card.id) {
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { tracker.resetTracking(); onDragging(true) },
                        onDragCancel = { onDragging(false); springBack() },
                        onDragEnd = {
                            onDragging(false)
                            val v = tracker.calculateVelocity()
                            val ox = offset.value.x
                            val oy = offset.value.y
                            val horizontal = abs(ox) > abs(oy)
                            when {
                                horizontal && (ox > w * 0.35f || v.x > 1800f) -> fly("right")
                                horizontal && (ox < -w * 0.35f || v.x < -1800f) -> fly("left")
                                !horizontal && (oy > h * 0.22f || v.y > 2000f) -> fly("down")
                                !horizontal && (oy < -h * 0.22f || v.y < -2000f) -> fly("up")
                                else -> springBack()
                            }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch { offset.snapTo(Offset(offset.value.x + amount.x, offset.value.y + amount.y)) }
                        },
                    )
                }
                .pointerInput(card.id) {
                    detectTapGestures(
                        onTap = { onTap(SystemClock.elapsedRealtime() - shownAt) },
                        onDoubleTap = { haptic(view); onDoubleTap() },
                    )
                }
                .graphicsLayer {
                    translationX = x
                    translationY = y
                    rotationZ = (x / w * 8f).coerceIn(-8f, 8f)
                }
        ) {
            CardView(card, onChip = onChip, onWhy = onWhy)

            // decision labels fade in with drag distance
            Label("More like this", c.more, ((x - 20f) / 120f).coerceIn(0f, 1f), Modifier.align(Alignment.TopStart).padding(22.dp).rotate(-6f))
            Label("Seen", c.seen, ((-x - 20f) / 120f).coerceIn(0f, 1f), Modifier.align(Alignment.TopEnd).padding(22.dp).rotate(6f))
            Label("Less like this", c.less, ((y - 20f) / 140f).coerceIn(0f, 1f), Modifier.align(Alignment.TopCenter).padding(22.dp))
            Label("Fine", c.accent, ((-y - 20f) / 140f).coerceIn(0f, 1f), Modifier.align(Alignment.BottomCenter).padding(28.dp))
        }
    }
}

@Composable
private fun Label(text: String, color: Color, alpha: Float, modifier: Modifier) {
    if (alpha <= 0f) return
    Text(
        text, style = Type.meta.copy(fontWeight = FontWeight.Bold), color = color,
        modifier = modifier.alpha(alpha).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f))
            .border(2.dp, color, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
