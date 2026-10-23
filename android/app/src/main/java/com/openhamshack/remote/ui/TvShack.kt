package com.openhamshack.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.remote.AppViewModel
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.Kind
import com.openhamshack.remote.shack.PairEnd
import com.openhamshack.remote.shack.Scope
import com.openhamshack.remote.shack.ShackScreen
import com.openhamshack.remote.shack.Span
import com.openhamshack.kind.stationflow.ui.StationFlowTvScreen

/**
 * A controller on a TV: pairing with a code, its apps, and their kinds
 * (NB, Radio, QO-100 WB, DATV, Icom, Flex), receive only. TvUi hands over here whenever the
 * controller navigation has a screen.
 */
@Composable
fun TvShack(vm: AppViewModel) {
    val screen by vm.shack.screen.collectAsStateWithLifecycle()
    val engine by vm.shack.engine.collectAsStateWithLifecycle()
    val wb by vm.shack.wb.collectAsStateWithLifecycle()
    val datv by vm.shack.datv.collectAsStateWithLifecycle()
    val icom by vm.shack.icom.collectAsStateWithLifecycle()
    val flex by vm.shack.flex.collectAsStateWithLifecycle()
    val e = engine
    val w = wb
    val d = datv
    val i = icom
    val f = flex
    val reconnecting by vm.shack.reconnecting.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
    when {
        e != null -> TvEngine(e, onBack = { vm.shack.closeApp() }, onRepair = { vm.shack.tokenRefused() })
        // The Icom kind's own TV screen, receive only; "sign in" there is pairing again.
        i != null -> StationFlowTvScreen(i, onExit = { vm.shack.closeApp() }, onSignIn = { vm.shack.tokenRefused() })
        f != null -> TvFlex(f, onBack = { vm.shack.closeApp() })
        // The Classic box's TV screen over the app's base: receive only, as on a Classic box.
        w != null -> TvWatch(w, vm, onBack = { vm.shack.closeApp() })
        d != null -> TvDatv(d, onBack = { vm.shack.closeApp() })
        else -> when (val s = screen) {
            is ShackScreen.Pairing -> TvPairing(s, vm)
            is ShackScreen.Apps -> TvApps(s, vm)
            // A TV never signs in with a password (CONTROLLER-CONTRACT 4.2
            // refuses deviceKind tv); ShackNav never sends one here.
            is ShackScreen.SignIn, null -> Unit
        }
    }
    // An app refused the token and the controller is being asked whether
    // it still stands (TokenGuard): a passing state, said over whatever is
    // on screen rather than replacing it.
    if (reconnecting) ReconnectingChip(Modifier.align(Alignment.TopEnd).padding(top = 24.dp, end = 48.dp), big = true)
    }
}

