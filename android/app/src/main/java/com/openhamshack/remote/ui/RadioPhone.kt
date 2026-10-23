package com.openhamshack.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.openhamshack.remote.shack.AntennaChoice
import com.openhamshack.remote.shack.Bookmarks
import com.openhamshack.remote.shack.DecoderPanel
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.EngineUi
import com.openhamshack.remote.shack.ModeAudio
import com.openhamshack.remote.shack.PanelPrefs
import com.openhamshack.remote.shack.ScopeLabels
import com.openhamshack.remote.shack.Span
import com.openhamshack.remote.shack.WFM_DEEMPHASIS_US

/**
 * Radio on a phone or tablet, as Brian's mockups (approved 23/10/2026:
 * "Mockups are good"; Main, Bands, Dab and Tablet). Before it the screen
 * was Flex's arrangement (13/10/2026): chips and three buttons in the
 * header, the dial with a meter beside it, a long scrolling strip of band
 * buttons and one row of six small tuning buttons. Now, top to bottom:
 *
 * - the header: Back, the app over the box and its receiver, the unusual
 *   states, and ⋮ (Scope, Controls) - on a tablet held sideways, Scope and
 *   Controls themselves;
 * - one readout block: the frequency big, the mode (▾ the modes), the band
 *   or area the dial is in (RX on a receive-only area; a tap opens the
 *   picker), the antenna (its picker), and the S-meter line;
 * - the scope, passband shaded, tap or drag to tune;
 * - the tuning row: ◀ ▶ that only tune, the step between them with its own
 *   − and +;
 * - "Bands ▾" (a bottom sheet of every band and area in two sections, with
 *   All / Amateur / Listen) and the last three bands used;
 * - Mute and "Hold to transmit", or the amber "Receive only" strip
 *   wherever nothing could be sent (TxSlot).
 *
 * DAB puts its station list where the scope is, and the station's name in
 * the readout. A tablet held sideways (840 dp and wider) keeps the scope and
 * readout on the left and gives tuning, the full band grid and transmit a
 * 380 dp column on the right; upright it is the phone's layout, larger.
 *
 * NB keeps its own screen (PhoneEngine). Receive is what this screen adds;
 * the transmit pieces (Enable TX, the PTT hold) are PhoneEngine's own,
 * unchanged, and no band press ever keys.
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
    var antennaSheet by remember { mutableStateOf(false) }
    var modeSheet by remember { mutableStateOf(false) }
    var bandSheet by remember { mutableStateOf(false) }
    var blockSheet by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    // Bigger everything on a tablet, read at arm's length or across a table.
    val tablet = config.smallestScreenWidthDp >= 600
    val sideways = config.screenWidthDp >= 840 && landscape
    val tx = rememberPhoneEnableTx(e)
    val range = scopeRange(e)
    val marks = remember(u.bookmarks, range, u.state.dialHz == null) {
        Bookmarks.inView(u.bookmarks, range?.first, range?.second, u.state.dialHz)
    }
    val named = marks.getOrNull(ScopeLabels.named(marks, u.dialHz, null, range?.first, range?.second))
    val plan = rememberPlanStrip(e, u)
    val antenna = AntennaChoice.of(u.state)
    val dab = u.dab

    if (scopeSheet) ModalBottomSheet(onDismissRequest = { scopeSheet = false }) {
        RadioScopeSheet(e, u, marks, onDone = { scopeSheet = false })
    }
    if (controlsSheet) ModalBottomSheet(onDismissRequest = { controlsSheet = false }) {
        RadioControlsSheet(e, u, onType = { controlsSheet = false; typing = true }, onLog = { controlsSheet = false; logOpen = true },
            onDone = { controlsSheet = false })
    }
    if (modeSheet) ModalBottomSheet(onDismissRequest = { modeSheet = false }) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeChips(e, u, onDone = { modeSheet = false })
            Spacer(Modifier.height(16.dp))
        }
    }
    if (bandSheet) ModalBottomSheet(onDismissRequest = { bandSheet = false }, containerColor = RadioCard) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            BandPicker(e, u, columns = if (tablet) 6 else 4, onPicked = { bandSheet = false },
                onLocal = { bandSheet = false; e.openLocal() })
        }
    }
    if (u.local != null) ModalBottomSheet(onDismissRequest = { e.closeLocal() }, containerColor = RadioCard) {
        LocalSheet(e, u, onDone = { e.closeLocal() })
    }
    if (blockSheet && dab != null) ModalBottomSheet(onDismissRequest = { blockSheet = false }) {
        DabBlocksSheet(e, u, dab, onDone = { blockSheet = false })
    }
    // The inputs went (the radio is no longer an SDR, or the app was replaced by an older one): nothing to pick from.
    if (antennaSheet && antenna != null) ModalBottomSheet(onDismissRequest = { antennaSheet = false }) {
        RadioAntennaSheet(e, u, antenna, onDone = { antennaSheet = false })
    }

    val readout = @Composable { row: Boolean ->
        if (dab != null) DabReadout(dab, onBlocks = { blockSheet = true }, modifier = Modifier.fillMaxWidth())
        else RadioReadout(u, antenna, row = row, digits = if (row) 56.sp else if (tablet) 52.sp else 40.sp,
            unit = if (row || tablet) 20.sp else 18.sp, onType = { typing = true }, onMode = { modeSheet = true },
            onBands = { bandSheet = true }, onAntenna = { antennaSheet = true }, modifier = Modifier.fillMaxWidth())
    }
    val notes = @Composable {
        u.problem?.let { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(problemText(p), color = OnAirRed, modifier = Modifier.weight(1f))
                if (p.http == 401) TextButton(onClick = onRepair) { Text(stringResource(R.string.shack_signin_go)) }
            }
        }
        u.toast?.let { Text(it, color = Dim, maxLines = 1) }
    }
    // The scope, or DAB's list in its place, with the decoder's panel: beside
    // or under it as before (a slotted decoder's Band Activity gets half a
    // tablet), docked under it on a phone.
    val middle = @Composable { m: Modifier, big: Boolean, split: Boolean ->
        if (dab != null) DabStations(e, u, dab, m)
        else {
            val scope = @Composable { sm: Modifier -> RadioScope(e, u, marks, named, plan, big, onLong = { scopeSheet = true }, modifier = sm) }
            val activity = split && u.panel.shown == DecoderPanel.SLOTS
            Column(m, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activity) {
                    scope(Modifier.weight(0.8f).fillMaxWidth())
                    PhonePanel(e, onPick = tx.onPick, fill = true, modifier = Modifier.weight(1.2f).fillMaxWidth())
                } else {
                    scope(Modifier.weight(1f).heightIn(min = 140.dp).fillMaxWidth())
                    PhonePanel(e, onPick = tx.onPick)
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().remoteKeys(tx.keys)) {
        if (sideways) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                RadioHeader(e, u, onBack, inline = true) {
                    HeaderButton(stringResource(R.string.shack_scope_sheet), "scopeSheet") { scopeSheet = true }
                    HeaderButton(stringResource(R.string.shack_controls_sheet), "controlsSheet") { controlsSheet = true }
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        readout(true)
                        notes()
                        middle(Modifier.weight(1f).fillMaxWidth(), true, true)
                    }
                    Column(Modifier.width(380.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (dab == null) TuningRow(e, u, 72.dp, Modifier.fillMaxWidth())
                        BandGridCard(e, u, Modifier.weight(1f).fillMaxWidth(), onLocal = { e.openLocal() })
                        PhoneEnableTx(e, tx)
                        RadioBottomRow(e, u, 64.dp, Modifier.fillMaxWidth())
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioHeader(e, u, onBack, inline = false) {
                    MoreMenu(onScope = { scopeSheet = true }, onControls = { controlsSheet = true })
                }
                readout(false)
                notes()
                middle(Modifier.weight(1f).fillMaxWidth(), tablet, tablet && config.screenWidthDp >= 600)
                if (dab == null) TuningRow(e, u, if (tablet) 72.dp else 64.dp, Modifier.fillMaxWidth())
                RecentBandsRow(e, u, onOpen = { bandSheet = true }, modifier = Modifier.fillMaxWidth())
                PhoneEnableTx(e, tx)
                RadioBottomRow(e, u, if (tablet) 64.dp else 52.dp, Modifier.fillMaxWidth())
            }
        }
        RemoteKeysHost(tx.keys, tx.face, tx.onLines())
    }
    if (typing) FrequencyDialog(onTune = { hz -> e.setDial(hz); typing = false }, onDismiss = { typing = false })
    if (logOpen) PhoneLog(e, onClose = { logOpen = false })
}

/** A tablet header's Scope or Controls: an outlined pill, as the Tablet mockup's Controls. */
@Composable
private fun HeaderButton(text: String, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.height(40.dp).clip(shape).border(1.dp, RadioLine, shape).clickable(onClick = onClick).padding(horizontal = 16.dp).testTag(tag),
        contentAlignment = Alignment.Center) { Text(text, fontSize = 14.sp, maxLines = 1) }
}

