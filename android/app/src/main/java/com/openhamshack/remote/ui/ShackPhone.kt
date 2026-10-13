package com.openhamshack.remote.ui

import com.openhamshack.kind.stationflow.ui.StationFlowTouchScreen
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.openhamshack.remote.AppViewModel
import com.openhamshack.remote.R
import com.openhamshack.remote.ptt.PttPhase
import com.openhamshack.remote.shack.EnginePttLink
import com.openhamshack.remote.shack.AutoTxStatus
import com.openhamshack.remote.shack.EnableTxController
import com.openhamshack.remote.shack.EnableTxPhase
import com.openhamshack.remote.shack.SlotDecode
import com.openhamshack.remote.shack.TxChoice
import com.openhamshack.core.keys.FaceClass
import com.openhamshack.core.keys.Fired
import com.openhamshack.core.keys.RemoteAction
import com.openhamshack.core.tv.FaceKeys
import com.openhamshack.core.tv.FaceTx
import com.openhamshack.core.tv.RemoteKeysHost
import com.openhamshack.core.tv.RemoteKeysState
import com.openhamshack.core.tv.TxPlan
import com.openhamshack.core.tv.remoteKeys
import com.openhamshack.core.tv.rememberRemoteKeys
import androidx.compose.ui.platform.LocalResources
import androidx.compose.runtime.LaunchedEffect
import com.openhamshack.remote.shack.Bookmarks
import com.openhamshack.remote.shack.DecoderPanel
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.ModeAudio
import com.openhamshack.remote.shack.PanelPrefs
import com.openhamshack.remote.shack.ScopeLabels
import com.openhamshack.remote.shack.Kind
import com.openhamshack.remote.shack.ShackScreen
import com.openhamshack.remote.shack.Span

/**
 * A controller on a phone or tablet: sign in once with the controller's
 * user, pick an app, and the NB or Radio screen - the TV's, plus touch
 * tuning on the scope and the transmit lease.
 */
@Composable
fun ShackPhone(vm: AppViewModel) {
    val reconnecting by vm.shack.reconnecting.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        ShackPhoneScreens(vm)
        // An app refused the token and the controller is being asked whether
        // it still stands (TokenGuard): said over the screen, not instead of it.
        if (reconnecting) ReconnectingChip(
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 8.dp), big = false)
    }
}

@Composable
private fun ShackPhoneScreens(vm: AppViewModel) {
    val screen by vm.shack.screen.collectAsStateWithLifecycle()
    val engine by vm.shack.engine.collectAsStateWithLifecycle()
    val wb by vm.shack.wb.collectAsStateWithLifecycle()
    val datv by vm.shack.datv.collectAsStateWithLifecycle()
    val icom by vm.shack.icom.collectAsStateWithLifecycle()
    val flex by vm.shack.flex.collectAsStateWithLifecycle()
    val e = engine
    val f = flex
    // QO-100 WB is the Classic box's own screen, full bleed: it does its
    // own insets.
    wb?.let { w ->
        BackHandler { vm.shack.closeApp() }
        BoxScreen(w, vm, onBack = { vm.shack.closeApp() })
        return
    }
    datv?.let { d ->
        BackHandler { vm.shack.closeApp() }
        DatvPhone(d, onBack = { vm.shack.closeApp() })
        return
    }
    // OpenHamShack Icom: the kind's own touch screen (receive only), full bleed.
    icom?.let { i ->
        StationFlowTouchScreen(i, onExit = { vm.shack.closeApp() }, onSignIn = { vm.shack.tokenRefused() })
        return
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)) {
        if (e != null) {
            BackHandler { vm.shack.closeApp() }
            // Radio is laid out as Flex (Brian, 13/10/2026: "make the radio app
            // look more like the flex app"); NB keeps this screen, whose 50/50
            // Band Activity was made for it.
            if (e.kind == Kind.RADIO) RadioPhone(e, onBack = { vm.shack.closeApp() }, onRepair = { vm.shack.tokenRefused() })
            else PhoneEngine(e, onBack = { vm.shack.closeApp() }, onRepair = { vm.shack.tokenRefused() })
        } else if (f != null) {
            FlexPhone(f, onBack = { vm.shack.closeApp() })
        } else {
            BackHandler { vm.closeController() }
            when (val s = screen) {
                is ShackScreen.SignIn -> SignIn(s, vm)
                is ShackScreen.Apps -> PhoneApps(s, vm)
                // A phone signs in with a password; pairing by code is the TV's.
                is ShackScreen.Pairing, null -> Unit
            }
        }
    }
}

