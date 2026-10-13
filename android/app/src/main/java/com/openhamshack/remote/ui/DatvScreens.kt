package com.openhamshack.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.onFocusChanged
import com.openhamshack.core.keys.FaceClass
import com.openhamshack.core.keys.Fired
import com.openhamshack.core.keys.RemoteAction
import com.openhamshack.core.tv.Choice
import com.openhamshack.core.tv.ChoiceOverlay
import com.openhamshack.core.tv.FaceKeys
import com.openhamshack.core.tv.RemoteKeysHost
import com.openhamshack.core.tv.remoteKeys
import com.openhamshack.core.tv.rememberRemoteKeys
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.remote.R
import com.openhamshack.remote.player.PlayPhase
import com.openhamshack.remote.player.PlayerState
import com.openhamshack.remote.shack.DATV_RATES
import com.openhamshack.remote.shack.DatvSession
import com.openhamshack.remote.shack.DatvStatus
import com.openhamshack.remote.shack.DatvUi
import com.openhamshack.remote.shack.datvChannelAt

// OpenHamShack DATV (kind `datv`): the received picture and the receiver's
// tuning, receive only on every device (openhamshack-datv docs/SERVER-API.md).
// The phone puts the controls under the picture; the TV keeps the picture
// full screen with an overlay and a tuning panel, as the Classic TV screen
// does (TvWatch).

/** Frequency and rate, as the overlay's first line: "437.00000 MHz · 333 kS". */
@Composable
private fun datvTuning(st: DatvStatus): String =
    stringResource(R.string.datv_tuning, dialText(st.rxFreqHz), (st.rxSr ?: 0) / 1000)

/** The engine's lock word with the MER, or why there is no picture yet. */
@Composable
private fun datvLock(st: DatvStatus): String = when {
    !st.running -> stringResource(R.string.datv_stopped)
    st.locked -> stringResource(R.string.datv_locked, st.lock, db(st.mer))
    st.lock == "header" -> stringResource(R.string.datv_header)
    else -> stringResource(R.string.datv_searching)
}

private fun lockColour(st: DatvStatus) = when {
    st.locked -> LockGreen
    !st.running -> Dim
    else -> Amber
}

/** The player's state, blank while all is well (the receiver's lock says the rest). */
@Composable
private fun datvPlayer(player: PlayerState, st: DatvStatus?): String = when (player.phase) {
    PlayPhase.Connecting -> stringResource(R.string.datv_player_connecting)
    PlayPhase.Reconnecting -> stringResource(R.string.datv_player_reconnecting)
    PlayPhase.Stopped -> ""
    PlayPhase.Playing -> if (player.hasVideo) "" else if (st?.locked == true) stringResource(R.string.datv_player_keyframe)
        else stringResource(R.string.datv_player_no_picture)
}

@Composable
private fun DatvOverlayLines(ui: DatvUi, player: PlayerState, big: Boolean) {
    val st = ui.status
    val size = if (big) 1.6f else 1f
    if (st == null) {
        Text(stringResource(if (ui.problem != null) R.string.shack_problem_network else R.string.shack_connecting),
            color = Amber, fontSize = (14 * size).sp)
        return
    }
    Text(datvTuning(st), color = Color.White, fontSize = (16 * size).sp, fontWeight = FontWeight.Bold)
    Text(datvLock(st), color = lockColour(st), fontSize = (14 * size).sp)
    if (st.locked && st.service.isNotBlank()) Text(stringResource(R.string.datv_service, st.service), color = Color.White, fontSize = (13 * size).sp)
    if (st.simulator) Text(stringResource(R.string.datv_simulator), color = Amber, fontSize = (13 * size).sp)
    if (st.radioError.isNotBlank()) Text(st.radioError, color = OnAirRed, fontSize = (13 * size).sp)
    datvPlayer(player, st).ifBlank { null }?.let { Text(it, color = Amber, fontSize = (13 * size).sp) }
}

// --- phone and tablet --------------------------------------------------------

@Composable
fun DatvPhone(d: DatvSession, onBack: () -> Unit) {
    val ui by d.ui.collectAsStateWithLifecycle()
    val player by d.player.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(d) { d.messages.collect { snackbar.showSnackbar(it) } }
    KeepScreenOn()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.wb_platform_back)) }
                Text(stringResource(R.string.shack_engine_title, d.app.name, d.controllerName),
                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black).testTag("datvPicture")) {
                VideoSurface(d.player)
                Column(Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000)).padding(6.dp)) {
                    DatvOverlayLines(ui, player, big = false)
                }
            }
            DatvControls(d, ui, Modifier.padding(12.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing))
    }
}