/** The phone header's ⋮: the Scope and Controls sheets, which had a button each in the old header. */
@Composable
private fun MoreMenu(onScope: () -> Unit, onControls: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RadioKey({ open = true }, Modifier.size(44.dp), "more", stringResource(R.string.shack_more), colour = Color.Transparent, radius = 22.dp) {
            androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
                val k = size.width / 24f
                for (y in listOf(5f, 12f, 19f)) drawCircle(Color.White, 2 * k, androidx.compose.ui.geometry.Offset(12 * k, y * k))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.shack_scope_sheet)) }, onClick = { open = false; onScope() },
                modifier = Modifier.testTag("scopeSheet"))
            DropdownMenuItem(text = { Text(stringResource(R.string.shack_controls_sheet)) }, onClick = { open = false; onControls() },
                modifier = Modifier.testTag("controlsSheet"))
        }
    }
}

/**
 * The modes as chips, from the mode chip's ▾ and in Controls: the server's
 * list, the one in use selected; a mode this receiver cannot do (wide FM on
 * an HL2) disabled, with the server's sentence under the row.
 */
@Composable
internal fun ModeChips(e: EngineSession, u: EngineUi, onDone: () -> Unit) {
    if (u.modes.isEmpty()) return
    Text(stringResource(R.string.shack_modes), color = Dim)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        u.modes.forEach { m ->
            FilterChip(selected = m == u.state.mode, onClick = { e.setMode(m); onDone() }, label = { Text(modeWord(m)) },
                enabled = !u.viewOnly && m in u.offered, modifier = Modifier.testTag("mode-$m"))
        }
    }
    u.modes.forEach { m -> u.state.modeMissing(m)?.takeIf { it.isNotEmpty() }?.let { Text(it, color = Dim, fontSize = 13.sp) } }
}