@Composable
private fun SignIn(s: ShackScreen.SignIn, vm: AppViewModel) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var totp by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_signin_title, s.box.title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.shack_signin_hint), color = Dim)
        OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.shack_signin_user)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("shackUser"))
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.shack_signin_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("shackPassword"))
        if (s.needTotp) OutlinedTextField(totp, { totp = it }, label = { Text(stringResource(R.string.shack_signin_totp)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
        s.problem?.let { Text(problemText(it), color = OnAirRed) }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { vm.shack.signIn(user, password, totp.ifBlank { null }) }, enabled = !s.busy,
                modifier = Modifier.testTag("shackSignIn")) {
                Text(stringResource(if (s.busy) R.string.common_connecting else R.string.shack_signin_go))
            }
            TextButton(onClick = { vm.closeController() }) { Text(stringResource(R.string.shack_back)) }
        }
    }
}

@Composable
private fun PhoneApps(s: ShackScreen.Apps, vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.shack_apps_title, s.wk?.name ?: s.box.title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.closeController() }) { Text(stringResource(R.string.shack_back)) }
        }
        if (s.wk == null && s.problem == null) Text(stringResource(R.string.shack_apps_loading), color = Dim)
        s.problem?.let { p ->
            Text(problemText(p), color = OnAirRed)
            if (p.http == 401) Button(onClick = { vm.shack.tokenRefused() }) { Text(stringResource(R.string.shack_signin_go)) }
            else Button(onClick = { vm.shack.refreshApps() }) { Text(stringResource(R.string.shack_retry)) }
        }
        if (s.wk != null && s.wk.openable.isEmpty()) Text(stringResource(R.string.shack_apps_none), color = Dim)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.wk?.apps.orEmpty().sortedBy { if (it.kind.opens) 0 else 1 }, key = { it.id }) { a ->
                val ok = a.kind.opens
                Card(Modifier.fillMaxWidth().clickable(enabled = ok) { vm.shack.openApp(a) }.testTag("app-${a.wireKind}")) {
                    Column(Modifier.padding(14.dp)) {
                        Text(a.name, style = MaterialTheme.typography.titleMedium, color = if (ok) Color.Unspecified else Dim)
                        Text(listOfNotNull(a.wireKind, if (!a.running) stringResource(R.string.shack_app_stopped) else null,
                            if (!ok) stringResource(R.string.shack_app_unsupported) else null).joinToString(" · "), color = Dim)
                    }
                }
            }
        }
    }
}

/**
 * NB or Radio on a phone or tablet. The scope is touch-tuned (a tap tunes
 * to that frequency); buttons step, change mode, span and band; a typed
 * frequency; and the transmit lease's button at the bottom when this app
 * can transmit. A tablet in landscape puts the controls beside the scope;
 * while FT8, FT4, Q65 or JS8 decodes, Band Activity takes half the screen
 * beside the scope (under it, held upright) instead.
 */