@Composable
private fun DatvControls(d: DatvSession, ui: DatvUi, modifier: Modifier) {
    val st = ui.status
    var typed by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    val tag = localeTag()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.datv_receive_only), color = Dim, style = MaterialTheme.typography.bodySmall)
        if (st != null) {
            Button(onClick = { if (st.running) d.stop() else d.start() }, modifier = Modifier.testTag("datvRun")) {
                Text(stringResource(if (st.running) R.string.datv_stop else R.string.datv_start))
            }
        }
        Text(stringResource(R.string.datv_band), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ui.bands.forEach { b ->
                FilterChip(selected = st?.band == b.name, onClick = { d.band(b.name) }, label = { Text(b.name) },
                    modifier = Modifier.testTag("datvBand-${b.name}"))
            }
        }
        val band = ui.bands.firstOrNull { it.name == st?.band }
        val here = datvChannelAt(ui.bands, st?.rxFreqHz)
        if (band != null && band.channels.isNotEmpty()) {
            Text(stringResource(R.string.datv_channels), style = MaterialTheme.typography.titleSmall)
            band.channels.forEach { c ->
                FilterChip(selected = c == here, onClick = { d.tune(c.freqHz) },
                    label = { Text(stringResource(R.string.datv_channel, dialText(c.freqHz), c.label)) },
                    modifier = Modifier.fillMaxWidth())
            }
        }
        Text(stringResource(R.string.datv_rate_title), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DATV_RATES.forEach { r ->
                FilterChip(selected = st?.rxSr == r, onClick = { d.setRate(r) }, label = { Text(stringResource(R.string.datv_rate, r / 1000)) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(typed, { typed = it; bad = false }, label = { Text(stringResource(R.string.shack_freq_label)) },
                singleLine = true, isError = bad, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f).testTag("datvFreq"))
            Button(onClick = {
                val hz = runCatching { com.openhamshack.i18n.ShackFormat.parseFrequency(typed, tag, "MHz") }.getOrNull()
                if (hz == null || hz <= 0) bad = true else d.tune(hz.toDouble())
            }) { Text(stringResource(R.string.shack_freq_go)) }
        }
        if (bad) Text(stringResource(R.string.shack_freq_bad), color = OnAirRed)
    }
}

// --- TV ------------------------------------------------------------------------

/**
 * DATV on a TV: the picture full screen, on the remote's standard map for a
 * video face (core keys/, REMOTE-KEYS.md §4). Left/Right and the channel
 * keys step through the band's channels, one per press (each retune costs
 * the tuner a lock); Up chooses a band or channel from a grid; rewind/FF
 * change band; OK shows or hides the overlay and Down shows it with its
 * Tune button focused; Menu opens the panel (bands, channels, rates, a
 * typed frequency), OK held or a digit the typed frequency; Play/Pause
 * mutes; Menu held or Info shows the legend. Before 12/10/2026 only OK
 * and Menu did anything. BACK closes the panel, then the overlay, then
 * goes back to the controller's apps. Everything under the panel is
 * unfocusable while it is open, so D-pad focus cannot leak onto the
 * picture behind it (GUIDE 5, "focus leaks out of overlays").
 */
