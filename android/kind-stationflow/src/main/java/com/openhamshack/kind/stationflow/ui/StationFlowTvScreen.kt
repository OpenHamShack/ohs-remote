package com.openhamshack.kind.stationflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.core.net.AuthLevel
import com.openhamshack.core.net.SocketState
import com.openhamshack.core.tv.Amber
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.Immersive
import com.openhamshack.core.tv.KeepScreenOn
import com.openhamshack.core.tv.OpenHamShackTvTheme
import com.openhamshack.core.tv.LocalTvInert
import com.openhamshack.core.tv.LockGreen
import com.openhamshack.core.tv.OnAirRed
import com.openhamshack.core.tv.ScrollOnlyAsNeeded
import com.openhamshack.core.tv.TvFocus
import com.openhamshack.core.tv.TvFaceH
import com.openhamshack.core.tv.TvFaceV
import com.openhamshack.core.tv.TvItem
import com.openhamshack.core.tv.TvSafeH
import com.openhamshack.core.tv.TvSafeV
import com.openhamshack.core.tv.rememberBackRouting
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.api.Heard
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.radio.FaceFocus
import com.openhamshack.kind.stationflow.radio.FaceHost
import com.openhamshack.kind.stationflow.radio.RadioFaceTv
import com.openhamshack.kind.stationflow.radio.faceFor
import com.openhamshack.kind.stationflow.bandOf
import com.openhamshack.kind.stationflow.formatHz
import com.openhamshack.kind.stationflow.formatStep
import com.openhamshack.kind.stationflow.sUnits
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** How long the remote must be left alone before a new SSTV picture takes the screen. */
private const val SSTV_IDLE_MS = 15_000L

/** What is over the main screen. */
private enum class Panel { None, Tune, Heard, Sstv }

/** Which control has focus, for the hint line. */
private enum class Hint { Dial, Chips, Strip }

