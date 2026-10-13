package com.openhamshack.remote.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.R
import com.openhamshack.remote.Link
import com.openhamshack.remote.api.Status
import com.openhamshack.remote.ptt.PttPhase

/**
 * How long TRANSMIT must be held to key. The console asks for two taps
 * within 4 s; on a phone, which lives in a pocket and is handled with a
 * thumb, a deliberate one-second hold with the bar filling is harder to do
 * by accident and easier to do on purpose. Stopping is always one tap.
 */
const val HOLD_TO_KEY_MS = 1000L

@Composable
fun TransmitPanel(s: BoxSession, status: Status?, link: Link) {
    val ptt by s.ptt.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val onAir = status?.onAir == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (onAir) R.string.shack_on_air else R.string.wb_stand_by),
                    color = if (onAir) Color.White else Dim, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = if (onAir) Modifier.background(OnAirRed).padding(horizontal = 8.dp, vertical = 2.dp) else Modifier,
                )
                if (onAir) {
                    Text(
                        listOfNotNull(
                            status.onAirSeconds?.let { clock(it) },
                            status.txTimeout?.let { stringResource(R.string.wb_timeout_in, clock(it.secondsLeft)) },
                        ).joinToString("  ·  "),
                        modifier = Modifier.padding(start = 10.dp), fontSize = 16.sp,
                    )
                }
            }
            status?.config?.let {
                Text(txText(it), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.wb_picture_source, sourceText(it.source)), color = Dim, style = MaterialTheme.typography.bodySmall)
            }

            // The time-out's last minute: the console's red bar and Keep transmitting.
            val t = status?.txTimeout
            if (onAir && t != null && t.warning) {
                Column(Modifier.fillMaxWidth().background(OnAirRed).padding(8.dp)) {
                    Text(pluralStringResource(R.plurals.wb_timeout_seconds, t.secondsLeft, t.secondsLeft), color = Color.White, fontWeight = FontWeight.Bold)
                    if (status.auth.authorised) {
                        Button(onClick = { s.keepTransmitting() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = OnAirRed)) {
                            Text(stringResource(R.string.wb_keep_transmitting))
                        }
                    }
                }
            }
            if (ptt.linkTrouble) {
                Text(stringResource(R.string.wb_link_trouble), color = Amber)
            }
            // The box's reason first: it knows why it is not transmitting.
            val err = status?.txError?.ifBlank { null } ?: ptt.error
            if (err != null && !onAir) Text(err, color = OnAirRed)

            // The licence/band reason, big and next to the button, before
            // anyone tries: the box refuses the key-up for it.
            if (!onAir && status != null && status.txBlocked.isNotBlank()) TxBlockedBanner(status.txBlocked)
            PttButton(s, status, link)
        }
    }
}

@Composable
private fun PttButton(s: BoxSession, status: Status?, link: Link) {
    val ptt by s.ptt.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }

    val onAir = status?.onAir == true
    val mine = ptt.phase != PttPhase.Idle
    // Why keying is not possible now; null when it is.
    val cannot: String? = when {
        mine || onAir -> null
        link !is Link.Up || status == null -> stringResource(R.string.wb_ptt_not_connected)
        // On a controller: below the Transmit level the app refuses a key-up.
        status.auth.platform && !status.auth.canTransmit -> stringResource(R.string.wb_platform_tx_locked)
        status.auth.viewOnly -> stringResource(R.string.wb_ptt_view_only)
        !status.leaseSupported -> stringResource(R.string.wb_ptt_no_lease)
        status.txBlocked.isNotBlank() -> stringResource(R.string.wb_cannot_transmit)
        else -> null
    }
    val force = ptt.check != null

    val (label, colour) = when {
        ptt.phase == PttPhase.Keyed -> stringResource(R.string.wb_on_air_tap_to_stop) to OnAirRed
        ptt.phase == PttPhase.Keying -> stringResource(R.string.wb_ptt_starting) to OnAirRed
        onAir -> stringResource(R.string.wb_ptt_on_air_elsewhere) to OnAirRed
        cannot != null -> cannot to Color(0xFF424242)
        progress > 0f -> stringResource(R.string.wb_ptt_keep_holding) to Color(0xFF6D4C41)
        force -> stringResource(if (ptt.check?.kind == "busy") R.string.wb_ptt_force_busy else R.string.wb_ptt_force_unchecked) to Color(0xFF5D4037)
        else -> stringResource(R.string.wb_ptt_hold) to Color(0xFF5D4037)
    }

    Box(
        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(colour)
            .testTag("ptt")
            .pointerInput(mine, onAir, cannot, force) {
                detectTapGestures(onPress = {
                    when {
                        // Off air is one tap, whoever keyed and whatever state.
                        mine || onAir -> {
                            if (tryAwaitRelease()) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                s.ptt.unkey()
                            }
                        }
                        cannot != null -> {
                            tryAwaitRelease()
                            if (status?.auth?.platform == true) s.say(s.txLevelReason(status).ifEmpty { s.viewOnlyReason(status) })
                            else if (status?.auth?.viewOnly == true) s.say(status.auth.viewOnlyReason)
                        }
                        else -> {
                            val start = SystemClock.elapsedRealtime()
                            val job = scope.launch {
                                while (true) {
                                    val f = (SystemClock.elapsedRealtime() - start).toFloat() / HOLD_TO_KEY_MS
                                    if (f >= 1f) break
                                    progress = f
                                    delay(16)
                                }
                                progress = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                s.ptt.key(force)
                            }
                            tryAwaitRelease()
                            if (job.isActive) job.cancel()
                            progress = 0f
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (progress > 0f) {
            Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress).background(OnAirRed))
        }
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp))
    }
}
