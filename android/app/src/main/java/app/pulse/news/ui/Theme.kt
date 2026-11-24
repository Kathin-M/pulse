package app.pulse.news.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.pulse.news.R
import app.pulse.news.data.Store

/** Colour roles from the spec: dark-first, warm paper light mode, colour only where it means something. */
@Immutable
data class PulseColors(
    val bg: Color, val surface: Color, val raised: Color, val text: Color, val text2: Color,
    val divider: Color, val accent: Color, val more: Color, val seen: Color, val less: Color, val dark: Boolean,
)

val DarkColors = PulseColors(
    bg = Color(0xFF0B0B0C), surface = Color(0xFF161618), raised = Color(0xFF222225), text = Color(0xFFF2F2F0),
    text2 = Color(0xFF9A9A9E), divider = Color(0xFF2A2A2E), accent = Color(0xFFF5A524), more = Color(0xFF3DD68C),
    seen = Color(0xFF8A8A8F), less = Color(0xFFFF5C5C), dark = true,
)
val LightColors = PulseColors(
    bg = Color(0xFFF7F5F0), surface = Color(0xFFFFFFFF), raised = Color(0xFFEFECE6), text = Color(0xFF141414),
    text2 = Color(0xFF6B6966), divider = Color(0xFFE4E0D8), accent = Color(0xFFD98A00), more = Color(0xFF1FA864),
    seen = Color(0xFF8A8A8F), less = Color(0xFFE5484D), dark = false,
)

val LocalColors = staticCompositionLocalOf { DarkColors }

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Newsreader = FontFamily(variable(R.font.newsreader, 400), variable(R.font.newsreader, 600), variable(R.font.newsreader, 700))
val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600))

/** Three text sizes per card, at most: headline, summary, meta (+ chips). */
object Type {
    private val s get() = Store.textScale
    val headline get() = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.SemiBold, fontSize = (26 * s).sp, lineHeight = (32 * s).sp, letterSpacing = (-0.2).sp)
    val headlineXl get() = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.SemiBold, fontSize = (29 * s).sp, lineHeight = (35 * s).sp, letterSpacing = (-0.3).sp)
    val title get() = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp)
    val summary get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = (16 * s).sp, lineHeight = (24 * s).sp)
    val meta get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = (13 * s).sp, lineHeight = (18 * s).sp)
    val chip get() = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = (12 * s).sp, lineHeight = 16.sp)
    val reader get() = TextStyle(fontFamily = Newsreader, fontWeight = FontWeight.Normal, fontSize = (19 * s).sp, lineHeight = (30 * s).sp)
}

@Composable
fun PulseTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = if (dark) darkColorScheme(
        primary = c.accent, onPrimary = Color.Black, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = c.raised, onSurfaceVariant = c.text2,
        surfaceContainer = c.raised, surfaceContainerHigh = c.raised, outline = c.divider, secondary = c.accent,
    ) else lightColorScheme(
        primary = c.accent, onPrimary = Color.Black, background = c.bg, onBackground = c.text,
        surface = c.bg, onSurface = c.text, surfaceVariant = c.raised, onSurfaceVariant = c.text2,
        surfaceContainer = c.raised, surfaceContainerHigh = c.raised, outline = c.divider, secondary = c.accent,
    )
    CompositionLocalProvider(LocalColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
