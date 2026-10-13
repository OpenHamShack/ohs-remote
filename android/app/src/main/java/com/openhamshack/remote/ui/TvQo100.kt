package com.openhamshack.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.R
import com.openhamshack.remote.api.Qo100Signal
import com.openhamshack.remote.api.Status
import java.util.Locale

/**
 * QO-100 on a TV: the transponder across the top (spectrum, the signals
 * the box found boxed and labelled, the receiver's frequency in green),
 * and the signals beneath as big rows a remote walks - up/down moves, the
 * focused signal is ringed on the spectrum, OK tunes it with the console's
 * click-to-tune rules (ui/Qo100Tune.kt), the same as the tablet. The
 * picture shows through on the right, so a tune is seen to lock.
 *
 * No waterfall here: at a sofa's distance its one-second rows read as
 * noise, and the space is better spent on the spectrum and the list.
 * Without the PIN the screen still shows everything; OK on a signal says
 * why it cannot tune, and the PIN can be entered here.
 */
@Composable
internal fun TvQo100(s: BoxSession, status: Status?, onClose: () -> Unit) {
    val qo by s.qo100.collectAsStateWithLifecycle()
    // The focused row, by its signal's key: the list is a new one every
    // second (levels move), so the object itself never stays equal.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    var askPin by remember { mutableStateOf(false) }
    val closeItem = remember { FocusRequester() }

    // Polled only while this screen shows: that poll holds the box's BATC feed open.
    DisposableEffect(s) {
        s.setQo100Shown(true)
        onDispose { s.setQo100Shown(false) }
    }
    BackHandler(onBack = onClose)

    val signals = qo?.signals.orEmpty()
    val heard = status?.receivers?.firstOrNull { it.heard } ?: status?.rx
    val viewOnly = status?.auth?.viewOnly ?: true
    val qoLo = status?.config?.qo100LnbMHz ?: 9750.0
    // Focus to the first signal once there is one (the list arrives a
    // second after the screen), else to Close so a key is never lost.
    val hasSignals = signals.isNotEmpty()
    // Again when the PIN is accepted: the Enter PIN button that held focus
    // has gone, and the next OK would otherwise go nowhere.
    LaunchedEffect(hasSignals, viewOnly) {
        delay(80)
        runCatching { (signals.firstOrNull()?.let { rowFocus.getOrPut(qo100Key(it)) { FocusRequester() } } ?: closeItem).requestFocus() }
    }
    // A station that keys off takes its row with it, and the focus too -
    // the next OK then went nowhere. Move to the nearest signal still there.
    val keys = signals.map { qo100Key(it) }
    LaunchedEffect(keys) {
        val gone = focusedKey ?: return@LaunchedEffect
        if (gone in keys) return@LaunchedEffect
        val f = gone.substringBefore('/').toDoubleOrNull() ?: return@LaunchedEffect
        val near = signals.minByOrNull { kotlin.math.abs(it.freqMHz - f) }
        delay(50)
        runCatching { (near?.let { rowFocus[qo100Key(it)] } ?: closeItem).requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        // The transponder, across the top, opaque.
        Column(
            Modifier.fillMaxWidth().background(Color(0xF0080C18))
                .padding(start = TvSafeH, end = TvSafeH, top = TvSafeV, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(stringResource(R.string.wb_tv_qo100_title), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(16.dp))
                val err = qo != null && qo!!.error.isNotBlank() && !qo!!.live
                Text(
                    qo100StateText(
                        qo?.let { qo100State(it.live, it.error, it.age, signals.size, it.outOfBand, true) }
                            ?: qo100State(false, "", null, 0, false, false),
                    ),
                    color = if (err) Amber else Dim, fontSize = 15.sp, maxLines = 1, modifier = Modifier.weight(1f),
                )
            }
            Qo100Spectrum(
                qo, qo100RxMarker(heard), Modifier.fillMaxWidth().height(165.dp),
                focused = signals.firstOrNull { qo100Key(it) == focusedKey }, labelSize = 11f,
            )
            Text(qo100Credit(qo, tv = true), color = Dim, fontSize = 12.sp)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.width(470.dp).fillMaxHeight().background(Color(0xE0101010))
                    .padding(start = TvSafeH, end = 16.dp, top = 10.dp, bottom = TvSafeV),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (signals.isEmpty()) {
                        item {
                            Text(stringResource(if (qo?.live == true) R.string.wb_qo100_nothing_found else R.string.wb_qo100_waiting),
                                color = Dim, fontSize = 16.sp)
                        }
                    }
                    items(signals, key = { qo100Key(it) }) { sig ->
                        val key = qo100Key(sig)
                        val on = qo100TunedOn(sig, status?.receivers.orEmpty()).isNotEmpty()
                        TvItem(
                            onClick = { heard?.let { s.tune(qo100Tune(it, sig, qoLo)) } },
                            focus = rowFocus.getOrPut(key) { FocusRequester() },
                            selected = on,
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusedKey = key },
                        ) {
                            // One line a signal, so five or six fit under the spectrum.
                            val f = downlinkText(sig.freqMHz, localeTag())
                            Text(if (sig.beacon) stringResource(R.string.qo100_beacon_at, f) else f, fontSize = 19.sp,
                                fontWeight = FontWeight.Bold, color = if (sig.overPower) OnAirRed else Color.White,
                                modifier = Modifier.weight(1f))
                            Text(
                                listOfNotNull(stringResource(R.string.wb_qo100_rate, sig.tuneSymbolRateKs),
                                    stringResource(R.string.wb_db, db(sig.levelDb)),
                                    if (sig.overPower) stringResource(R.string.wb_tv_qo100_over) else null).joinToString(" · "),
                                color = Dim, fontSize = 15.sp, modifier = Modifier.padding(end = 12.dp),
                            )
                            Text(stringResource(if (on) R.string.wb_tv_qo100_on else R.string.wb_tv_qo100_ok), color = if (on) LockGreen else Dim, fontSize = 15.sp)
                        }
                    }
                }
                if (viewOnly && status?.auth?.platform == true) {
                    Text(s.viewOnlyReason(status), color = Amber, fontSize = 14.sp)
                } else if (viewOnly) {
                    Text(
                        stringResource(if (status?.auth?.pinSet != false) R.string.wb_tv_qo100_view_only_pin
                            else R.string.wb_tv_qo100_view_only_no_pin),
                        color = Amber, fontSize = 14.sp,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (viewOnly && status?.auth?.platform != true && status?.auth?.pinSet != false) {
                        TvItem(onClick = { askPin = true }) { Text(stringResource(R.string.signin_enter_pin), fontSize = 16.sp) }
                    }
                    TvItem(onClick = onClose, focus = closeItem) { Text(stringResource(R.string.wb_close), fontSize = 16.sp) }
                }
            }
            // The picture shows through here; what it is tuned to, at its foot.
            Box(Modifier.weight(1f).fillMaxHeight().padding(end = TvSafeH, bottom = TvSafeV, start = 16.dp)) {
                if (heard != null) {
                    Column(
                        Modifier.align(Alignment.BottomStart).background(Color(0xB0000000), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(tuningText(heard), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(lockText(heard), color = when (heard.locked) { true -> LockGreen; false -> Amber; null -> Dim }, fontSize = 15.sp)
                    }
                }
            }
        }
    }

    if (askPin) {
        NumberDialog(
            stringResource(R.string.signin_title), stringResource(R.string.wb_tv_pin_hint),
            KeyboardType.NumberPassword, secret = true, onDismiss = { askPin = false },
        ) { pin, fail -> s.login(pin) { err -> if (err == null) askPin = false else fail(err) } }
    }
}

/** A signal's identity from one answer to the next: its frequency, and beacon or not. */
private fun qo100Key(s: Qo100Signal) = String.format(Locale.ROOT, "%.4f/%b", s.freqMHz, s.beacon)
