package com.openhamshack.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openhamshack.core.tv.RemoteKeysHost
import com.openhamshack.core.tv.remoteKeys
import com.openhamshack.remote.R
import com.openhamshack.remote.ptt.PttPhase
import com.openhamshack.remote.shack.Bookmarks
import com.openhamshack.remote.shack.DecoderPanel
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.EngineUi
import com.openhamshack.remote.shack.ModeAudio
import com.openhamshack.remote.shack.PanelPrefs
import com.openhamshack.remote.shack.ScopeLabels
import com.openhamshack.remote.shack.Span

/**
 * Radio on a phone or tablet, arranged as Flex's screen (Brian, 13/10/2026:
 * "Can we make the radio app look more like the flex app?"): a header row
 * of status chips with Scope, Controls and Back at its right; the frequency
 * big, its mode and band under it, the meter beside it; the waterfall the
 * whole width, Radio's band plan between its spectrum and its waterfall; and
 * at the foot the band buttons and one compact row - tune and step at the
 * left, mute at the right. Everything else is in the Scope and Controls
 * sheets, where the column of buttons beside the scope used to narrow it.
 *
 * The arrangement is copied, not shared: FlexPhone belongs to another
 * session's work and must not move under it. NB keeps its own screen
 * (PhoneEngine), whose 50/50 Band Activity was made for it; Radio keeps that
 * split too while a slotted decoder runs on a tablet held sideways.
 *
 * Receive is what this screen adds; the transmit pieces (Enable TX, the
 * PTT hold) are PhoneEngine's own, unchanged, and the band buttons never key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RadioPhone(e: EngineSession, onBack: () -> Unit, onRepair: () -> Unit) {
    val u by e.ui.collectAsStateWithLifecycle()
    val pttState = e.ptt?.state?.collectAsStateWithLifecycle()
    if (keepAwake(u.audioRate, u.muted, keyed = pttState != null && pttState.value.phase != PttPhase.Idle)) KeepScreenOn()
    var typing by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var scopeSheet by remember { mutableStateOf(false) }
    var controlsSheet by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 840
    val landscape = config.screenWidthDp > config.screenHeightDp
    // Bigger buttons and plan words on a tablet, read at arm's length or across a table.
    val tablet = config.smallestScreenWidthDp >= 600
    val tx = rememberPhoneEnableTx(e)
    val range = scopeRange(e)
    val marks = remember(u.bookmarks, range, u.state.dialHz == null) {
        Bookmarks.inView(u.bookmarks, range?.first, range?.second, u.state.dialHz)
    }
    val named = marks.getOrNull(ScopeLabels.named(marks, u.dialHz, null, range?.first, range?.second))
    val plan = rememberPlanStrip(e, u)

    if (scopeSheet) ModalBottomSheet(onDismissRequest = { scopeSheet = false }) {
        RadioScopeSheet(e, u, marks, onDone = { scopeSheet = false })
    }
    if (controlsSheet) ModalBottomSheet(onDismissRequest = { controlsSheet = false }) {
        RadioControlsSheet(e, u, onType = { controlsSheet = false; typing = true }, onLog = { controlsSheet = false; logOpen = true },
            onDone = { controlsSheet = false })
    }

    Box(Modifier.fillMaxSize().remoteKeys(tx.keys)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { RadioChips(e, u) }
                TextButton(onClick = { scopeSheet = true }, modifier = Modifier.testTag("scopeSheet")) {
                    Text(stringResource(R.string.shack_scope_sheet), softWrap = false)
                }
                TextButton(onClick = { controlsSheet = true }, modifier = Modifier.testTag("controlsSheet")) {
                    Text(stringResource(R.string.shack_controls_sheet), softWrap = false)
                }
                TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(stringResource(R.string.shack_back), softWrap = false) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioDial(u, Modifier.weight(1f), maxSize = if (tablet) 56 else 44, onType = { typing = true }, onMode = { controlsSheet = true })
                SignalMeter(u.rxDb, if (tablet) 160.dp else 100.dp, big = tablet)
            }
            u.problem?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(problemText(p), color = OnAirRed, modifier = Modifier.weight(1f))
                    if (p.http == 401) TextButton(onClick = onRepair) { Text(stringResource(R.string.shack_signin_go)) }
                }
            }
            u.toast?.let { Text(it, color = Dim, maxLines = 1) }
            val scopeBox = @Composable { m: Modifier ->
                Box(m.clip(RoundedCornerShape(6.dp)).pointerInput(Unit) {
                    detectTapGestures(onLongPress = { scopeSheet = true }) { pos ->
                        val sc = e.scopeData
                        if (sc.endHz > sc.startHz) {
                            val hz = sc.startHz + (sc.endHz - sc.startHz) * (pos.x / size.width)
                            // A tap is on a signal: the dial goes where the mode hears it (ModeAudio).
                            e.setDial(ModeAudio.dialFor(hz, ModeAudio.of(e.ui.value.state)))
                        }
                    }
                }.testTag("scope")) {
                    ScopeView(e, u.dialHz, Modifier.fillMaxSize(), marks = marks, named = named, passband = ModeAudio.of(u.state),
                        labelSize = if (tablet) 16.sp else 13.sp,
                        plan = plan, stripHeight = if (tablet) 22.dp else 16.dp, stripText = if (tablet) 13.sp else 10.sp)
                }
            }
            val bandActivity = wide && u.panel.shown == DecoderPanel.SLOTS
            when {
                bandActivity && landscape -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    scopeBox(Modifier.weight(1f).fillMaxHeight())
                    PhonePanel(e, onPick = tx.onPick, fill = true, modifier = Modifier.weight(1.15f).fillMaxHeight())
                }
                bandActivity -> {
                    scopeBox(Modifier.weight(0.8f).fillMaxWidth())
                    PhonePanel(e, onPick = tx.onPick, fill = true, modifier = Modifier.weight(1.2f).fillMaxWidth())
                }
                else -> {
                    scopeBox(Modifier.weight(1f).heightIn(min = 140.dp).fillMaxWidth())
                    PhonePanel(e, onPick = tx.onPick)
                }
            }
            BandButtons(e, u, height = if (tablet) 56.dp else 48.dp, textSize = if (tablet) 22.sp else 18.sp, modifier = Modifier.fillMaxWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { e.tune(-1) }, modifier = Modifier.testTag("tuneDown")) { Text("◀") }
                // The step had one button, which only ever went up: down was
                // unreachable (Brian, Tab S9, 13/10/2026). Down and up either
                // side of what the step is now.
                OutlinedButton(onClick = { e.step(-1) }, modifier = Modifier.testTag("stepDown")) { Text("\u2212") }
                Text(stringResource(R.string.shack_step, stepText(u.stepHz)), maxLines = 1, modifier = Modifier.testTag("step"))
                OutlinedButton(onClick = { e.step(1) }, modifier = Modifier.testTag("stepUp")) { Text("+") }
                OutlinedButton(onClick = { e.tune(1) }, modifier = Modifier.testTag("tuneUp")) { Text("▶") }
                OutlinedButton(onClick = { e.mute(!u.muted) }, modifier = Modifier.testTag("mute")) {
                    Text(stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute))
                }
            }
            PhoneEnableTx(e, tx)
            if (e.ptt != null) EnginePtt(e)
        }
        RemoteKeysHost(tx.keys, tx.face, tx.onLines())
    }
    if (typing) FrequencyDialog(onTune = { hz -> e.setDial(hz); typing = false }, onDismiss = { typing = false })
    if (logOpen) PhoneLog(e, onClose = { logOpen = false })
}

/** Only what is unusual, as Flex's header: on the air, NR on, muted, no audio, the simulator, reconnecting. */
@Composable
private fun RadioChips(e: EngineSession, u: EngineUi) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically) {
        if (u.status?.onAir == true) Chip(stringResource(R.string.shack_on_air_short), OnAirRed)
        if (!u.connected) Chip(stringResource(if (u.state.dialHz == null) R.string.shack_connecting else R.string.shack_reconnecting), Amber)
        if (u.state.nr == true) Chip(stringResource(R.string.shack_nr_chip), Accent)
        if (u.muted) Chip(stringResource(R.string.shack_muted), Amber)
        else if (u.audioRate <= 0 && u.connected) Chip(stringResource(R.string.shack_no_audio), Dim)
        if (u.status?.simulator == true) Chip(stringResource(R.string.shack_simulator), Amber)
    }
}