@Composable
private fun PhoneEngine(e: EngineSession, onBack: () -> Unit, onRepair: () -> Unit) {
    val u by e.ui.collectAsStateWithLifecycle()
    val pttState = e.ptt?.state?.collectAsStateWithLifecycle()
    // Awake while audio plays, and always while this phone holds the
    // transmitter: a screen going off unkeys it (REMOTE-CONTRACT 5).
    if (keepAwake(u.audioRate, u.muted, keyed = pttState != null && pttState.value.phase != PttPhase.Idle)) KeepScreenOn()
    var typing by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    val config = LocalConfiguration.current
    val wide = config.screenWidthDp >= 840
    val landscape = config.screenWidthDp > config.screenHeightDp
    val tx = rememberPhoneEnableTx(e)
    Box(Modifier.fillMaxSize().remoteKeys(tx.keys)) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A phone held upright scrolls, with the waterfall at a fixed height:
        // with the rows of modes and bookmarks, the controls and a decoder
        // panel all on one screen, a weighted scope was squeezed to nothing
        // (seen on the Pixel emulator with the SSTV picture open). The
        // transmit button stays outside the scroll, always in reach.
        Column(Modifier.weight(1f).then(if (wide) Modifier else Modifier.verticalScroll(rememberScrollState())),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The same pruning as the TV (Brian, 10/10/2026: "too cluttered"):
            // the dial and mode, one quiet line of what matters, and the rows
            // of modes and bookmarks folded behind one button.
            // Back sits at the top right of the header row, as on Flex: a
            // tablet had only the system gesture (Brian, 12/10/2026: "there
            // is no back button to get out of the app"). It does what the
            // system Back does here (BackHandler in the caller).
            // The dial and mode wrap (mode under the dial) rather than squeeze
            // Back: on a 1080 px phone the 30 sp dial left Back one letter wide.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    itemVerticalAlignment = Alignment.Bottom) {
                    Text(dialText(u.dialHz), fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                        color = if (u.pendingDialHz != null) Accent else Color.White, modifier = Modifier.clickable { typing = true }.testTag("dial"))
                    Text(u.state.mode.uppercase(), fontSize = 22.sp, color = Accent, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onBack, modifier = Modifier.testTag("back")) { Text(stringResource(R.string.shack_back), softWrap = false) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                if (e.kind == Kind.QO100NB) LockDot(u.state.beaconLock, 14.sp)
                if (e.kind == Kind.RADIO) Text(if (u.state.band.isBlank()) stringResource(R.string.shack_band_none) else stringResource(R.string.shack_band, u.state.band),
                    color = Dim, fontSize = 14.sp)
                if (u.state.segment.isNotBlank()) Text(u.state.segment, color = Dim, fontSize = 14.sp)
                if (u.status?.simulator == true) Text(stringResource(R.string.shack_simulator), color = Amber, fontSize = 14.sp)
                if (u.muted) Text(stringResource(R.string.shack_muted), color = Dim, fontSize = 14.sp)
                else if (u.audioRate <= 0 && u.connected) Text(stringResource(R.string.shack_no_audio), color = Dim, fontSize = 14.sp)
                if (!u.connected) Text(stringResource(R.string.shack_reconnecting), color = Amber, fontSize = 14.sp)
                SignalMeter(u.rxDb, 80.dp, big = false)
            }
            // The engine's modes, then the band plan's bookmarks on the scope:
            // a tap sets the mode (with a bookmark, the dial too) and the
            // decoder's panel opens, as on the TV. Folded away until asked
            // for, and folded again once one is chosen.
            val range = scopeRange(e)
            val marks = remember(u.bookmarks, range, u.state.dialHz == null) {
                Bookmarks.inView(u.bookmarks, range?.first, range?.second, u.state.dialHz)
            }
            val current = Bookmarks.current(marks, u.state.dialHz, u.state.mode)
            val named = marks.getOrNull(ScopeLabels.named(marks, u.dialHz, null, range?.first, range?.second))
            var picking by remember { mutableStateOf(false) }
            if (u.modes.isNotEmpty() || marks.isNotEmpty()) {
                TextButton(onClick = { picking = !picking }, modifier = Modifier.testTag("picker")) {
                    Text(stringResource(R.string.shack_picker_open, current?.name ?: u.state.mode.uppercase()) + if (picking) "  ▴" else "  ▾")
                }
            }
            if (picking) {
                if (marks.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    marks.forEach { b ->
                        FilterChip(selected = b == current, onClick = { e.goTo(b); picking = false }, enabled = !u.viewOnly,
                            label = { Text(b.name) }, modifier = Modifier.testTag("mark-${b.name}"))
                    }
                }
                if (u.modes.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    u.modes.forEach { m ->
                        FilterChip(selected = m == u.state.mode, onClick = { e.setMode(m); picking = false }, label = { Text(m.uppercase()) },
                            enabled = !u.viewOnly, modifier = Modifier.testTag("mode-$m"))
                    }
                }
            }
            val scopeBox = @Composable { m: Modifier ->
                Box(m.clip(RoundedCornerShape(6.dp)).pointerInput(Unit) {
                    detectTapGestures { pos ->
                        val sc = e.scopeData
                        if (sc.endHz > sc.startHz) {
                            val hz = sc.startHz + (sc.endHz - sc.startHz) * (pos.x / size.width)
                            // A tap is on a signal: the dial goes where the mode
                            // hears it - 1500 Hz below an FT8 or FreeDV signal,
                            // the pitch below a CW one (ModeAudio).
                            e.setDial(ModeAudio.dialFor(hz, ModeAudio.of(e.ui.value.state)))
                        }
                    }
                }.testTag("scope")) { ScopeView(e, u.dialHz, Modifier.fillMaxSize(), marks = marks, named = named, passband = ModeAudio.of(u.state)) }
            }
            // Brian, 12/10/2026, on his Tab S9 Ultra: "make the decode more
            // prominent on the screen". The list was a 340 dp column at 13 sp
            // under the controls' width; a tablet decoding a slotted mode now
            // gives it as much room as the waterfall, at 18 sp, with every
            // column the desktop has.
            val bandActivity = wide && u.panel.shown == DecoderPanel.SLOTS
            if (bandActivity && landscape) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        scopeBox(Modifier.weight(1f).fillMaxWidth())
                        Controls(e, onType = { typing = true }, onLog = { logOpen = true })
                    }
                    PhonePanel(e, onPick = tx.onPick, fill = true, modifier = Modifier.weight(1.15f).fillMaxHeight())
                }
            } else if (bandActivity) {
                scopeBox(Modifier.weight(0.8f).fillMaxWidth())
                PhonePanel(e, onPick = tx.onPick, fill = true, modifier = Modifier.weight(1.2f).fillMaxWidth())
                Controls(e, onType = { typing = true }, onLog = { logOpen = true })
            } else if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    scopeBox(Modifier.weight(1f).fillMaxHeight())
                    // A fixed width: the FlowRow would otherwise take the whole row and leave the scope a sliver.
                    Column(Modifier.width(340.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The decoder first, beside the waterfall as on the TV.
                        PhonePanel(e, onPick = tx.onPick)
                        Controls(e, onType = { typing = true }, onLog = { logOpen = true })
                    }
                }
            } else {
                scopeBox(Modifier.height(240.dp).fillMaxWidth())
                // Under the waterfall, before the controls: what is being decoded comes first.
                PhonePanel(e, onPick = tx.onPick)
                Controls(e, onType = { typing = true }, onLog = { logOpen = true })
            }
            u.problem?.let { p ->
                Text(problemText(p), color = OnAirRed)
                if (p.http == 401) TextButton(onClick = onRepair) { Text(stringResource(R.string.shack_signin_go)) }
            }
            u.toast?.let { Text(it, color = Dim, maxLines = 2) }
        }
        PhoneEnableTx(e, tx)
        if (e.ptt != null) EnginePtt(e)
    }
    RemoteKeysHost(tx.keys, tx.face, tx.onLines())
    }
    if (typing) FrequencyDialog(onTune = { hz -> e.setDial(hz); typing = false }, onDismiss = { typing = false })
    if (logOpen) PhoneLog(e, onClose = { logOpen = false })
}