@Composable
fun TvDatv(d: DatvSession, onBack: () -> Unit) {
    val ui by d.ui.collectAsStateWithLifecycle()
    val player by d.player.state.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<String?>(null) }
    val root = remember { FocusRequester() }
    val tune = remember { FocusRequester() }
    var choosing by remember { mutableStateOf(false) }
    KeepScreenOn()
    Immersive()
    val st = ui.status
    val band = ui.bands.firstOrNull { it.name == st?.band }
    fun channel(by: Int) {
        val list = band?.channels.orEmpty()
        if (list.isEmpty()) return
        val at = list.indexOf(datvChannelAt(ui.bands, st?.rxFreqHz))
        val next = if (at < 0) (if (by > 0) 0 else list.size - 1) else (at + by).coerceIn(0, list.size - 1)
        if (next != at) d.tune(list[next].freqHz)
    }
    fun bandBy(by: Int) {
        if (ui.bands.isEmpty()) return
        val at = ui.bands.indexOf(band)
        val next = if (at < 0) 0 else (at + by).coerceIn(0, ui.bands.size - 1)
        if (next != at) d.band(ui.bands[next].name)
    }
    val face = object : FaceKeys {
        override val faceClass = FaceClass.VIDEO
        override fun can(a: RemoteAction): Boolean = when (a) {
            RemoteAction.CHANNEL_PREV, RemoteAction.CHANNEL_NEXT -> band?.channels?.isNotEmpty() == true
            RemoteAction.BAND_PREV, RemoteAction.BAND_NEXT, RemoteAction.CHOOSE -> ui.bands.isNotEmpty()
            RemoteAction.CONTROLS, RemoteAction.FOCUS_DOWN, RemoteAction.FREQUENCY, RemoteAction.SETTINGS, RemoteAction.MUTE -> true
            // Receive only on a TV for now (datv_receive_only): Play/Pause held says so.
            else -> false
        }
        override fun perform(f: Fired) {
            when (f.action) {
                RemoteAction.CHANNEL_PREV -> channel(-1)
                RemoteAction.CHANNEL_NEXT -> channel(+1)
                RemoteAction.BAND_PREV -> bandBy(-1)
                RemoteAction.BAND_NEXT -> bandBy(+1)
                RemoteAction.CHOOSE -> choosing = true
                RemoteAction.CONTROLS -> overlay = !overlay
                RemoteAction.FOCUS_DOWN -> { overlay = true; runCatching { tune.requestFocus() } }
                RemoteAction.FREQUENCY -> typing = true
                RemoteAction.SETTINGS -> panel = true
                RemoteAction.MUTE -> d.player.mute(!d.player.state.value.muted)
                else -> {}
            }
        }
    }
    val keys = rememberRemoteKeys(face, overlayOpen = panel || choosing || typing, onBack = onBack)
    BackHandler(enabled = !panel && overlay) { overlay = false }
    LaunchedEffect(overlay, panel, choosing, keys.legend) {
        if (panel || choosing || keys.legend) return@LaunchedEffect
        delay(50)
        runCatching { if (overlay) tune.requestFocus() else root.requestFocus() }
    }
    LaunchedEffect(d) { d.messages.collect { banner = it } }
    LaunchedEffect(banner) { if (banner != null) { delay(3000); banner = null } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(root)
            .focusProperties { canFocus = !panel }
            // The picture is the surface: with focus here every key of the map acts (RemoteKeys).
            .onFocusChanged { keys.onSurface = it.isFocused }
            .remoteKeys(keys)
            .focusable(!panel),
    ) {
        VideoSurface(d.player)
        if (overlay && !panel) {
            Column(
                Modifier.align(Alignment.TopStart).padding(48.dp)
                    .background(Color(0xB0000000), RoundedCornerShape(16.dp)).padding(28.dp).widthIn(max = 760.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.shack_engine_title, d.app.name, d.controllerName),
                    color = Color.White.copy(alpha = 0.8f), fontSize = 20.sp)
                DatvOverlayLines(ui, player, big = true)
                if (player.muted) Text(stringResource(R.string.shack_muted), color = Dim, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    TvItem(onClick = { panel = true }, focus = tune) { Text(stringResource(R.string.datv_tune), fontSize = 18.sp) }
                    Text(stringResource(R.string.datv_tv_keys), color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp)
                }
            }
        }
        if (panel) TvDatvPanel(d, ui, Modifier.align(Alignment.CenterEnd), onType = { typing = true }, onClose = { panel = false })
        if (choosing) {
            // Up: the bands over the band's channels, the ones in use marked.
            val here = datvChannelAt(ui.bands, st?.rxFreqHz)
            ChoiceOverlay(stringResource(R.string.datv_choose_title),
                listOf(ui.bands.map { Choice("b:" + it.name, it.name, selected = it == band) },
                    band?.channels.orEmpty().mapIndexed { i, c -> Choice("c:$i", dialText(c.freqHz), selected = c == here) })
                    .filter { it.isNotEmpty() },
                onPick = { c ->
                    if (c.key.startsWith("b:")) d.band(c.key.drop(2))
                    else band?.channels?.getOrNull(c.key.drop(2).toInt())?.let { d.tune(it.freqHz) }
                },
                onClose = { choosing = false })
        }
        banner?.let {
            Text(it, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp)
                    .background(Color(0xC0000000), RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 18.dp))
        }
        RemoteKeysHost(keys, face)
    }
    if (typing) FrequencyDialog(onTune = { d.tune(it); typing = false }, onDismiss = { typing = false })
}

@Composable
private fun TvDatvPanel(d: DatvSession, ui: DatvUi, modifier: Modifier, onType: () -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    val st = ui.status
    Column(
        modifier.fillMaxHeight().width(660.dp).background(Color(0xEB101010))
            .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.datv_tune_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        if (st != null) {
            Text(datvTuning(st), fontSize = 22.sp)
            Text(datvLock(st), color = lockColour(st), fontSize = 18.sp)
        }
        TvSection(stringResource(R.string.datv_band))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ui.bands.forEachIndexed { i, b ->
                TvItem(onClick = { d.band(b.name) }, selected = st?.band == b.name, focus = if (i == 0) first else null) {
                    Text(b.name, fontSize = 18.sp)
                }
            }
        }
        val band = ui.bands.firstOrNull { it.name == st?.band }
        val here = datvChannelAt(ui.bands, st?.rxFreqHz)
        if (band != null && band.channels.isNotEmpty()) {
            TvSection(stringResource(R.string.datv_channels))
            band.channels.forEach { c ->
                TvItem(onClick = { d.tune(c.freqHz) }, selected = c == here, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.datv_channel, dialText(c.freqHz), c.label), fontSize = 17.sp)
                }
            }
        }
        TvSection(stringResource(R.string.datv_rate_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DATV_RATES.forEach { r ->
                TvItem(onClick = { d.setRate(r) }, selected = st?.rxSr == r) { Text(stringResource(R.string.datv_rate, r / 1000), fontSize = 17.sp) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TvItem(onClick = onType) { Text(stringResource(R.string.shack_frequency), fontSize = 17.sp) }
            if (st != null) {
                TvItem(onClick = { if (st.running) d.stop() else d.start() }) {
                    Text(stringResource(if (st.running) R.string.datv_stop else R.string.datv_start), fontSize = 17.sp)
                }
            }
            TvItem(onClick = onClose) { Text(stringResource(R.string.shack_done), fontSize = 17.sp) }
        }
        Text(stringResource(R.string.datv_receive_only), color = Dim, fontSize = 15.sp)
    }
}
