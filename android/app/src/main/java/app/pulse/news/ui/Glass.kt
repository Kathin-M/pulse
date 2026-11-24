package app.pulse.news.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/*
 * Liquid-glass components for Compose, modelled on @sasivisvan/glass-morphism
 * (https://github.com/Sasivisvan/Glass-Morphism, MIT): same materials (tint, rim gradient lit at
 * 135°, inner highlight, soft shadow) and the same "droplet" selection for segmented controls and
 * tab bars, which swells into a lens while pressed.
 */

/** iOS-style overshoot, the library's --lg-ease: cubic-bezier(.3, 1.4, .4, 1). */
val GlassEase = CubicBezierEasing(0.3f, 1.4f, 0.4f, 1f)

object GlassTokens {
    @Composable fun tint(): Color = if (LocalColors.current.dark) Color(0x73121214) else Color(0x8CFFFFFF)
    @Composable fun indicator(): Color = if (LocalColors.current.dark) Color(0x33FFFFFF) else Color(0xCCFFFFFF)
    @Composable fun rimStrength(): Float = if (LocalColors.current.dark) 1f else 0.7f
}

/** The specular rim: a hairline gradient brighter at the top-left and bottom-right, like the library's ::before. */
@Composable
fun rimBrush(strength: Float = GlassTokens.rimStrength()): Brush = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.75f * strength),
    0.22f to Color.White.copy(alpha = 0.12f * strength),
    0.5f to Color.White.copy(alpha = 0.05f * strength),
    0.8f to Color.White.copy(alpha = 0.14f * strength),
    1f to Color.White.copy(alpha = 0.5f * strength),
    start = Offset(0f, 0f),
    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
)

fun Modifier.glass(shape: Shape, tint: Color, rim: Brush, elevation: Dp = 10.dp): Modifier = this
    .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f))
    .clip(shape)
    .background(tint)
    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.07f), 0.5f to Color.Transparent))   // inner glow
    .border(1.dp, rim, shape)

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
    tint: Color = GlassTokens.tint(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, tint, rimBrush()), content = content)
}

/** Segmented control whose selection is a glass droplet that slides between options. */
@Composable
fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lens by animateFloatAsState(if (pressed) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "lens")
    GlassSurface(modifier.height(44.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(3.dp)) {
            val segW = maxWidth / options.size
            val x by animateDpAsState(segW * selected, tween(450, easing = GlassEase), label = "droplet")
            Box(
                Modifier.offset(x = x).width(segW).fillMaxHeight()
                    .graphicsLayer { scaleX = lens; scaleY = lens * 1.08f }
                    .glass(RoundedCornerShape(50), GlassTokens.indicator(), rimBrush(0.6f), elevation = 3.dp)
            )
            Row(Modifier.fillMaxSize()) {
                options.forEachIndexed { i, label ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                            .clickable(interactionSource = interaction, indication = null) { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = Type.meta.copy(fontWeight = FontWeight.SemiBold), color = if (i == selected) c.text else c.text2)
                    }
                }
            }
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector)

/** Floating glass tab bar with the same droplet selection. */
@Composable
fun GlassTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lens by animateFloatAsState(if (pressed) 1.1f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "lens")
    GlassSurface(modifier.height(62.dp), shape = RoundedCornerShape(31.dp)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val segW = maxWidth / items.size
            val x by animateDpAsState(segW * selected.coerceAtLeast(0), tween(450, easing = GlassEase), label = "droplet")
            if (selected >= 0) Box(
                Modifier.offset(x = x).width(segW).fillMaxHeight()
                    .graphicsLayer { scaleX = lens; scaleY = lens * 1.06f }
                    .glass(RoundedCornerShape(27.dp), GlassTokens.indicator().copy(alpha = if (c.dark) 0.14f else 0.75f), rimBrush(0.5f), elevation = 2.dp)
            )
            Row(Modifier.fillMaxSize()) {
                items.forEachIndexed { i, item ->
                    val on = i == selected
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(27.dp))
                            .clickable(interactionSource = interaction, indication = null) { onSelect(i) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(item.icon, null, tint = if (on) c.accent else c.text2, modifier = Modifier.size(22.dp))
                        Text(item.label, style = Type.chip.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.accent else c.text2)
                    }
                }
            }
        }
    }
}

/** Round glass button (undo, close). Scales up with a spring on press, like .lg-button. */
@Composable
fun GlassIconButton(icon: ImageVector, contentDescription: String, enabled: Boolean = true, size: Dp = 44.dp, onClick: () -> Unit) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 1.1f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "press")
    GlassSurface(
        Modifier.size(size).scale(s).clickable(enabled = enabled, interactionSource = interaction, indication = null) { onClick() },
        shape = CircleShape,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) c.text else c.text2.copy(alpha = 0.4f), modifier = Modifier.align(Alignment.Center).size(20.dp))
    }
}

/** Glass capsule grouping actions, like .lg-toolbar. Use [GlassDivider] between groups. */
@Composable
fun GlassToolbar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    GlassSurface(modifier.height(52.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
fun GlassDivider() {
    Box(Modifier.padding(horizontal = 2.dp).width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.25f)))
}

@Composable
fun RowScope.ToolbarButton(text: String, icon: ImageVector? = null, color: Color? = null, onClick: () -> Unit) {
    val c = LocalColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 1.12f else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "press")
    Row(
        Modifier.weight(1f).fillMaxHeight().scale(s).clip(RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color ?: c.text, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = Type.meta.copy(fontWeight = FontWeight.SemiBold), color = color ?: c.text, maxLines = 1)
    }
}

// ---------------------------------------------------------------- line icons (stroke paths, 24×24)
private fun strokeIcon(name: String, vararg paths: String): ImageVector {
    val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    for (d in paths) {
        b.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

object PulseIcons {
    val Feed = strokeIcon("feed", "M8 3.5h8a3 3 0 0 1 3 3v11a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-11a3 3 0 0 1 3-3z", "M8.5 8h7", "M8.5 12h7", "M8.5 16h4")
    val Seen = strokeIcon("seen", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 0 1 0-17z", "M12 7.5V12l3 2")
    val Liked = strokeIcon("liked", "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z")
    val Controls = strokeIcon("controls", "M4 7h10", "M18 7h2", "M4 17h4", "M12 17h8", "M16 5a2 2 0 1 1 0 4a2 2 0 0 1 0-4z", "M10 15a2 2 0 1 1 0 4a2 2 0 0 1 0-4z")
    val Undo = strokeIcon("undo", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val Share = strokeIcon("share", "M12 3v13", "m7 8 5-5 5 5", "M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6")
}