@Composable
internal fun PhoneLog(e: EngineSession, onClose: () -> Unit) {
    val u by e.ui.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { e.loadLog() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.shack_log_title, e.app.name)) },
        text = {
            val log = u.log
            if (log != null && log.isEmpty()) Text(stringResource(R.string.shack_log_empty), color = Dim)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(log.orEmpty().take(200), key = { it.t.toString() + it.call }) { q -> QsoText(q, false) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.shack_done)) } },
    )
}

@Composable
private fun Controls(e: EngineSession, onType: () -> Unit, onLog: () -> Unit) {
    val u by e.ui.collectAsStateWithLifecycle()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(onClick = { e.tune(-1) }, modifier = Modifier.testTag("tuneDown")) { Text("◀") }
        FilledTonalButton(onClick = { e.tune(1) }, modifier = Modifier.testTag("tuneUp")) { Text("▶") }
        // Down as well as up, either side of the step: one button that only went up left the
        // smaller steps unreachable once the larger had been chosen.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { e.step(-1) }, modifier = Modifier.testTag("stepDown")) { Text("\u2212") }
            Text(stringResource(R.string.shack_step, stepText(u.stepHz)), maxLines = 1, modifier = Modifier.testTag("step"))
            FilledTonalButton(onClick = { e.step(1) }, modifier = Modifier.testTag("stepUp")) { Text("+") }
        }
        FilledTonalButton(onClick = { e.span(if (u.span == Span.WIDE) Span.ZOOM else Span.WIDE) }) {
            Text(stringResource(if (u.span == Span.WIDE) R.string.shack_span_wide else R.string.shack_span_zoom))
        }
        FilledTonalButton(onClick = { e.mute(!u.muted) }) { Text(stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute)) }
        // Only where the engine's spec has it; disabled once the controller
        // has refused this person a receive-side change.
        if (u.hasNr) FilledTonalButton(onClick = { e.nr(u.state.nr != true) }, enabled = !u.viewOnly,
            modifier = Modifier.testTag("nr")) { Text(nrLabel(u.state.nr)) }
        if (e.kind == Kind.RADIO) {
            FilledTonalButton(onClick = { e.band(-1) }) { Text(stringResource(R.string.shack_band_down)) }
            FilledTonalButton(onClick = { e.band(1) }, modifier = Modifier.testTag("bandUp")) { Text(stringResource(R.string.shack_band_up)) }
        }
        FilledTonalButton(onClick = onType) { Text(stringResource(R.string.shack_frequency)) }
        FilledTonalButton(onClick = onLog, modifier = Modifier.testTag("log")) { Text(stringResource(R.string.shack_log)) }
        if (u.panel.active != DecoderPanel.NONE && u.panel.shown == DecoderPanel.NONE) {
            FilledTonalButton(onClick = { e.openPanel() }, modifier = Modifier.testTag("panelShow")) { Text(stringResource(R.string.shack_panel_show)) }
        }
        val context = LocalContext.current
        FilledTonalButton(onClick = { val on = !u.autoOpen; PanelPrefs.setAutoOpen(context, on); e.setAutoOpen(on) }, modifier = Modifier.testTag("panelAuto")) {
            Text(stringResource(if (u.autoOpen) R.string.shack_panel_auto_on else R.string.shack_panel_auto_off))
        }
    }
}