/**
 * StationFlow on a TV, receive only.
 *
 * The main screen is a radio's front panel seen from across the room: the
 * frequency as large as the screen allows, the mode under it, the S-meter
 * beside it, the band scope and waterfall across the full width, and the
 * latest decodes along the bottom. The dial has focus from the start:
 *
 * - left/right tunes by the step (held, it runs on);
 * - OK cycles the step (10 Hz ... 100 kHz);
 * - down reaches the chips (mode, filter, band, VFO, mute, then the Tune,
 *   Decodes and SSTV screens), each OK cycling its value or opening its
 *   screen, and below them the decodes strip, where OK tunes to a station.
 *
 * Up/down are left as movement rather than given to band or mode: a TV user
 * presses down expecting to go down, and a key that silently retuned the
 * radio instead would be the one surprise this screen must not have. Band
 * and mode are a press away on the chips, and the remote's channel keys (and
 * page up/down) change band from anywhere. The hint line says all of this
 * for whatever has focus.
 *
 * That is the plain layout, for a radio without a face of its own. The
 * IC-9700, IC-7610 and IC-705 faces (RadioFaceTv) use the remote's standard
 * map instead (REMOTE-KEYS.md, core keys/).
 *
 * Audio plays while this is on screen and stops when the app is not (the
 * session follows the lifecycle). The TV's own volume keys set the volume;
 * play/pause mutes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationFlowTvScreen(session: StationFlowSession, onExit: () -> Unit, onSignIn: (() -> Unit)? = null) {
    OpenHamShackTvTheme {
        LifecycleStartEffect(session) {
            session.foreground(true)
            onStopOrDispose { session.foreground(false) }
        }
        KeepScreenOn()
        Immersive()
        Surface(Modifier.fillMaxSize().then(rememberBackRouting()), color = Color.Black) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides ScrollOnlyAsNeeded) {
                Main(session, onExit, onSignIn)
            }
        }
    }
}

@Composable
private fun Main(s: StationFlowSession, onExit: () -> Unit, onSignIn: (() -> Unit)?) {
    val state by s.state.collectAsStateWithLifecycle()
    val pending by s.pendingHz.collectAsStateWithLifecycle()
    val chosenStep by s.step.collectAsStateWithLifecycle()
    val link by s.link.collectAsStateWithLifecycle()
    val muted by s.muted.collectAsStateWithLifecycle()
    val audio by s.audio.collectAsStateWithLifecycle()
    val stats by s.player.stats.collectAsStateWithLifecycle()
    val decodes by s.decodes.collectAsStateWithLifecycle()
    val spots by s.spots.collectAsStateWithLifecycle()
    val sstv by s.sstv.collectAsStateWithLifecycle()

    var panel by remember { mutableStateOf(Panel.None) }
    var hint by remember { mutableStateOf(Hint.Dial) }
    var banner by remember { mutableStateOf<String?>(null) }
    var autoShowSstv by remember { mutableStateOf(true) }
    var showPicture by remember { mutableStateOf<String?>(null) }   // picture id full screen
    val dial = remember { FocusRequester() }
    val modeChip = remember { FocusRequester() }
    // Back at the dial, the chips scroll home, so Mode is the chip in view
    // under it rather than whatever the row was last scrolled to.
    val chipRow = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(hint) { if (hint == Hint.Dial) chipRow.animateScrollToItem(0) }

    BackHandler(enabled = panel == Panel.None && showPicture == null) { onExit() }
    val resources = LocalResources.current
    LaunchedEffect(s) { s.messages.collect { banner = it.text(resources) } }
    LaunchedEffect(banner) { if (banner != null) { delay(3500); banner = null } }
    // The dial has focus from the start, and again whenever a panel closes:
    // otherwise the first key after Back goes nowhere.
    LaunchedEffect(panel, showPicture) {
        if (panel == Panel.None && showPicture == null) { delay(60); runCatching { dial.requestFocus() } }
    }
    // A new SSTV picture comes up full screen, if wanted - but only when the
    // remote has been left alone for a while. On the emulator a picture
    // arriving mid-tune took the screen and the next presses stepped through
    // pictures instead of tuning; somebody using the remote is told instead.
    var lastKeyAt by remember { mutableStateOf(0L) }
    LaunchedEffect(sstv.arrived) {
        val a = sstv.arrived ?: return@LaunchedEffect
        val idle = System.currentTimeMillis() - lastKeyAt > SSTV_IDLE_MS
        if (autoShowSstv && panel == Panel.None && showPicture == null && idle) showPicture = a.id
        else banner = resources.getString(R.string.tv_sstv_arrived, a.mode)
        s.clearArrived()
    }

    val st = state
    val step = chosenStep ?: s.effectiveStep()
    val viewOnly = st?.auth?.effective == AuthLevel.View

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { lastKeyAt = System.currentTimeMillis(); false }.onKeyEvent { e ->
            // Keys the focused control left alone, from anywhere on the main screen - the plain
            // layout's only: a radio with a face has the remote's standard map (RadioFaceTv).
            if (faceFor(st?.modelKey) != null) return@onKeyEvent false
            if (panel != Panel.None || showPicture != null || e.type != KeyEventType.KeyDown) return@onKeyEvent false
            val first = e.nativeKeyEvent.repeatCount == 0
            when (e.key) {
                Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { if (first) s.toggleMute(); true }
                Key.ChannelUp, Key.PageUp -> { if (first) s.cycleBand(+1); true }
                Key.ChannelDown, Key.PageDown -> { if (first) s.cycleBand(-1); true }
                Key.MediaFastForward -> { s.tuneBy(+1); true }
                Key.MediaRewind -> { s.tuneBy(-1); true }
                Key.Menu -> { if (first) panel = Panel.Tune; true }
                else -> false
            }
        },
    ) {
        // Under any panel the main screen keeps drawing (the Tune panel sits
        // beside it) but takes no focus; see LocalTvInert.
        CompositionLocalProvider(LocalTvInert provides (panel != Panel.None || showPicture != null)) {
        // A radio with a face of its own (IC-9700, IC-7610, IC-705) gets its own screen, laid out as
        // the radio's is (radio/Faces.kt, docs/RADIO-DISPLAYS.md); any other radio, the plain layout below.
        val face = faceFor(st?.modelKey)
        if (face != null) {
            RadioFaceTv(
                s, face, st, pending, step, muted,
                linkProblem = (link as? SocketState.Down)?.let { stringResource(R.string.link_not_answering, it.why) },
                dial = dial, viewOnly = viewOnly,
                host = object : FaceHost {
                    override fun openTune() { panel = Panel.Tune }
                    override fun openDecodes() { panel = Panel.Heard }
                    override fun openSstv() { panel = Panel.Sstv }
                    override fun focusChanged(what: FaceFocus) {}
                    // The face keeps its own overlays (RadioFaceTv); nothing for this screen to open.
                    override fun open(panel: com.openhamshack.kind.stationflow.radio.FacePanel) {}
                },
                modifier = Modifier.fillMaxSize().padding(horizontal = TvFaceH, vertical = TvFaceV),
            )
        } else
        Column(Modifier.fillMaxSize().padding(horizontal = TvSafeH, vertical = TvSafeV)) {
            Header(s.title, st, link, muted, audio, stats.level, stats.playing)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Dial(
                    st, pending, step, dial,
                    // Down from the dial always lands on Mode, the first chip,
                    // not whichever chip happens to sit under the dial's centre.
                    Modifier.weight(1f).focusProperties { down = modeChip },
                    onTune = { s.tuneBy(it) }, onStep = { s.cycleStep() },
                    onFocus = { if (it) hint = Hint.Dial },
                )
                Spacer(Modifier.width(24.dp))
                SMeter(st, Modifier.width(280.dp))
            }
            Chips(s, st, muted, modeChip, chipRow, onFocus = { hint = Hint.Chips },
                onTune = { panel = Panel.Tune }, onHeard = { panel = Panel.Heard }, onSstv = { panel = Panel.Sstv })
            ScopeView(
                s.waterfall, pending ?: st?.freqHz, pending != null,
                message = when {
                    st == null -> null
                    !st.connected -> stringResource(R.string.session_no_radio)
                    else -> null
                },
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp).clip(RoundedCornerShape(6.dp)),
            )
            Strip(decodes.ifEmpty { spots }, decodes.isEmpty(), onFocus = { hint = Hint.Strip }) { s.tuneToHeard(it) }
            Text(
                hintText(hint, step, viewOnly),
                color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        }

        when (panel) {
            Panel.Tune -> TunePanel(s, st, onClose = { panel = Panel.None }, onSignIn = onSignIn,
                modifier = Modifier.align(Alignment.CenterEnd))
            Panel.Heard -> HeardScreen(s, onClose = { panel = Panel.None })
            Panel.Sstv -> SstvScreen(s, autoShowSstv, { autoShowSstv = it }, onClose = { panel = Panel.None })
            Panel.None -> {}
        }
        showPicture?.let { id ->
            SstvViewer(s, sstv.pictures, id, onClose = { showPicture = null })
        }
        if (st?.transmitting == true) {
            // Shown, never caused: something else (the web page, WSJT-X) has the radio on air.
            Text(
                stringResource(R.string.radio_on_air), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .background(OnAirRed, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        banner?.let {
            Text(
                it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp).widthIn(max = 900.dp)
                    .background(Color(0xE0101010), RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
    }
}

/** The key hints for what has focus; view only puts its reason first, as one more hint. */
@Composable
private fun hintText(h: Hint, step: Long, viewOnly: Boolean): String {
    val hint = when (h) {
        Hint.Dial -> stringResource(R.string.tv_hint_dial, formatStep(step))
        Hint.Chips -> stringResource(R.string.tv_hint_chips)
        Hint.Strip -> stringResource(R.string.tv_hint_strip)
    }
    return if (viewOnly) stringResource(R.string.hint_view_only) + "  ·  " + hint else hint
}