/**
 * The antenna sheet: the driver's default and each input the radio
 * reported, with the operator's labels (Flex's antenna chips, the same
 * look). Disabled once the controller has refused a change for this
 * person's level, as the modes are; the refusal itself is the toast line.
 */
@Composable
private fun RadioAntennaSheet(e: EngineSession, u: EngineUi, a: AntennaChoice, onDone: () -> Unit) {
    // The navigation bar's inset: the footnote sat under the gesture bar.
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_antenna_title), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(stringResource(R.string.shack_antenna_summary, antennaNow(a)), color = Dim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            a.rows.forEach { name ->
                FilterChip(selected = name == a.chosen, onClick = { e.setAntenna(name); onDone() }, enabled = !u.viewOnly,
                    label = { Text(antennaName(name, a)) }, modifier = Modifier.testTag("antenna-$name"))
            }
        }
        if (u.viewOnly) Text(stringResource(R.string.shack_antenna_view_only), color = Amber)
        Text(stringResource(R.string.shack_antenna_rx_only), color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
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
        ModeChips(e, u, onDone)
        // Wide FM's de-emphasis, only in wfm and only from a server that has the setting.
        val deemph = u.state.wfmDeemphasis
        if (u.state.mode == "wfm" && deemph != null) {
            Text(stringResource(R.string.shack_wfm_deemphasis), color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WFM_DEEMPHASIS_US.forEach { us ->
                    FilterChip(selected = Math.round(deemph).toInt() == us, onClick = { e.setDeemphasis(us) }, enabled = !u.viewOnly,
                        label = { Text(stringResource(R.string.shack_wfm_deemphasis_us, us)) }, modifier = Modifier.testTag("deemph-$us"))
                }
            }
        }
        // Why the meter has no S reading (no calibration yet), where there is room to say it.
        u.sMeter.explanation?.let { Text(stringResource(R.string.shack_smeter_why, it), color = Dim, fontSize = 13.sp) }
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