/**
 * The running decoder's panel under the scope, opened and closed with its
 * decoder as on the TV ([PanelState]); Hide puts it away until the
 * decoder changes. SSTV keeps the phone's own view, with the session's
 * earlier pictures to tap through.
 *
 * [fill]: a tablet's Band Activity, filling the [modifier]'s room at
 * 20 sp under a heading line that carries Hide. Otherwise a phone's: a
 * fixed 220 dp list at 14 sp under a slim heading line.
 */
@Composable
internal fun PhonePanel(e: EngineSession, onPick: ((SlotDecode) -> Unit)? = null, fill: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth()) {
    val u by e.ui.collectAsStateWithLifecycle()
    if (u.panel.shown == DecoderPanel.NONE) return
    val hide = @Composable { m: Modifier ->
        TextButton(onClick = { e.closePanel() }, modifier = m.height(32.dp)) {
            Text(stringResource(R.string.shack_panel_hide), color = Dim, fontSize = 13.sp)
        }
    }
    // Clear empties the displayed list and nothing else (EngineSession.clearDecodes).
    val clear = @Composable {
        TextButton(onClick = { e.clearDecodes() }, enabled = u.decodes.items.isNotEmpty(), modifier = Modifier.height(32.dp).testTag("clearDecodes")) {
            Text(stringResource(R.string.shack_decodes_clear), color = Dim, fontSize = 13.sp)
        }
    }
    if (fill) {
        Column(modifier.background(Color(0xFF0C0C0C), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp).testTag("panel")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.shack_band_activity, u.state.mode.uppercase()), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (u.panel.shown == DecoderPanel.SLOTS) clear()
                hide(Modifier)
            }
            // 20 sp: Brian wants it read "across a room" at a club demo. In
            // landscape that costs the DT column (BandColumns), which nobody
            // reads from across a room either.
            DecoderPanelBody(e, u, big = false, listHeight = 0.dp, onPick = onPick, listSize = 20.sp, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        return
    }
    if (u.panel.shown == DecoderPanel.SLOTS) {
        // Band Activity's Country column runs to the right edge, where Hide
        // used to sit over the first row (and covered its country): a
        // slim line of its own, as on the tablet but smaller.
        Column(modifier.background(Color(0xFF0C0C0C), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp).testTag("panel")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.shack_band_activity, u.state.mode.uppercase()), fontSize = 14.sp, color = Dim,
                    modifier = Modifier.weight(1f))
                clear()
                hide(Modifier)
            }
            DecoderPanelBody(e, u, big = false, listHeight = 220.dp, onPick = onPick, heads = false)
        }
        return
    }
    // No heading for the other decoders: the mode is beside the dial
    // already. Hide sits over the panel's top corner rather than taking a
    // line of its own.
    Box(modifier.background(Color(0xFF0C0C0C), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp).testTag("panel")) {
        Column(Modifier.fillMaxWidth()) {
            if (u.panel.shown == DecoderPanel.SSTV && synchronized(e.sstv) { e.sstv.current } != null) PhoneSstv(e)
            else DecoderPanelBody(e, u, big = false, listHeight = 150.dp, onPick = onPick)
        }
        hide(Modifier.align(Alignment.TopEnd))
    }
}