@Composable
private fun Header(
    title: String, st: RadioState?, link: SocketState, muted: Boolean,
    audio: com.openhamshack.kind.stationflow.AudioState, level: Float, playing: Boolean,
) {
    var now by remember { mutableStateOf(ZonedDateTime.now(ZoneOffset.UTC)) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = ZonedDateTime.now(ZoneOffset.UTC) } }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(st?.name?.ifBlank { null } ?: title, st?.version?.ifBlank { null }?.let { stringResource(R.string.tv_module_version, it) })
                .joinToString("  ·  "),
            color = Color.White.copy(alpha = 0.85f), fontSize = 18.sp, modifier = Modifier.weight(1f),
        )
        val (word, colour) = when {
            link is SocketState.Down -> stringResource(R.string.link_not_answering, link.why) to Amber
            link is SocketState.Connecting || st == null -> stringResource(R.string.link_connecting) to Dim
            st.switching -> stringResource(R.string.tv_radio_switching) to Amber
            // The module's link state is its own word ("connecting"), shown as sent.
            !st.connected -> {
                val state = st.linkState.ifBlank { null }
                val err = st.error
                stringResource(when {
                    state == null && err == null -> R.string.tv_radio_not_connected
                    state == null -> R.string.tv_radio_not_connected_why
                    err == null -> R.string.tv_radio_state
                    else -> R.string.tv_radio_state_why
                }, *listOfNotNull(state, err).toTypedArray()) to Amber
            }
            else -> stringResource(R.string.tv_radio_connected) to LockGreen
        }
        Text(word, color = colour, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 420.dp))
        Spacer(Modifier.width(18.dp))
        AudioBadge(muted, audio, level, playing)
        Spacer(Modifier.width(18.dp))
        Text(stringResource(R.string.tv_clock_utc, now.hour, now.minute, now.second), color = Dim, fontSize = 16.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AudioBadge(muted: Boolean, a: com.openhamshack.kind.stationflow.AudioState, level: Float, playing: Boolean) {
    val text = when {
        a.error != null -> stringResource(R.string.audio_error, a.error)
        muted -> stringResource(R.string.audio_muted)
        a.link is SocketState.Up && playing -> stringResource(R.string.audio_playing, a.rate / 1000)
        a.link is SocketState.Up -> stringResource(R.string.audio_buffering)
        else -> stringResource(R.string.audio_connecting)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = if (muted || a.error != null) Amber else Color.White.copy(alpha = 0.85f), fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
        if (!muted && a.error == null) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.width(60.dp).height(8.dp).background(Color(0xFF222222), RoundedCornerShape(4.dp))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((level * 4f).coerceIn(0f, 1f))
                    .background(LockGreen, RoundedCornerShape(4.dp)))
            }
        }
    }
}

