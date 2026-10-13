package com.openhamshack.core.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import com.openhamshack.core.R

/*
 * The pieces every TV screen of every kind is made from, taken from OpenHamShack
 * Remote's TV mode (ui/TvUi.kt), where each one was a fix for something seen
 * on a real Fire TV or the Android TV emulator. The comments say what.
 */

/** ON AIR is red everywhere, as on the box consoles; nothing else is. */
val OnAirRed = Color(0xFFE53935)
val Amber = Color(0xFFFFB300)
val LockGreen = Color(0xFF66BB6A)
val Dim = Color(0xFF9E9E9E)
/** Focus on a TV is a ring the whole room can see, not Material's faint tint. */
val TvFocus = Color(0xFF4FC3F7)
val TvCard = Color(0xFF161616)
val TvCardFocused = Color(0xFF243440)

@Composable
fun OpenHamShackTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TvFocus,
            onPrimary = Color.Black,
            secondary = Amber,
            background = Color.Black,
            surface = Color(0xFF121212),
            surfaceVariant = Color(0xFF1E1E1E),
            error = OnAirRed,
        ),
        content = content,
    )
}

/**
 * A television: Android TV, Google TV, Fire TV. The UI mode is what those
 * report; a device with no touchscreen at all counts too, because some Fire
 * OS sets have been seen reporting NORMAL.
 */
fun isTelevision(context: Context): Boolean {
    val ui = context.getSystemService(UiModeManager::class.java)
    return ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
}

/** The TV's safe area: ~5% overscan at 1080p, inside which every TV screen lays itself out. */
val TvSafeH = 48.dp
val TvSafeV = 27.dp

/**
 * The margin round a radio's own screen on a TV (the Icom face, Flex's
 * display): about 2% of a 1080p screen's 960 x 540 dp, not the 5% safe area.
 * Brian (10 October 2026) found the faces lost in a large black border on
 * his Fire TV. Television sets have not overscanned HDMI by default for
 * years, and Fire TV lays apps out 1:1; a radio's screen running to within a
 * finger of the edge is what he asked for. Menus and lists keep [TvSafeH].
 */
val TvFaceH = 20.dp
val TvFaceV = 12.dp

/**
 * Scroll a focused item into view only as far as needed, instead of the
 * leanback pivot Compose uses by default on a TV (focused item held ~30% down
 * the list). On short screens the pivot scrolled headings away for nothing
 * and left a picker's content in the lower half of a Fire TV's screen.
 * Provide it with `LocalBringIntoViewSpec provides ScrollOnlyAsNeeded`.
 */
@OptIn(ExperimentalFoundationApi::class)
object ScrollOnlyAsNeeded : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= 0 && trailing <= containerSize -> 0f        // already in view
            offset < 0 && trailing > containerSize -> 0f          // larger than the view: leave it
            offset < 0 || size > containerSize -> offset          // above: bring its top in
            else -> trailing - containerSize                      // below: bring its bottom in
        }
    }
}

/**
 * One focusable thing on a TV screen: a bright ring, a lighter card and a
 * slight lift when focused, visible from a sofa. OK (centre) and Enter click
 * it. [selected] is a green edge for the current choice among chips, so it
 * reads apart from the focus ring.
 */
/**
 * True under a panel or overlay: every [TvItem] in it stops taking focus.
 * Compose's focus search is geometric and knows nothing of what is drawn on
 * top, so on the Android TV emulator a Down on a full-screen list moved focus
 * to a control of the screen hidden beneath it, and the next OK retuned the
 * radio from a screen nobody could see. Provide true around whatever an open
 * panel covers.
 */
val LocalTvInert = compositionLocalOf { false }

@Composable
fun TvItem(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    selected: Boolean = false,
    onFocus: (Boolean) -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val inert = LocalTvInert.current
    Row(
        modifier
            .focusProperties { canFocus = !inert }
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .scale(if (focused) 1.02f else 1f)
            .clip(shape)
            .background(if (focused) TvCardFocused else TvCard)
            .border(3.dp, if (focused) TvFocus else if (selected) LockGreen else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun TvSection(title: String, modifier: Modifier = Modifier) {
    Text(title, color = Dim, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp))
}

/** Keep the screen on while this is composed: a TV must not dim mid-listen. */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Hide the system bars while this is composed. */
@Composable
fun Immersive() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val c = window?.let { WindowCompat.getInsetsController(it, view) }
        c?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { c?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * One value typed on the TV's on-screen keyboard. The field takes focus as
 * the dialog opens, which brings the keyboard up, and the keyboard's Done
 * sends it - nothing to navigate to on a remote. [onOk] gets the text and a
 * way to say why not, which leaves the dialog open.
 */
@Composable
fun TvTextDialog(
    title: String,
    help: String,
    type: KeyboardType,
    secret: Boolean = false,
    initial: String = "",
    onDismiss: () -> Unit,
    onOk: (String, (String) -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { field.requestFocus() }
    }
    val go = { if (text.isNotBlank()) onOk(text.trim()) { error = it } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The reason goes above the field, not below it: with the
                // on-screen keyboard up, a TV's dialog is squeezed into the
                // top half and a line under the field is clipped away - seen
                // on the Android TV emulator, where a wrong password only
                // turned the field red.
                error?.let { Text(it, color = OnAirRed, fontSize = 17.sp) }
                Text(help, color = Dim, fontSize = 15.sp)
                OutlinedTextField(
                    text, { text = it; error = null }, singleLine = true, isError = error != null,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
        },
        confirmButton = { TvItem(onClick = go) { Text(stringResource(R.string.core_ok), fontSize = 16.sp) } },
        dismissButton = { TvItem(onClick = onDismiss) { Text(stringResource(R.string.core_cancel), fontSize = 16.sp) } },
    )
}

/**
 * Back, routed by hand to the innermost enabled BackHandler (and with none,
 * the activity's own Back, which leaves the app as a TV user expects). Put it
 * on a screen's root. Compose otherwise spends a Back press that nothing
 * consumes on clearing focus - seen on the Android TV emulator: the first
 * Back on a focused row only took the ring away, the panel stayed up, and the
 * next key went nowhere.
 */
@Composable
fun rememberBackRouting(): Modifier {
    val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    return Modifier.onKeyEvent { e ->
        if (e.key != Key.Back) return@onKeyEvent false
        if (e.type == KeyEventType.KeyUp) back?.onBackPressed()
        true
    }
}