/** The scope's span, recomposing only when it changes (a band, wide/zoom), not on every frame. */
@Composable
internal fun scopeRange(e: EngineSession): Pair<Double, Double>? {
    val v by e.scopeVersion.collectAsStateWithLifecycle()
    val r by remember { derivedStateOf { v.let { e.scopeData.startHz to e.scopeData.endHz }.takeIf { it.second > it.first } } }
    return r
}

/**
 * The transmit button, as the Classic box's (TransmitPanel): a one-second
 * hold keys under this device's lease, one tap stops - whoever keyed.
 * Refused here, before any request, when the server has no lease
 * (`tx.remote` absent) or says why transmit is locked; the server checks
 * everything again at key-up and its refusal is shown as it says it.
 */
@Composable
internal fun EnginePtt(e: EngineSession) {
    val ptt = e.ptt ?: return
    val st by ptt.state.collectAsStateWithLifecycle()
    val u by e.ui.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    val status = u.status
    val onAir = status?.onAir == true
    val mine = st.phase != PttPhase.Idle
    val noLease = stringResource(R.string.shack_ptt_no_lease)
    val cannot: String? = when {
        mine || onAir -> null
        status == null || !u.connected -> stringResource(R.string.shack_connecting)
        !status.leaseSupported -> noLease
        status.txBlocked.isNotBlank() -> stringResource(R.string.shack_tx_blocked, status.txBlocked)
        else -> null
    }
    val label = when {
        st.phase == PttPhase.Keyed -> stringResource(R.string.shack_on_air) + " · " + stringResource(R.string.shack_ptt_release)
        st.phase == PttPhase.Keying -> stringResource(R.string.shack_ptt_keying)
        // This device's own Enable TX keying an over is not "another station control".
        onAir && u.status?.autoTx?.let { it.armed && it.remote == e.enableTx.remoteId } == true ->
            stringResource(R.string.shack_tx_on_title, u.state.mode.uppercase())
        onAir -> stringResource(R.string.shack_on_air_elsewhere)
        cannot != null -> cannot
        else -> stringResource(R.string.shack_ptt_hold)
    }
    val colour = if (mine || onAir || progress > 0f) OnAirRed else if (cannot != null) Color(0xFF424242) else Color(0xFF5D4037)
    st.error?.let { err ->
        Text(when (err) {
            EnginePttLink.LEASE_MISSING -> stringResource(R.string.shack_ptt_lease_missing)
            EnginePttLink.NOT_ON_AIR -> stringResource(R.string.shack_ptt_not_on_air)
            else -> err
        }, color = Amber)
    }
    Box(
        Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(12.dp)).background(if (progress > 0f) Color(0xFF6D4C41) else colour)
            .testTag("ptt")
            .pointerInput(mine, onAir, cannot) {
                detectTapGestures(onPress = {
                    when {
                        mine || onAir -> if (tryAwaitRelease()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            ptt.unkey()
                        }
                        cannot != null -> tryAwaitRelease()
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
                                ptt.key(false)
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
        if (progress > 0f) Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress).background(OnAirRed))
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
    }
}

/**
 * Enable TX on a phone or tablet (REMOTE-CONTRACT 12c): Call CQ, a tap on a
 * decode, or a typed line, each through the same red confirm as a TV's
 * Play/Pause held - Start or Cancel within 5 s - and Stop while it is on.
 * The keys are the shared ones too, so a tablet's keyboard or a Bluetooth
 * remote arms and stops it the same way. The server decides who may: a
 * device below Transmit is refused, and the refusal is shown.
 */
internal class PhoneEnableTx(
    val keys: RemoteKeysState,
    val face: FaceKeys,
    val onPick: ((SlotDecode) -> Unit)?,
    val armCq: () -> Unit,
    val armLine: (String) -> Unit,
    val onLines: @Composable () -> List<String>,
)

@Composable
internal fun rememberPhoneEnableTx(e: EngineSession): PhoneEnableTx {
    val u by e.ui.collectAsStateWithLifecycle()
    val txState by e.enableTx.state.collectAsStateWithLifecycle()
    val res = LocalResources.current
    var choice by remember { mutableStateOf<TxChoice?>(null) }
    val mode = u.state.mode
    val title = stringResource(R.string.shack_tx_title)
    val where = stringResource(R.string.shack_tx_where, mode.uppercase(), dialText(u.dialHz))
    val cq = stringResource(R.string.shack_tx_cq_text, u.state.callsign.ifBlank { "—" }, u.state.locator.take(4)).trim()
    val notDigital = stringResource(R.string.shack_tx_not_digital, mode.uppercase())
    val tx = object : FaceTx {
        override fun plan(): TxPlan? = when (mode) {
            in AutoTxStatus.SEQUENCED -> { choice = TxChoice.Cq; TxPlan(title, listOf(where, cq)) }
            // A line is typed on screen, and armed from its Send button.
            in AutoTxStatus.LINES -> null
            else -> TxPlan(title, emptyList(), refusal = notDigital)
        }
        override fun start(plan: TxPlan) { choice?.let { e.enableTx.enable(it) } }
        override fun stop() = e.enableTx.stop()
    }
    // Touch has its own buttons for everything; the keys a tablet's keyboard brings are the radio's basics.
    val face = object : FaceKeys {
        override val faceClass = FaceClass.RADIO
        override fun can(a: RemoteAction): Boolean = a in setOf(RemoteAction.TUNE_DOWN, RemoteAction.TUNE_UP, RemoteAction.STEP_DOWN,
            RemoteAction.STEP_UP, RemoteAction.MUTE, RemoteAction.TRANSMIT)
        override fun perform(f: Fired) {
            when (f.action) {
                RemoteAction.TUNE_DOWN -> e.tune(-1)
                RemoteAction.TUNE_UP -> e.tune(1)
                RemoteAction.STEP_DOWN -> e.step(-1)
                RemoteAction.STEP_UP -> e.step(1)
                RemoteAction.MUTE -> e.mute(!u.muted)
                else -> {}
            }
        }
    }
    val keys = rememberRemoteKeys(face, tx, txOn = txState.phase == EnableTxPhase.Armed, txBusy = txState.phase == EnableTxPhase.Arming)
    val noCall = stringResource(R.string.shack_tx_no_call)
    LaunchedEffect(txState.error) {
        val err = txState.error ?: return@LaunchedEffect
        keys.note = when (err.code) {
            EnableTxController.RELEASED -> return@LaunchedEffect
            "level_too_low" -> res.getString(R.string.shack_tx_level, err.text)
            EnableTxController.SERVER_OFF -> e.ui.value.status?.autoTx?.why?.takeIf { it.isNotBlank() }
                ?.let { res.getString(R.string.shack_tx_off_why, it) } ?: res.getString(R.string.shack_tx_off)
            EnableTxController.LINK_LOST -> res.getString(R.string.shack_tx_link_lost)
            EnableTxController.NO_ANSWER -> res.getString(R.string.shack_tx_no_answer, err.text)
            else -> res.getString(R.string.shack_tx_refused, err.text.ifBlank { err.code })
        }
    }
    val onPick: ((SlotDecode) -> Unit)? = if (mode !in AutoTxStatus.SEQUENCED) null else { d ->
        val call = d.sender
        if (call.isEmpty()) keys.note = noCall
        else {
            choice = TxChoice.Reply(d)
            keys.armWith(TxPlan(title, listOf(where, res.getString(R.string.shack_tx_reply, call), d.text)))
        }
    }
    return PhoneEnableTx(
        keys, face, onPick,
        armCq = { tx.plan()?.let { keys.armWith(it) } },
        armLine = { text ->
            choice = TxChoice.Line(text)
            keys.armWith(TxPlan(title, listOf(where, res.getString(R.string.shack_tx_line, text))))
        },
        onLines = {
            val auto = u.status?.autoTx
            buildList {
                add(stringResource(R.string.shack_tx_on_title, mode.uppercase()))
                add(where)
                auto?.dx?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.shack_tx_with, it)) }
                auto?.next?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.shack_tx_next, it)) }
                (txState.choice as? TxChoice.Line)?.let { add(stringResource(R.string.shack_tx_line, it.text)) }
            }
        },
    )
}

/** The Enable TX row above the PTT button: shown only in the modes the server can send. */
@Composable
internal fun PhoneEnableTx(e: EngineSession, tx: PhoneEnableTx) {
    val u by e.ui.collectAsStateWithLifecycle()
    val mode = u.state.mode
    when (mode) {
        in AutoTxStatus.SEQUENCED -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = tx.armCq, modifier = Modifier.testTag("txCq")) { Text(stringResource(R.string.shack_tx_cq_button)) }
            Text(stringResource(R.string.shack_tx_tap_hint), color = Dim, fontSize = 13.sp)
        }
        in AutoTxStatus.LINES -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            var line by remember { mutableStateOf("") }
            androidx.compose.material3.OutlinedTextField(line, { line = it }, singleLine = true, modifier = Modifier.weight(1f).testTag("txLine"),
                label = { Text(stringResource(R.string.shack_tx_line_label)) })
            FilledTonalButton(onClick = { if (line.isNotBlank()) tx.armLine(line.trim()) }, enabled = line.isNotBlank(),
                modifier = Modifier.testTag("txSend")) { Text(stringResource(R.string.shack_tx_send)) }
        }
        else -> {}
    }
}