/**
 * The frequency, as big as fits (a tap types one); the mode (a tap opens
 * the modes), the band and the plan's segment under it. Accent while a tune
 * is on its way, as on the NB screen.
 */
@Composable
private fun RadioDial(u: EngineUi, modifier: Modifier, maxSize: Int, onType: () -> Unit, onMode: () -> Unit) {
    Column(modifier) {
        BasicText(
            dialText(u.dialHz),
            style = TextStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = if (u.pendingDialHz != null) Accent else Color.White),
            maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = maxSize.sp, stepSize = 2.sp),
            modifier = Modifier.clickable(onClick = onType).testTag("dial"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(u.state.mode.uppercase().ifEmpty { "–" }, fontSize = (maxSize / 3).sp, fontWeight = FontWeight.Bold, color = Accent,
                modifier = Modifier.clickable(onClick = onMode).padding(end = 4.dp).testTag("mode"))
            Text(if (u.state.band.isBlank()) stringResource(R.string.shack_band_none) else stringResource(R.string.shack_band, u.state.band),
                fontSize = (maxSize / 4).sp, color = Dim, maxLines = 1)
            if (u.state.segment.isNotBlank()) Text(u.state.segment, fontSize = (maxSize / 4).sp, color = Dim, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

/** The Scope sheet: span, and the bookmarks on the scope (a tap goes there with its mode). */
@Composable
private fun RadioScopeSheet(e: EngineSession, u: EngineUi, marks: List<com.openhamshack.remote.shack.Bookmark>, onDone: () -> Unit) {
    val current = Bookmarks.current(marks, u.state.dialHz, u.state.mode)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = u.span == Span.WIDE, onClick = { e.span(Span.WIDE) }, label = { Text(stringResource(R.string.shack_span_wide)) })
            FilterChip(selected = u.span == Span.ZOOM, onClick = { e.span(Span.ZOOM) }, label = { Text(stringResource(R.string.shack_span_zoom)) })
        }
        if (marks.isNotEmpty()) {
            Text(stringResource(R.string.shack_bookmarks), color = Dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                marks.forEach { b ->
                    FilterChip(selected = b == current, onClick = { e.goTo(b); onDone() }, enabled = !u.viewOnly,
                        label = { Text(b.name) }, modifier = Modifier.testTag("mark-${b.name}"))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** The Controls sheet: the modes, then noise reduction, a typed frequency, the log and the decoder panel's switches. */
@Composable
private fun RadioControlsSheet(e: EngineSession, u: EngineUi, onType: () -> Unit, onLog: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (u.modes.isNotEmpty()) {
            Text(stringResource(R.string.shack_modes), color = Dim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                u.modes.forEach { m ->
                    FilterChip(selected = m == u.state.mode, onClick = { e.setMode(m); onDone() }, label = { Text(m.uppercase()) },
                        enabled = !u.viewOnly, modifier = Modifier.testTag("mode-$m"))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (u.hasNr) FilledTonalButton(onClick = { e.nr(u.state.nr != true) }, enabled = !u.viewOnly,
                modifier = Modifier.testTag("nr")) { Text(nrLabel(u.state.nr)) }
            FilledTonalButton(onClick = onType) { Text(stringResource(R.string.shack_frequency)) }
            FilledTonalButton(onClick = onLog, modifier = Modifier.testTag("log")) { Text(stringResource(R.string.shack_log)) }
            if (u.panel.active != DecoderPanel.NONE) {
                FilledTonalButton(onClick = { if (u.panel.shown == DecoderPanel.NONE) e.openPanel() else e.closePanel() },
                    modifier = Modifier.testTag("panelShow")) {
                    Text(stringResource(if (u.panel.shown == DecoderPanel.NONE) R.string.shack_panel_show else R.string.shack_panel_hide))
                }
            }
            FilledTonalButton(onClick = { val on = !u.autoOpen; PanelPrefs.setAutoOpen(context, on); e.setAutoOpen(on) },
                modifier = Modifier.testTag("panelAuto")) {
                Text(stringResource(if (u.autoOpen) R.string.shack_panel_auto_on else R.string.shack_panel_auto_off))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
