package app.pulse.news.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// ---------------------------------------------------------------- time helpers
fun parseTime(iso: String?): OffsetDateTime? = runCatching { OffsetDateTime.parse(iso) }.getOrNull()

fun ago(iso: String?): String {
    val t = parseTime(iso) ?: return ""
    val s = Duration.between(t, OffsetDateTime.now()).seconds
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86400 -> "${s / 3600}h ago"
        s < 172800 -> "yesterday"
        else -> "${s / 86400}d ago"
    }
}

fun dayBucket(iso: String?): String {
    val t = parseTime(iso) ?: return "Earlier"
    val d = t.atZoneSameInstant(ZoneId.systemDefault()).toLocalDate()
    val days = LocalDate.now().toEpochDay() - d.toEpochDay()
    return when {
        days <= 0 -> "Today"
        days == 1L -> "Yesterday"
        days < 7 -> "This week"
        else -> "Earlier"
    }
}

// ---------------------------------------------------------------- misc helpers
fun decodeLqip(dataUri: String?): ImageBitmap? = runCatching {
    val b64 = dataUri!!.substringAfter("base64,")
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size).asImageBitmap()
}.getOrNull()

fun haptic(view: View, strong: Boolean = false) {
    if (!app.pulse.news.data.Store.haptics) return
    val c = if (Build.VERSION.SDK_INT >= 30) {
        if (strong) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK
    } else HapticFeedbackConstants.VIRTUAL_KEY
    view.performHapticFeedback(c)
}

fun openInBrowser(ctx: Context, url: String, dark: Boolean) {
    runCatching {
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setDefaultColorSchemeParams(
                CustomTabColorSchemeParams.Builder().setToolbarColor(if (dark) 0xFF0B0B0C.toInt() else 0xFFF7F5F0.toInt()).build()
            )
            .build()
            .launchUrl(ctx, Uri.parse(url))
    }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

// ---------------------------------------------------------------- toast
object Toaster {
    var message by mutableStateOf<String?>(null)
    var stamp by mutableStateOf(0L)
    fun show(msg: String) { message = msg; stamp = System.nanoTime() }
}

@Composable
fun BoxScope.ToastHost() {
    val c = LocalColors.current
    val msg = Toaster.message
    LaunchedEffect(Toaster.stamp) {
        if (msg != null) { delay(2200); Toaster.message = null }
    }
    AnimatedVisibility(
        visible = msg != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 84.dp, start = 24.dp, end = 24.dp),
    ) {
        Text(
            msg ?: "",
            style = Type.meta,
            color = c.text,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(c.raised).padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// ---------------------------------------------------------------- small components
@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    color: Color? = null,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    style: TextStyle = Type.meta,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    Text(
        text,
        style = style,
        color = color ?: if (selected) Color.Black else c.text,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.accent else c.raised)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(padding),
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val c = LocalColors.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.accent)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = Type.summary.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Color.Black) }
}

@Composable
fun TextLink(text: String, color: Color? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalColors.current
    Text(text, style = Type.meta, color = color ?: c.text2, modifier = modifier.clip(RoundedCornerShape(8.dp)).clickable { onClick() }.padding(6.dp))
}

@Composable
fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Default,
    onAction: (() -> Unit)? = null,
) {
    val c = LocalColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = Type.summary.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onAction?.invoke() }),
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(c.raised).padding(horizontal = 18.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = Type.summary, color = c.text2)
                inner()
            }
        },
    )
}

@Composable
fun SectionTitle(text: String, hint: String? = null) {
    val c = LocalColors.current
    Text(text, style = Type.meta, color = c.text, modifier = Modifier.padding(bottom = 2.dp))
    if (hint != null) Text(hint, style = Type.chip, color = c.text2, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
fun AccentRule() {
    val c = LocalColors.current
    Box(Modifier.padding(vertical = 2.dp).fillMaxWidth(0.14f).background(c.accent).padding(vertical = 1.dp))
}

@Composable
fun rememberFlag(initial: Boolean = false) = remember { mutableStateOf(initial) }

@Composable
fun SpacedRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** Android share sheet for a story; sharing also tells the ranker it mattered. */
fun shareStory(ctx: Context, card: app.pulse.news.data.Card, scope: kotlinx.coroutines.CoroutineScope) {
    scope.launch_safe { app.pulse.news.data.Api.interact(card.storyIdForDetail, "share") }
    shareText(ctx, card.displayTitle, card.url)
}

fun shareText(ctx: Context, title: String, url: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .putExtra(Intent.EXTRA_TEXT, "$title\n$url")
    ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun kotlinx.coroutines.CoroutineScope.launch_safe(block: suspend () -> Unit) {
    launch { runCatching { block() } }
}