/** The frequency, big, and the mode under it. Left/right tunes; OK cycles the step. */
@Composable
private fun Dial(
    st: RadioState?, pending: Long?, step: Long, focus: FocusRequester, modifier: Modifier,
    onTune: (Int) -> Unit, onStep: () -> Unit, onFocus: (Boolean) -> Unit,
) {
    TvItem(
        onClick = onStep, focus = focus, onFocus = onFocus,
        modifier = modifier.onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { onTune(-1); true }
                Key.DirectionRight -> { onTune(+1); true }
                else -> false
            }
        },
    ) {
        Text("◀", color = Dim, fontSize = 28.sp)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            // As large as fits: a 1296 MHz frequency is a digit wider than a
            // 2 m one, and a fixed size either clipped its last digit or left
            // the 2 m one small. Never wraps.
            BasicText(
                formatHz(pending ?: st?.freqHz),
                style = TextStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = if (pending != null) Amber else Color.White),
                maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 30.sp, maxFontSize = 64.sp, stepSize = 2.sp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(st?.shownMode ?: "–", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TvFocus)
                Text(st?.filter ?: "", fontSize = 22.sp, color = Color.White.copy(alpha = 0.8f))
                Text(if (st?.vfo == "VFOB") "VFO B" else "VFO A", fontSize = 22.sp, color = Color.White.copy(alpha = 0.8f))
                if (st?.split == true) Text("SPLIT", fontSize = 22.sp, color = Amber, fontWeight = FontWeight.Bold)
                bandOf(st?.bands.orEmpty(), pending ?: st?.freqHz)?.let { Text(it.name, fontSize = 22.sp, color = Dim) }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(R.string.tv_step), color = Dim, fontSize = 13.sp)
            Text(formatStep(step), fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Text("▶", color = Dim, fontSize = 28.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

/** S-units in words and a bar, S1 to S9+60, readable from the sofa. */
@Composable
private fun SMeter(st: RadioState?, modifier: Modifier) {
    val frac = st?.smeter?.toFloat() ?: 0f
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(sUnits(st?.smeterDb), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(st?.smeterDb?.let { "%+.0f dB".format(it) } ?: "", color = Dim, fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A1A1A))) {
            // S9 is 120/241 of the raw scale: green to there, red beyond.
            val s9 = 120f / 241f
            Box(Modifier.fillMaxHeight().fillMaxWidth(minOf(frac, s9)).background(LockGreen))
            if (frac > s9) {
                Row(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(s9))
                    Box(Modifier.weight((frac - s9).coerceAtLeast(0.001f)).fillMaxHeight().background(OnAirRed))
                    Spacer(Modifier.weight((1f - frac).coerceAtLeast(0.001f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("1", "3", "5", "7", "9", "+20", "+40", "+60").forEach {
                Text(it, color = Dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Chips(
    s: StationFlowSession, st: RadioState?, muted: Boolean, modeFocus: FocusRequester,
    rowState: androidx.compose.foundation.lazy.LazyListState, onFocus: () -> Unit,
    onTune: () -> Unit, onHeard: () -> Unit, onSstv: () -> Unit,
) {
    // A LazyRow, not a Row: on a narrower TV (or with a long mode name) a
    // Row squeezed its last chip to nothing, which then wrapped tall and took
    // the waterfall's height. Here the row scrolls instead, as far as focus needs.
    val f: (Boolean) -> Unit = { if (it) onFocus() }
    LazyRow(Modifier.fillMaxWidth().padding(top = 8.dp), state = rowState, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        item { TvItem(onClick = { s.cycleMode() }, focus = modeFocus, onFocus = f) { Label(stringResource(R.string.chip_mode), st?.shownMode ?: "–") } }
        item { TvItem(onClick = { s.cycleFilter() }, onFocus = f) { Label(stringResource(R.string.chip_filter), st?.filter ?: "–") } }
        item { TvItem(onClick = { s.cycleBand() }, onFocus = f) { Label(stringResource(R.string.chip_band), bandOf(st?.bands.orEmpty(), st?.freqHz)?.name ?: "–") } }
        item {
            TvItem(onClick = { s.setVfo(if (st?.vfo == "VFOB") "VFOA" else "VFOB") }, onFocus = f) {
                Label(stringResource(R.string.chip_vfo), if (st?.vfo == "VFOB") "B" else "A")
            }
        }
        // The receive front end, for a radio without a face of its own (the IC-7600): only what its
        // model lists, with what the radio last said.
        if (st?.preamps?.isNotEmpty() == true) {
            item { TvItem(onClick = { s.cyclePreamp() }, onFocus = f) { Label(stringResource(R.string.chip_preamp), st.preampName ?: "–") } }
        }
        if (st?.attenuators?.isNotEmpty() == true) {
            item { TvItem(onClick = { s.cycleAtt() }, onFocus = f) { Label(stringResource(R.string.chip_att), st.attName ?: "–") } }
        }
        item { TvItem(onClick = { s.toggleMute() }, onFocus = f, selected = muted) { Label(stringResource(R.string.chip_audio), stringResource(if (muted) R.string.audio_muted else R.string.chip_audio_on)) } }
        item { TvItem(onClick = onTune, onFocus = f) { Text(stringResource(R.string.chip_tune), fontSize = 18.sp, maxLines = 1) } }
        item { TvItem(onClick = onHeard, onFocus = f) { Text(stringResource(R.string.chip_decodes), fontSize = 18.sp, maxLines = 1) } }
        item { TvItem(onClick = onSstv, onFocus = f) { Text(stringResource(R.string.chip_sstv), fontSize = 18.sp, maxLines = 1) } }
    }
}

/** A chip's name and value on one line: two-line chips cost the waterfall its height. */
@Composable
private fun Label(name: String, value: String) {
    Text(name, color = Dim, fontSize = 15.sp)
    Spacer(Modifier.width(8.dp))
    Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/** The latest decodes (or, with none, stations heard) along the bottom; OK tunes to one. */
@Composable
private fun Strip(list: List<Heard>, areSpots: Boolean, onFocus: () -> Unit, onTune: (Heard) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(stringResource(if (areSpots) R.string.strip_spots else R.string.strip_decodes), color = Dim, fontSize = 13.sp)
        if (list.isEmpty()) {
            Text(stringResource(R.string.strip_empty), color = Dim, fontSize = 15.sp,
                modifier = Modifier.padding(top = 4.dp))
            return
        }
        LazyRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list.take(12), key = { it.key }) { h ->
                // One line per card: the message is in the full list (Decodes…).
                TvItem(onClick = { onTune(h) }, onFocus = { if (it) onFocus() }, modifier = Modifier.widthIn(max = 420.dp)) {
                    Text(h.call ?: "?", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (h.forMe) Amber else if (h.cq) LockGreen else Color.White)
                    h.snr?.let { Text("  %+d".format(it), color = Dim, fontSize = 15.sp) }
                    Text("  " + h.text, fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
