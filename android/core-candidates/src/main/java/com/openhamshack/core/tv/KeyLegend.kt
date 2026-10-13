package com.openhamshack.core.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.core.R
import com.openhamshack.core.keys.Gesture
import com.openhamshack.core.keys.KeyMap
import com.openhamshack.core.keys.RemoteAction
import com.openhamshack.core.keys.RemoteKey
import com.openhamshack.core.keys.legendRows

/**
 * The key legend (REMOTE-KEYS.md §6.2): Menu held, Info or F1, on any face.
 * Every key the map binds, for this face and this moment, in the face's
 * own words where it has them; keys it does not use are dimmed with "—".
 * Built from [legendRows], the same table the keys are dispatched by, so
 * the legend cannot say one thing while a key does another.
 *
 * A list rather than a drawing of a remote: it reads from a sofa at any
 * size, and needs no artwork (no external assets).
 */
@Composable
fun KeyLegend(face: FaceKeys, can: (RemoteAction) -> Boolean, onClose: () -> Unit) {
    BackHandler { onClose() }
    val focus = remember { FocusRequester() }
    val pressed = remember { mutableSetOf<Key>() }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    val rows = legendRows(KeyMap.STANDARD, face.faceClass, can)
    Box(
        Modifier.fillMaxSize().background(Color(0xE6000000)).focusRequester(focus)
            .onKeyEvent { e ->
                // OK closes it, as do the keys that open it (Info, Menu) - on a
                // release whose press it saw: Menu held opens it, and that
                // hold's own release must not close it again.
                val closes = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter ||
                    e.key == Key.Info || e.key == Key.Menu || e.key == Key.F1
                if (e.type == KeyEventType.KeyDown) pressed += e.key
                if (closes && e.type == KeyEventType.KeyUp && pressed.remove(e.key)) onClose()
                // Everything else stays here: focus must not wander to the face beneath.
                e.key != Key.Back
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 1100.dp).padding(horizontal = TvSafeH, vertical = TvSafeV)
                .background(Color(0xFF101418), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.core_legend_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            // Two columns: twenty-odd lines in one would not fit a TV's height at a readable size.
            val half = (rows.size + 1) / 2
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                for (part in listOf(rows.take(half), rows.drop(half))) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        part.forEach { r ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                val name = keyName(r.key)
                                Text(if (r.gesture == Gesture.HOLD) stringResource(R.string.core_key_held, name) else name,
                                    color = if (r.active) TvFocus else Dim, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(150.dp))
                                Text(if (r.active) face.label(r.action) ?: actionWords(r.action) else stringResource(R.string.core_legend_unused),
                                    color = if (r.active) Color.White else Dim, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.core_legend_tv_own), color = Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            Text(stringResource(R.string.core_legend_close), color = Dim, fontSize = 15.sp)
        }
    }
}

/** A remote key as the legend and the hints name it. */
@Composable
fun keyName(k: RemoteKey): String = when (k) {
    RemoteKey.LEFT -> "◀"
    RemoteKey.RIGHT -> "▶"
    RemoteKey.UP -> "▲"
    RemoteKey.DOWN -> "▼"
    RemoteKey.OK -> stringResource(R.string.core_key_ok)
    RemoteKey.BACK -> stringResource(R.string.core_key_back)
    RemoteKey.MENU -> stringResource(R.string.core_key_menu)
    RemoteKey.PLAY_PAUSE -> "▶❚❚"
    RemoteKey.REWIND -> "◀◀"
    RemoteKey.FAST_FORWARD -> "▶▶"
    RemoteKey.CHANNEL_UP -> stringResource(R.string.core_key_channel_up)
    RemoteKey.CHANNEL_DOWN -> stringResource(R.string.core_key_channel_down)
    RemoteKey.INFO -> stringResource(R.string.core_key_info)
    else -> stringResource(R.string.core_key_digits)
}

/** What an action does, in the shared words. */
@Composable
fun actionWords(a: RemoteAction): String = stringResource(when (a) {
    RemoteAction.TUNE_DOWN -> R.string.core_action_tune_down
    RemoteAction.TUNE_UP -> R.string.core_action_tune_up
    RemoteAction.STEP_DOWN -> R.string.core_action_step_down
    RemoteAction.STEP_UP -> R.string.core_action_step_up
    RemoteAction.BAND_PREV -> R.string.core_action_band_prev
    RemoteAction.BAND_NEXT -> R.string.core_action_band_next
    RemoteAction.CHANNEL_PREV -> R.string.core_action_channel_prev
    RemoteAction.CHANNEL_NEXT -> R.string.core_action_channel_next
    RemoteAction.CHOOSE -> R.string.core_action_choose
    RemoteAction.CONTROLS -> R.string.core_action_controls
    RemoteAction.FOCUS_DOWN -> R.string.core_action_focus_down
    RemoteAction.FREQUENCY -> R.string.core_action_frequency
    RemoteAction.SETTINGS -> R.string.core_action_settings
    RemoteAction.HELP -> R.string.core_action_help
    RemoteAction.MUTE -> R.string.core_action_mute
    RemoteAction.TRANSMIT -> R.string.core_action_transmit
    RemoteAction.BACK -> R.string.core_action_back
})
