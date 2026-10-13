package com.openhamshack.core.tv

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.core.R
import com.openhamshack.core.keys.TxPhase

/**
 * Transmit's overlay (REMOTE-KEYS.md §5.2, §6.2): armed, it names exactly
 * what will be sent - the plan's lines: frequency, mode, message - with the
 * five seconds left as a shrinking ring and the keys that start or cancel
 * it; on the air, ON AIR in red with [onLines] (what the face says is
 * going on) and "any key stops".
 *
 * It takes no focus: the keys reach it through the face's transmit guard
 * (Modifier.remoteKeys), which is first to see every key. The buttons are
 * for touch (a phone or tablet, where there are no remote keys) and take
 * taps only, so the D-pad cannot land on one.
 */
@Composable
internal fun BoxScope.TxOverlay(s: RemoteKeysState, onLines: List<String>) {
    val phase = s.txPhase
    val tv = isTelevision(LocalContext.current)
    // On a phone or tablet in touch mode nothing has focus, and Compose gives a
    // key only to the focused chain: a keyboard's or Bluetooth remote's key
    // never reached the transmit guard, so "any key stops" was false there
    // (seen on the Pixel emulator). The overlay takes focus while it is up;
    // the guard on the face's root still sees the key first (preview). On a
    // TV the face's surface always holds focus, and taking it would leave it
    // nowhere when the overlay goes.
    val focus = remember { FocusRequester() }
    if (!tv) LaunchedEffect(phase) { runCatching { focus.requestFocus() } }
    // A dimmed screen behind, so what is armed is the one thing to read.
    Box(Modifier.fillMaxSize().background(Color(0x99000000)))
    Column(
        Modifier.align(Alignment.Center)
            .then(if (tv) Modifier else Modifier.focusRequester(focus).focusable()).widthIn(min = 520.dp, max = 900.dp).padding(16.dp)
            .background(Color(0xF0180808), RoundedCornerShape(18.dp))
            .border(4.dp, OnAirRed, RoundedCornerShape(18.dp))
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val title = when (phase) {
                TxPhase.ON -> onLines.firstOrNull() ?: s.plan?.title.orEmpty()
                else -> s.plan?.title.orEmpty()
            }
            Text(title, color = if (phase == TxPhase.ON) Color.White else OnAirRed, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).then(
                    if (phase == TxPhase.ON) Modifier.background(OnAirRed, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp)
                    else Modifier))
            if (phase == TxPhase.ARMED) Countdown(s.armUntilMs)
        }
        val lines = if (phase == TxPhase.ON) onLines.drop(1) else s.plan?.lines.orEmpty()
        lines.forEach { Text(it, color = Color.White, fontSize = 24.sp) }
        Text(
            when (phase) {
                TxPhase.ARMED -> stringResource(R.string.core_tx_armed_keys)
                TxPhase.STARTING -> stringResource(R.string.core_tx_starting)
                else -> stringResource(R.string.core_tx_on_keys)
            },
            color = Amber, fontSize = 18.sp,
        )
        // Touch only: on a TV they are buttons nobody can press, and read as if focus were somewhere.
        if (!tv) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (phase == TxPhase.ARMED) {
                TouchButton(stringResource(R.string.core_tx_start), OnAirRed) { s.confirmTouch() }
                TouchButton(stringResource(R.string.core_tx_cancel), Color(0xFF424242)) { s.cancelTouch() }
            } else {
                TouchButton(stringResource(R.string.core_tx_stop), OnAirRed) { s.cancelTouch() }
            }
        }
    }
}

/** A button by touch only: no focus, so no remote key can land on it. */
@Composable
private fun TouchButton(label: String, colour: Color, onTap: () -> Unit) {
    Box(
        Modifier.background(colour, RoundedCornerShape(10.dp)).pointerInput(onTap) { detectTapGestures { onTap() } }
            .padding(horizontal = 26.dp, vertical = 12.dp),
    ) { Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
}

/** The seconds left to confirm, as a ring that empties and the number inside it. */
@Composable
private fun Countdown(untilMs: Long) {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(untilMs) { while (true) { now = SystemClock.uptimeMillis(); delay(50) } }
    val left = (untilMs - now).coerceAtLeast(0)
    val frac = left.toFloat() / com.openhamshack.core.keys.TxArm.ARM_MS
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Color(0x40FFFFFF), 0f, 360f, false, style = Stroke(6.dp.toPx()))
            drawArc(OnAirRed, -90f, 360f * frac, false, style = Stroke(6.dp.toPx()))
        }
        Text(stringResource(R.string.core_tx_countdown, ((left + 999) / 1000).toInt()), color = Color.White, fontSize = 18.sp,
            fontWeight = FontWeight.Bold)
    }
}