@Composable
internal fun ReconnectingChip(modifier: Modifier, big: Boolean) {
    Text(stringResource(R.string.shack_reconnecting), color = Amber, fontSize = if (big) 20.sp else 14.sp,
        modifier = modifier.background(Color(0xE0101010), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
internal fun problemText(p: com.openhamshack.remote.shack.Problem): String = when {
    p.text.isNotBlank() -> p.text
    p.http == 401 -> stringResource(R.string.shack_problem_signin)
    p.http != null -> stringResource(R.string.shack_problem_http, p.http)
    else -> stringResource(R.string.shack_problem_network)
}

@Composable
private fun TvPairing(s: ShackScreen.Pairing, vm: AppViewModel) {
    BackHandler { vm.closeController() }
    val first = remember { FocusRequester() }
    LaunchedEffect(s.ended, s.problem) { delay(100); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = TvSafeH, vertical = TvSafeV), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.shack_pair_title, s.box.title), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (s.code == null && s.problem == null) Text(stringResource(R.string.shack_pair_asking), color = Dim, fontSize = 18.sp)
        s.code?.let { code ->
            Text(stringResource(R.string.shack_pair_how), color = Dim, fontSize = 18.sp)
            Text(code.chunked(3).joinToString(" "), fontSize = 96.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = if (s.ended == null) TvFocus else Dim)
        }
        when (s.ended) {
            PairEnd.DENIED -> Text(stringResource(R.string.shack_pair_denied), color = OnAirRed, fontSize = 18.sp)
            PairEnd.EXPIRED -> Text(stringResource(R.string.shack_pair_expired), color = Amber, fontSize = 18.sp)
            null -> if (s.code != null) Text(stringResource(R.string.shack_pair_waiting), color = Dim, fontSize = 18.sp)
        }
        s.problem?.let { Text(problemText(it), color = OnAirRed, fontSize = 18.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s.ended != null || s.problem != null) {
                TvItem(onClick = { vm.shack.retryPairing() }, focus = first) { Text(stringResource(R.string.shack_pair_again), fontSize = 18.sp) }
                TvItem(onClick = { vm.closeController() }) { Text(stringResource(R.string.shack_back), fontSize = 18.sp) }
            } else {
                TvItem(onClick = { vm.closeController() }, focus = first) { Text(stringResource(R.string.shack_back), fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun TvApps(s: ShackScreen.Apps, vm: AppViewModel) {
    BackHandler { vm.closeController() }
    val first = remember { FocusRequester() }
    val apps = s.wk?.apps.orEmpty()
    LaunchedEffect(s.wk, s.problem) { delay(100); runCatching { first.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = TvSafeH, vertical = TvSafeV), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_apps_title, s.wk?.name ?: s.box.title), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (s.wk == null && s.problem == null) Text(stringResource(R.string.shack_apps_loading), color = Dim, fontSize = 18.sp)
        s.problem?.let { p ->
            Text(problemText(p), color = OnAirRed, fontSize = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (p.http == 401) TvItem(onClick = { vm.shack.tokenRefused() }, focus = first) { Text(stringResource(R.string.shack_pair_again_signin), fontSize = 18.sp) }
                else TvItem(onClick = { vm.shack.refreshApps() }, focus = first) { Text(stringResource(R.string.shack_retry), fontSize = 18.sp) }
                TvItem(onClick = { vm.closeController() }) { Text(stringResource(R.string.shack_back), fontSize = 18.sp) }
            }
        }
        if (s.wk != null && s.wk.openable.isEmpty()) Text(stringResource(R.string.shack_apps_none), color = Dim, fontSize = 18.sp)
        // Openable apps first, so the first focus lands on one.
        val ordered = apps.sortedBy { if (it.kind.opens) 0 else 1 }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(ordered, key = { it.id }) { a ->
                val ok = a.kind.opens
                TvItem(onClick = { if (ok) vm.shack.openApp(a) }, focus = if (a == ordered.first() && s.problem == null) first else null,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontSize = 22.sp, color = if (ok) Color.Unspecified else Dim)
                        Text(listOfNotNull(a.wireKind, if (!a.running) stringResource(R.string.shack_app_stopped) else null,
                            if (!ok) stringResource(R.string.shack_app_unsupported) else null).joinToString(" · "), color = Dim, fontSize = 14.sp)
                    }
                    if (ok) Text(stringResource(R.string.shack_open), color = TvFocus, fontSize = 16.sp)
                }
            }
        }
    }
}

// --- the NB / Radio screen -------------------------------------------------

/**
 * The dial as it reads on a radio: MHz from 30 MHz up, kHz below, to 10 Hz
 * either way (the finest step). A negative frequency reads as no frequency:
 * ShackFormat rightly refuses one, and this runs while drawing, where its
 * exception ended the app - a radio once reported -100 Hz after Remote's
 * Flex tuned past zero (Brian's Fire TV, 10/10/2026). Tuning is clamped
 * now, but the readout must survive whatever a radio reports.
 */
@Composable
internal fun dialText(hz: Double?): String = dialText(hz, localeTag())

internal fun dialText(hz: Double?, tag: String): String {
    if (hz == null || hz.isNaN() || hz < 0) return "—"
    val n = Math.round(hz)
    return if (n >= 30_000_000L) com.openhamshack.i18n.ShackFormat.frequency(n, tag, "MHz", 5)
    else com.openhamshack.i18n.ShackFormat.frequency(n, tag, "kHz", 2)
}

@Composable
internal fun stepText(hz: Int): String = stepText(hz, localeTag())

/**
 * A step as the operator knows it: 12.5 kHz to one place, and the airband
 * step (Tuning.AIRBAND_833, 8333) to two, "8.33 kHz", the name every
 * airband radio gives it.
 */
internal fun stepText(hz: Int, tag: String): String = com.openhamshack.i18n.ShackFormat.frequency(hz, tag, if (hz >= 1000) "kHz" else "Hz",
    when { hz < 1000 || hz % 1000 == 0 -> 0; hz % 100 == 0 -> 1; else -> 2 })

@Composable
internal fun Chip(text: String, colour: Color) {
    Text(text, color = colour, fontSize = 14.sp, modifier = Modifier.padding(start = 10.dp)
        .border(1.dp, colour, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** A typed frequency, on the TV's keyboard: the field takes focus and Done tunes. */
@Composable
internal fun FrequencyDialog(onTune: (Double) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    // [initial]: a digit key opened it, and that digit is the first typed (REMOTE-KEYS.md §4).
    var text by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    var bad by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val tag = localeTag()
    LaunchedEffect(Unit) { delay(150); runCatching { field.requestFocus() } }
    val go = {
        val hz = runCatching { com.openhamshack.i18n.ShackFormat.parseFrequency(text.text, tag, "MHz") }.getOrNull()
        if (hz == null || hz <= 0) bad = true else onTune(hz.toDouble())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shack_freq_title)) },
        text = {
            Column(Modifier.width(520.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // The reason goes above the field: under it, the TV keyboard hides it (GUIDE 5).
                if (bad) Text(stringResource(R.string.shack_freq_bad), color = OnAirRed)
                OutlinedTextField(text, { text = it; bad = false }, label = { Text(stringResource(R.string.shack_freq_label)) },
                    singleLine = true, isError = bad,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { go() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field))
            }
        },
        confirmButton = { TvItem(onClick = go) { Text(stringResource(R.string.shack_freq_go), fontSize = 16.sp) } },
        dismissButton = { TvItem(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), fontSize = 16.sp) } },
    )
}

/** One logged contact, the same on TV and phone: the line, then when (the device's own date format). */
@Composable
internal fun QsoText(q: com.openhamshack.remote.shack.Qso, big: Boolean) {
    Column {
        Text(stringResource(R.string.shack_log_row, q.call, dialText(q.freqHz), q.mode, q.rstSent, q.rstRcvd),
            fontSize = if (big) 20.sp else 16.sp, fontFamily = FontFamily.Monospace)
        val tag = localeTag()
        val time = remember(q.t, tag) {
            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT,
                java.util.Locale.forLanguageTag(tag)).format(java.util.Date((q.t * 1000).toLong()))
        }
        Text(listOf(time, q.grid).filter { it.isNotBlank() }.joinToString(" · "), color = Dim, fontSize = if (big) 15.sp else 13.sp)
    }
}

/**
 * The logbook on a TV, read only (logging is done at the station). A
 * full-screen panel over the tuning screen: each contact is focusable so
 * the D-pad scrolls the list, BACK closes it.
 */
@Composable
internal fun TvLog(e: EngineSession, onClose: () -> Unit) {
    val u by e.ui.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { e.loadLog() }
    LaunchedEffect(u.log) { delay(100); runCatching { first.requestFocus() } }
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = TvSafeH, vertical = TvSafeV),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_log_title, e.app.name), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        val log = u.log
        if (log != null && log.isEmpty()) Text(stringResource(R.string.shack_log_empty), color = Dim, fontSize = 18.sp)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(log.orEmpty().take(200), key = { it.t.toString() + it.call }) { q ->
                TvItem(onClick = {}, focus = if (q == log!!.first()) first else null, modifier = Modifier.fillMaxWidth()) { QsoText(q, true) }
            }
        }
        TvItem(onClick = onClose, focus = if (log.isNullOrEmpty()) first else null) { Text(stringResource(R.string.shack_done), fontSize = 16.sp) }
    }
}
