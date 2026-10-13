package com.openhamshack.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.R
import com.openhamshack.remote.api.Status
import kotlin.math.abs
import kotlin.math.roundToLong

/** The frequency nudge's step sizes: a DATV channel's raster, a quarter-MHz, a whole MHz. */
private val NUDGE_STEPS = listOf(0.025, 0.25, 1.0)

/** Which number the on-screen keypad is asking for. */
private enum class Ask { Pin, Frequency, Rate }

/**
 * Receive tuning with a D-pad: the tablet's TunePanel (same presets, the
 * same symbol-rate chips by band, the same LNB rule for a typed frequency,
 * the same POST /api/rx/tune and /api/rx/select through BoxSession) laid
 * out as rows a remote can walk. A panel at the side, so the picture stays
 * in view while it changes.
 *
 * The frequency can be typed on the on-screen keypad, or nudged with
 * left/right in a chosen step and sent with OK - one tune for the value
 * arrived at, not one per press, so the box's tuner is not dragged through
 * every step on the way (each retune costs it a lock).
 *
 * Without the PIN the panel still shows everything, dimmed, and offers
 * the PIN (a tap on a dimmed control says why, as on the tablet). Still
 * receive only: nothing here can transmit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TvTunePanel(s: BoxSession, status: Status?, modifier: Modifier, onClose: () -> Unit, onQo100: () -> Unit) {
    var tuneId by remember { mutableIntStateOf(status?.receivers?.firstOrNull { it.heard }?.id ?: 1) }
    var stepIdx by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Double?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    val first = remember { FocusRequester() }

    // Back drops a nudged frequency not yet sent, then closes.
    BackHandler { if (pending != null) pending = null else onClose() }
    // Again when the PIN is accepted: the Enter PIN button that held focus
    // has gone, and focus must land somewhere a remote can see.
    LaunchedEffect(status?.auth?.viewOnly) {
        delay(50)
        runCatching { first.requestFocus() }
    }

    Column(
        modifier.fillMaxHeight().width(660.dp).background(Color(0xEB101010))
            .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (status == null) {
            Text(stringResource(R.string.wb_tv_waiting_box), fontSize = 20.sp)
            TvItem(onClick = onClose, focus = first) { Text(stringResource(R.string.wb_close), fontSize = 18.sp) }
            return@Column
        }
        val r = status.receivers.firstOrNull { it.id == tuneId } ?: status.rx
        val viewOnly = status.auth.viewOnly
        val qoLo = status.config.qo100LnbMHz
        val pinFirst = viewOnly && status.auth.pinSet
        // Dimmed, not disabled: OK on a dimmed control still says why (the
        // box's own refusal, through BoxSession.act), as the tablet does.
        val look = if (viewOnly) Modifier.alpha(0.5f) else Modifier

        Text(if (status.receivers.size > 1) stringResource(R.string.wb_tv_tune_receiver, r.id) else stringResource(R.string.wb_tune), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(tuningText(r), fontSize = 22.sp)
        Text(lockText(r), color = when (r.locked) { true -> LockGreen; false -> Amber; null -> Dim }, fontSize = 18.sp)

        if (viewOnly && status.auth.platform) {
            Text(s.viewOnlyReason(status), color = Amber, fontSize = 16.sp)
        } else if (viewOnly) {
            Text(
                stringResource(if (status.auth.pinSet) R.string.wb_tv_tune_view_only_pin else R.string.wb_tv_tune_view_only_no_pin),
                color = Amber, fontSize = 16.sp,
            )
            if (status.auth.pinSet) TvItem(onClick = { ask = Ask.Pin }, focus = first) { Text(stringResource(R.string.signin_enter_pin), fontSize = 18.sp) }
        }

        if (status.receivers.size > 1) {
            TvSection(stringResource(R.string.wb_tv_section_receiver))
            FlowRow(look, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                status.receivers.filter { it.enabled }.forEach { x ->
                    TvItem(onClick = { tuneId = x.id; pending = null }, selected = x.id == tuneId) {
                        Text("${x.id} · ${x.tuner}" + if (x.heard) "  ♪" else "", fontSize = 16.sp)
                    }
                }
            }
            if (!r.heard) {
                TvItem(onClick = { s.hear(r.id) }, modifier = look) { Text(stringResource(R.string.wb_hear_receiver, r.id), fontSize = 16.sp) }
            }
        }

        TvSection(stringResource(R.string.wb_tv_section_band))
        FlowRow(look, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RX_PRESETS.forEachIndexed { i, p ->
                TvItem(
                    onClick = {
                        pending = null
                        s.tune(r.id, frequencyMHz = p.mhz, offsetMHz = 0.0)
                    },
                    focus = if (i == 0 && !pinFirst) first else null,
                    selected = r.frequencyMHz?.let { abs(it - p.mhz) < 0.001 } == true,
                ) { Text(p.label, fontSize = 16.sp) }
            }
            // Opens view only too: watching the transponder needs no PIN.
            TvItem(onClick = onQo100, selected = onQo100(r)) { Text(stringResource(R.string.wb_tv_qo100), fontSize = 16.sp) }
        }

        TvSection(stringResource(R.string.wb_tv_section_frequency))
        val step = NUDGE_STEPS[stepIdx]
        val shown = pending ?: r.frequencyMHz
        TvItem(
            onClick = {
                pending?.let { n ->
                    val off = rxOffsetFor(n, r.offsetMHz, qoLo)
                    s.tune(r.id, frequencyMHz = n, offsetMHz = off,
                        symbolRateKs = rateForFrequency(n, off, r.symbolRateKs)?.toDouble())
                    pending = null
                }
            },
            modifier = look.fillMaxWidth().onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val by = when (e.key) {
                    Key.DirectionLeft -> -step
                    Key.DirectionRight -> step
                    else -> return@onKeyEvent false
                }
                // Held, the key repeats and the number runs on, as a TV's
                // own channel tuning does. Rounded to the kHz so 0.025
                // steps never collect a floating-point tail.
                val from = pending ?: r.frequencyMHz ?: return@onKeyEvent true
                pending = ((from + by) * 1000).roundToLong().coerceAtLeast(1) / 1000.0
                true
            },
        ) {
            Text("◀", color = Dim, fontSize = 22.sp)
            Text(
                stringResource(R.string.wb_mhz, mhz(shown)), color = if (pending != null) Amber else Color.White, fontSize = 28.sp,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Text("▶", color = Dim, fontSize = 22.sp)
        }
        Text(
            pending?.let { stringResource(R.string.wb_tv_nudge_pending, mhz(it)) }
                ?: stringResource(R.string.wb_tv_nudge_hint, stepWords(step)),
            color = Dim, fontSize = 15.sp,
        )
        FlowRow(look, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NUDGE_STEPS.forEachIndexed { i, st ->
                TvItem(onClick = { stepIdx = i }, selected = i == stepIdx) { Text(stringResource(R.string.wb_tv_step, stepWords(st)), fontSize = 16.sp) }
            }
            TvItem(onClick = { ask = Ask.Frequency }) { Text(stringResource(R.string.wb_tv_type_frequency), fontSize = 16.sp) }
        }

        val rates = srRatesFor(r.offsetMHz)
        TvSection(stringResource(if ((r.offsetMHz ?: 0.0) > 0) R.string.wb_tv_section_rate_qo100 else R.string.wb_tv_section_rate_terrestrial))
        FlowRow(look, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = r.symbolRateKs?.toInt()
            (if (current != null && current !in rates) (rates + current).sorted() else rates).forEach { sr ->
                TvItem(onClick = { s.tune(r.id, symbolRateKs = sr.toDouble()) }, selected = sr == current) {
                    Text("$sr", fontSize = 16.sp)
                }
            }
            TvItem(onClick = { ask = Ask.Rate }) { Text(stringResource(R.string.wb_tv_other), fontSize = 16.sp) }
        }

        TvItem(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.wb_close), fontSize = 18.sp) }
    }

    // Said from the dialogs' callbacks, where stringResource cannot be called.
    val typeFrequency = stringResource(R.string.wb_rx_type_frequency)
    val typeRate = stringResource(R.string.wb_type_rate)
    when (ask) {
        Ask.Pin -> NumberDialog(
            stringResource(R.string.signin_title), stringResource(R.string.wb_tv_pin_hint),
            KeyboardType.NumberPassword, secret = true, onDismiss = { ask = null },
        ) { pin, fail ->
            s.login(pin) { err -> if (err == null) ask = null else fail(err) }
        }
        Ask.Frequency -> NumberDialog(
            stringResource(R.string.wb_tv_frequency_title), stringResource(R.string.wb_tv_frequency_help),
            KeyboardType.Decimal, onDismiss = { ask = null },
        ) { text, fail ->
            val r = status?.receivers?.firstOrNull { it.id == tuneId } ?: status?.rx
            val n = text.replace(',', '.').toDoubleOrNull()
            if (n == null || n <= 0 || r == null) fail(typeFrequency)
            else {
                val off = rxOffsetFor(n, r.offsetMHz, status?.config?.qo100LnbMHz ?: 9750.0)
                s.tune(r.id, frequencyMHz = n, offsetMHz = off,
                    symbolRateKs = rateForFrequency(n, off, r.symbolRateKs)?.toDouble())
                pending = null
                ask = null
            }
        }
        Ask.Rate -> NumberDialog(
            stringResource(R.string.wb_tv_rate_title), stringResource(R.string.wb_tv_rate_help), KeyboardType.Number, onDismiss = { ask = null },
        ) { text, fail ->
            val n = text.toDoubleOrNull()
            if (n == null || n <= 0) fail(typeRate)
            else {
                s.tune(tuneId, symbolRateKs = n)
                ask = null
            }
        }
        null -> {}
    }
}

@Composable
private fun stepWords(step: Double) =
    stringResource(R.string.wb_mhz, if (step < 1) mhz(step).trimEnd('0') else step.toInt().toString())

/**
 * One number on the TV's on-screen keypad: the field takes focus as the
 * dialog opens, which brings the keypad up, and the keypad's Done sends it.
 * [onOk] gets the text and a way to say why not, leaving the dialog open.
 */
@Composable
internal fun NumberDialog(
    title: String,
    help: String,
    type: KeyboardType,
    secret: Boolean = false,
    onDismiss: () -> Unit,
    onOk: (String, (String) -> Unit) -> Unit,
) {
    var text by remember { mutableStateOf("") }
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
            Column(Modifier.width(480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(help, color = Dim, fontSize = 15.sp)
                OutlinedTextField(
                    text, { text = it; error = null }, singleLine = true, isError = error != null,
                    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
                error?.let { Text(it, color = OnAirRed) }
            }
        },
        confirmButton = { TvItem(onClick = go) { Text(stringResource(R.string.wb_ok), fontSize = 16.sp) } },
        dismissButton = { TvItem(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), fontSize = 16.sp) } },
    )
}
