package com.openhamshack.remote.ui

import androidx.compose.foundation.clickable
import com.openhamshack.core.tv.ChoiceOverlay
import com.openhamshack.core.tv.Choice
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.core.tv.TvFaceH
import com.openhamshack.core.tv.FaceKeys
import com.openhamshack.core.tv.RemoteKeysHost
import com.openhamshack.core.tv.remoteKeys
import com.openhamshack.core.tv.rememberRemoteKeys
import com.openhamshack.core.keys.FaceClass
import com.openhamshack.core.keys.Fired
import com.openhamshack.core.keys.RemoteAction
import com.openhamshack.core.tv.TvFaceV
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.DecoderPanel
import com.openhamshack.remote.shack.FlexLink
import com.openhamshack.remote.shack.FlexSstvImage
import com.openhamshack.remote.shack.FlexSession
import com.openhamshack.remote.shack.FlexDspTable
import com.openhamshack.remote.shack.FlexTuning
import com.openhamshack.remote.shack.FlexUi
import com.openhamshack.remote.shack.Scope
import kotlin.math.roundToLong
import com.openhamshack.core.scope.ScopeLook
import com.openhamshack.core.scope.rememberScopeLook
import com.openhamshack.core.scope.TraceAverage
import com.openhamshack.remote.shack.FlexScopeControl
import com.openhamshack.remote.shack.FlexPlanStrip
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.platform.LocalDensity

/**
 * OpenHamShack Flex on a TV, receive only, and calm: the frequency as large
 * as the screen allows, the mode and the S-meter beside it, and the
 * panadapter with its waterfall taking everything else. Nothing more is on
 * the screen until OK is pressed (Brian found the NB screens too busy; this
 * kind starts from the quiet end).
 *
 * The keys are the remote's standard map (core keys/, REMOTE-KEYS.md §4),
 * from the display, where focus stays: Left/Right tune by the step (held,
 * it runs on); Up chooses a mode or band (one grid, as the radio's own
 * screen); Down puts the display in adjust mode (amber edge), where Up/Down
 * move the reference level, Left/Right zoom, OK opens the scope page and
 * Back ends it (Brian, 10 October 2026: zoom, and the level easy to
 * adjust); OK opens the extras - the modes, the bands, and a row of the
 * radio's receive controls (mute, NR, NR2, noise, AGC, RF gain, antenna,
 * scope, decoder, SSTV, pictures, the key legend), each opening its own
 * page at the right; OK held types a frequency; rewind/fast-forward change
 * the step; the channel keys change band; Play/Pause mutes; Menu opens the
 * Noise page (the settings), held the legend. Back closes a page, then the
 * extras, then leaves for the apps. Until 12/10/2026 Up/Down changed the
 * step, OK held was adjust mode, rewind/FF tuned and Menu switched NR,
 * which is now in the extras.
 *
 * A running decoder's panel docks beside the display (the decodes, or the
 * SSTV picture as it arrives) and opens by itself as NB/Radio's do; it
 * takes no focus, so the D-pad goes on tuning.
 *
 * While anything is open over the display, the display cannot take focus,
 * so the D-pad cannot reach something hidden (GUIDE 5).
 */
@Composable
internal fun TvFlex(f: FlexSession, onBack: () -> Unit) {
    val u by f.ui.collectAsStateWithLifecycle()
    LifecycleStartEffect(f) {
        f.foreground(true)
        onStopOrDispose { f.foreground(false) }
    }
    if (keepAwake(u.audioRate, u.muted, keyed = false)) KeepScreenOn()
    var extras by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf<FlexPage?>(null) }
    // The extras item a page was opened from, focused again when BACK returns
    // to the extras: landing on the first mode instead meant the next OK
    // changed the radio's mode (seen on the emulator).
    var openedFrom by remember { mutableStateOf<FlexPage?>(null) }
    val pageItems = remember { FlexPage.entries.associateWith { FocusRequester() } }
    var fullDecodes by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf<FlexSstvImage?>(null) }
    val covered = extras || page != null || fullDecodes || picture != null
    var said by remember { mutableStateOf<String?>(null) }
    val display = remember { FocusRequester() }
    val firstExtra = remember { FocusRequester() }
    var adjusting by remember { mutableStateOf(false) }
    val (look, setLook) = rememberScopeLook(flexLookKey(f, u))
    var choosing by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(covered) { if (covered) adjusting = false }
    LaunchedEffect(f) { f.messages.collect { said = it } }
    LaunchedEffect(said) { if (said != null) { delay(3500); said = null } }
    val face = object : FaceKeys {
        override val faceClass = FaceClass.RADIO
        override fun can(a: RemoteAction): Boolean = when (a) {
            RemoteAction.TUNE_DOWN, RemoteAction.TUNE_UP, RemoteAction.STEP_DOWN, RemoteAction.STEP_UP,
            RemoteAction.BAND_PREV, RemoteAction.BAND_NEXT, RemoteAction.CONTROLS, RemoteAction.FREQUENCY,
            RemoteAction.SETTINGS, RemoteAction.MUTE -> true
            RemoteAction.CHOOSE -> u.tables.modes.isNotEmpty() || u.tables.bands.isNotEmpty()
            RemoteAction.FOCUS_DOWN -> u.state?.pan?.hasScopeSettings == true
            // Receive only (REMOTE-CONTRACT 13): Play/Pause held says so.
            else -> false
        }
        override fun perform(fired: Fired) {
            when (fired.action) {
                RemoteAction.TUNE_DOWN -> f.tuneBy(-1)
                RemoteAction.TUNE_UP -> f.tuneBy(+1)
                RemoteAction.STEP_DOWN -> f.stepBy(-1)
                RemoteAction.STEP_UP -> f.stepBy(+1)
                RemoteAction.BAND_PREV -> f.cycleBand(-1)
                RemoteAction.BAND_NEXT -> f.cycleBand(+1)
                RemoteAction.CHOOSE -> choosing = true
                RemoteAction.FOCUS_DOWN -> adjusting = true
                RemoteAction.CONTROLS -> extras = true
                RemoteAction.FREQUENCY -> typing = true
                RemoteAction.SETTINGS -> { page = FlexPage.NOISE; openedFrom = FlexPage.NOISE }
                RemoteAction.MUTE -> f.toggleMute()
                else -> {}
            }
        }
        @Composable override fun label(a: RemoteAction): String? = when (a) {
            RemoteAction.CHOOSE -> stringResource(R.string.flex_key_choose)
            RemoteAction.FOCUS_DOWN -> stringResource(R.string.flex_key_focus_down)
            else -> null
        }
    }
    val keys = rememberRemoteKeys(face, overlayOpen = covered || choosing || typing || adjusting, onBack = onBack)
    // The face's own: the extras and adjust mode close before the keys' stack and before leaving.
    BackHandler(enabled = extras || adjusting) { if (extras) extras = false else adjusting = false }
    LaunchedEffect(extras, covered, choosing, keys.legend) {
        delay(80)
        runCatching {
            when {
                extras -> (openedFrom?.let { pageItems[it] } ?: firstExtra).requestFocus()
                !covered && !choosing && !keys.legend -> display.requestFocus()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).remoteKeys(keys)) {
        Column(Modifier.fillMaxSize().padding(horizontal = TvFaceH, vertical = TvFaceV)) {
            FlexHeader(f, u, big = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlexDial(u, Modifier.weight(1f), maxSize = 84)
                Spacer(Modifier.width(24.dp))
                FlexSMeter(u.sDbm ?: u.slice?.levelDbm?.toFloat(), big = true)
            }
            Row(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .focusRequester(display)
                    .focusProperties { canFocus = !covered }
                    // The display is the surface: the standard map's keys arrive at the root (RemoteKeys).
                    // Only adjust mode keeps keys of its own, and runs first because it holds focus.
                    .onFocusChanged { keys.onSurface = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (covered || !adjusting) return@onPreviewKeyEvent false
                        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                        // OK is decided on release, so the release cannot land on the page it opens.
                        if (ok) {
                            if (e.type == KeyEventType.KeyUp) { adjusting = false; page = FlexPage.SCOPE }
                            return@onPreviewKeyEvent true
                        }
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val first = e.nativeKeyEvent.repeatCount == 0
                        when (e.key) {
                            Key.DirectionUp -> { f.stepScopeRef(up = true); true }
                            Key.DirectionDown -> { f.stepScopeRef(up = false); true }
                            Key.DirectionLeft -> { if (first) f.zoomScope(zoomIn = false); true }
                            Key.DirectionRight -> { if (first) f.zoomScope(zoomIn = true); true }
                            else -> false
                        }
                    }
                    .focusable(),
            ) {
                FlexScopeView(f, u, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)), look, adjusting, big = true)
                FlexProblem(u, Modifier.align(Alignment.Center))
                if (adjusting) FlexScopeReadout(u, Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
            }
            val shown = u.panel.shown
            if (shown != DecoderPanel.NONE) {
                // The list needs room for its messages; SSTV's picture less (as NB/Radio's panel).
                FlexDockedPanel(f, u, big = true, modifier = Modifier.weight(if (shown == DecoderPanel.SLOTS) 0.9f else 0.6f).fillMaxHeight())
            }
            }
            // The extras panel has its own hint; two would show through each other.
        }
        // Shown when the screen opens and for a moment after the step
        // changes (it names the step), then faded, as on NB/Radio. Over the
        // foot of the waterfall rather than on a line of its own: that line
        // cost the display its height for words that matter for a moment.
        var hint by remember { mutableStateOf(true) }
        LaunchedEffect(u.step) { hint = true; delay(6_000); hint = false }
        if (!covered && (hint || adjusting)) {
            Text(
                if (adjusting) stringResource(R.string.flex_tv_hint_adjust) else stringResource(R.string.flex_tv_hint, stepText(u.step)),
                color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TvFaceV + 6.dp)
                    .background(Color(0xC0000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
        // Closed with BACK from the display: the next OK starts at the modes again.
        LaunchedEffect(covered) { if (!covered) openedFrom = null }
        if (extras) FlexExtrasTv(f, u, firstExtra, Modifier.align(Alignment.BottomCenter),
            pageItems, onPage = { extras = false; page = it; openedFrom = it }, onDecodes = { extras = false; fullDecodes = true },
            onKeys = { extras = false; keys.legend = true })
        page?.let { p ->
            TvFlexSide(f, u, p, Modifier.align(Alignment.CenterEnd), look = look, setLook = setLook,
                // Back from a page is back to the extras it was opened from.
                onClose = { page = null; extras = true },
                onPicture = { picture = it })
        }
        said?.let {
            Text(it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp).widthIn(max = 900.dp)
                    .background(Color(0xE0101010), RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 14.dp))
        }
        if (choosing) {
            // Up: the modes over the bands, one grid as the radio's own screen.
            val band = u.tables.band(u.dialHz)
            ChoiceOverlay(stringResource(R.string.flex_choose_title),
                listOf(u.tables.modes.map { Choice("m:" + it.name, it.label, selected = it.name == u.slice?.mode) },
                    u.tables.bands.map { Choice("b:" + it.name, it.label, selected = it == band) }).filter { it.isNotEmpty() },
                onPick = { c -> if (c.key.startsWith("m:")) f.setMode(c.key.drop(2)) else f.cycleBandTo(c.key.drop(2)) },
                onClose = { choosing = false })
        }
        RemoteKeysHost(keys, face)
    }
    if (fullDecodes) TvDecodesPanel(u.decodes, u.state?.callsign.orEmpty(), onClose = { fullDecodes = false; openedFrom = FlexPage.DECODER; extras = true })
    if (typing) FrequencyDialog(onTune = { hz -> f.tuneTo(hz.toLong()); typing = false }, onDismiss = { typing = false })
    picture?.let { img -> FlexPictureFull(f, img, onClose = { picture = null }) }
}

/**
 * The extras, one opaque panel at the bottom: modes, bands, then the radio's
 * receive controls (FlexExtrasControls). Up/Down between rows, Left/Right along.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlexExtrasTv(f: FlexSession, u: FlexUi, first: FocusRequester, modifier: Modifier,
                         pageItems: Map<FlexPage, FocusRequester>, onPage: (FlexPage) -> Unit, onDecodes: () -> Unit,
                         onKeys: () -> Unit) {
    val band = u.tables.band(u.dialHz)
    Column(
        modifier.fillMaxWidth().background(Color(0xFF101418)).padding(horizontal = TvSafeH, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.flex_mode), color = Dim, fontSize = 15.sp)
        // Every mode at once, wrapped into rows like the radio's own mode screen, rather than a row
        // scrolled to find one (Brian, 10 October 2026: "more like the radio").
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            u.tables.modes.forEach { m ->
                val on = m.name == u.slice?.mode
                TvItem(onClick = { f.setMode(m.name) }, compact = true, selected = on,
                    focus = if (on || (u.slice == null && m == u.tables.modes.first())) first else null) {
                    Text(m.label, fontSize = 18.sp)
                }
            }
        }
        Text(stringResource(R.string.flex_band), color = Dim, fontSize = 15.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(u.tables.bands, key = { it.name }) { b ->
                TvItem(onClick = { f.cycleBandTo(b.name) }, compact = true, selected = b == band) { Text(b.label, fontSize = 18.sp) }
            }
        }
        // Its own row: at the end of the bands mute was off the screen until scrolled to.
        Text(stringResource(R.string.flex_receive), color = Dim, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) { FlexExtrasControls(f, u, pageItems, onPage) }
            if (u.decoders?.on == true) {
                TvItem(onClick = onDecodes, compact = true) { Text(stringResource(R.string.flex_decodes_full), fontSize = 18.sp) }
            }
        }
        // The legend on a row of its own: in the Receive row it squeezed the radio's controls off the end.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            TvItem(onClick = onKeys, compact = true) { Text(stringResource(R.string.shack_keys_item), fontSize = 16.sp) }
            Text(stringResource(R.string.flex_extras_hint), color = Dim, fontSize = 14.sp)
        }
    }
}

/**
 * Flex on a phone or tablet: the TV's screen, plus tapping the display to
 * tune and buttons for the rest; the radio's receive controls, the decoder
 * and SSTV in one sheet ("Controls"), NR in one tap beside it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FlexPhone(f: FlexSession, onBack: () -> Unit) {
    val u by f.ui.collectAsStateWithLifecycle()
    LifecycleStartEffect(f) {
        f.foreground(true)
        onStopOrDispose { f.foreground(false) }
    }
    if (keepAwake(u.audioRate, u.muted, keyed = false)) KeepScreenOn()
    var said by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var scopeSheet by remember { mutableStateOf(false) }
    var modes by remember { mutableStateOf(false) }
    val (look, setLook) = rememberScopeLook(flexLookKey(f, u))
    var picture by remember { mutableStateOf<FlexSstvImage?>(null) }
    LaunchedEffect(f) { f.messages.collect { said = it } }
    LaunchedEffect(said) { if (said != null) { delay(3500); said = null } }
    BackHandler { onBack() }
    if (sheet) {
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            FlexControlsSheet(f, u, onPicture = { picture = it })
        }
    }
    if (scopeSheet) {
        ModalBottomSheet(onDismissRequest = { scopeSheet = false }) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlexScopeRows(f, u, look, setLook, first = null)
            }
        }
    }
    picture?.let { img -> FlexPictureFull(f, img, onClose = { picture = null }) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { FlexHeader(f, u, big = false) }
            // NR in one tap, as Menu is on a TV; the rest in the sheet.
            FilterChip(selected = u.nrOn, onClick = { f.toggleNr() }, label = { Text(stringResource(R.string.flex_nr_chip)) })
            u.state?.nr2?.takeIf { it.available }?.let { n ->
                FilterChip(selected = n.on, onClick = { f.setNr2(on = !n.on) }, label = { Text(stringResource(R.string.flex_nr2_chip)) })
            }
            TextButton(onClick = { scopeSheet = true }) { Text(stringResource(R.string.flex_scope)) }
            TextButton(onClick = { sheet = true }) { Text(stringResource(R.string.flex_controls)) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.shack_back)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlexDial(u, Modifier.weight(1f), maxSize = 44, onMode = { modes = true })
            FlexSMeter(u.sDbm ?: u.slice?.levelDbm?.toFloat(), big = false)
        }
        said?.let { Text(it, color = Amber) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            FlexScopeView(f, u, Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).pointerInput(f) {
                detectTapGestures(onLongPress = { scopeSheet = true }) { p ->
                    val sc = f.scopeData
                    if (sc.highHz > sc.lowHz) {
                        val hz = sc.lowHz + (p.x / size.width) * (sc.highHz - sc.lowHz)
                        // On the step's grid, as the knob would land.
                        val step = u.step.toLong().coerceAtLeast(1)
                        f.tuneTo((hz / step).roundToLong() * step)
                    }
                }
            }.pointerInput(f) {
                // A swipe tunes, the frequency going the way the finger goes (FlexTuning.dragged).
                // Every move is sent: tuneTo keeps only the latest while one is
                // out, so a fast swipe costs the radio no more than a tap. The
                // state is read here, not from the composition's u, which this
                // block (keyed on the session) would hold as it was at the start.
                // Each movement counts by how fast the finger is going
                // (FlexTuning.dragGain): slow to settle on a signal, quick to
                // cross the band. The speed is smoothed over a few events, as
                // one event's time is too ragged to go by on its own.
                var startHz = 0L
                var spanHz = 0L
                var dx = 0f
                var speed = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        val sc = f.scopeData
                        startHz = f.ui.value.dialHz ?: 0L
                        spanHz = (sc.highHz - sc.lowHz).toLong()
                        dx = 0f
                        speed = 0f
                    },
                ) { change, amount ->
                    if (startHz <= 0L) return@detectHorizontalDragGestures
                    change.consume()
                    val ms = (change.uptimeMillis - change.previousUptimeMillis).coerceAtLeast(1L)
                    speed = 0.6f * speed + 0.4f * (amount / density) * 1000f / ms
                    dx += amount * FlexTuning.dragGain(speed)
                    f.tuneTo(FlexTuning.dragged(startHz, dx, size.width, spanHz, f.ui.value.step))
                }
            }, look = look)
            FlexProblem(u, Modifier.align(Alignment.Center))
        }
        if (u.panel.shown != DecoderPanel.NONE) FlexDockedPanel(f, u, big = false, modifier = Modifier.fillMaxWidth())
        // Step and band share one row, each as wide as its words. They were a
        // row each with the step button and the band name stretched across
        // the screen, which on the Tab S9 was two bars of nothing under the
        // waterfall (Brian, 12/10/2026); the height goes back to the scope.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { f.tuneBy(-1) }) { Text("◀") }
            OutlinedButton(onClick = { f.stepBy(+1) }) {
                Text(stringResource(R.string.flex_step, stepText(u.step)), maxLines = 1)
            }
            OutlinedButton(onClick = { f.tuneBy(+1) }) { Text("▶") }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { f.cycleBand(-1) }) { Text("◀") }
            Text(u.tables.band(u.dialHz)?.label ?: stringResource(R.string.flex_no_band), maxLines = 1)
            OutlinedButton(onClick = { f.cycleBand(+1) }) { Text("▶") }
            OutlinedButton(onClick = { f.toggleMute() }) { Text(stringResource(if (u.muted) R.string.flex_unmute else R.string.flex_mute)) }
        }
    }
    // The radio's mode screen, as a grid over everything: a tap on the mode under the frequency opens it.
    if (modes) ChoiceOverlay(stringResource(R.string.flex_mode), flexModeRows(u), onPick = { f.setMode(it.key) },
        onClose = { modes = false })
    }
}

/**
 * One quiet line of only the things that are wrong or unusual (no radio,
 * on air, muted). "Flex on FAKESHACK" went with the NB/Radio calm pass
 * (Brian, 10/10/2026): the app was chosen a moment ago and says nothing new.
 */
@Composable
private fun FlexHeader(f: FlexSession, u: FlexUi, big: Boolean) {
    val size = if (big) 16.sp else 13.sp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val st = u.state
        val onAir = st?.onAir == true || st?.transmitting == true
        val noAudio = !u.muted && u.link == FlexLink.UP && st?.connected == true && u.audioRate == 0
        // Everything that changes what is heard, in one place and one style:
        // each of the radio's DSP functions that is on, the module's NR2,
        // and the preamp or attenuator when it is off 0 dB. NR alone used to
        // be shown, so NB or ATT on gave no sign at all (Brian, 13/10/2026).
        val dspOn = FlexDspTable.onNow(u.slice)
        val nr2On = st?.nr2?.on == true
        val rfOn = u.slice != null && u.rfGain != 0
        if (onAir) Chip(stringResource(R.string.shack_on_air_elsewhere), OnAirRed)
        if (u.viewOnly) Chip(stringResource(R.string.flex_view_only_chip), Amber)
        dspOn.forEach { Chip(dspName(it), Accent) }
        if (nr2On) Chip(stringResource(R.string.flex_nr2_chip), Accent)
        if (rfOn) Chip(rfName(u.rfGain), Accent)
        if (u.muted) Chip(stringResource(R.string.flex_muted), Amber)
        else if (noAudio) Chip(stringResource(R.string.shack_no_audio), Dim)
        // On the TV this row sits on its own above the dial, so with no chip it
        // had no height and the first one to appear (NR, most often) pushed the
        // dial, meter and scope down a line (Brian, 13/10/2026). An unseen chip
        // holds the row open instead: the same Text as a real one, so it is the
        // right height at any font scale, where a fixed dp would drift. The
        // phone's header shares its row with the NR and Scope buttons, which
        // already hold it open.
        if (big && !onAir && !u.viewOnly && dspOn.isEmpty() && !nr2On && !rfOn && !u.muted && !noAudio) Chip(" ", Color.Transparent)
    }
}

/** The frequency, as big as fits; the mode and the band under it. Amber while a tune is on its way. */
@Composable
private fun FlexDial(u: FlexUi, modifier: Modifier, maxSize: Int, onMode: (() -> Unit)? = null) {
    Column(modifier) {
        BasicText(
            dialText(u.dialHz?.toDouble()),
            style = TextStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                color = if (u.pendingHz != null) Amber else Color.White),
            maxLines = 1, softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = maxSize.sp, stepSize = 2.sp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(u.slice?.mode ?: "–", fontSize = (maxSize / 3).sp, fontWeight = FontWeight.Bold, color = TvFocus,
                modifier = if (onMode != null) Modifier.clickable(onClick = onMode).padding(end = 4.dp) else Modifier)
            u.tables.band(u.dialHz)?.let { Text(it.label, fontSize = (maxSize / 4).sp, color = Dim) }
        }
    }
}

/** S-units from dBm (S9 = -73 dBm, 6 dB a unit, the HF convention the radio's own meter uses), and a bar. */
@Composable
private fun FlexSMeter(dbm: Float?, big: Boolean) {
    Column(Modifier.width(if (big) 260.dp else 120.dp), horizontalAlignment = Alignment.End) {
        Text(sUnitsFromDbm(dbm), fontSize = if (big) 44.sp else 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        val frac = dbm?.let { ((it + 127f) / 90f).coerceIn(0f, 1f) } ?: 0f
        val s9 = 54f / 90f
        Box(Modifier.fillMaxWidth().height(if (big) 14.dp else 8.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1A1A1A))) {
            // The screen's accent, amber past S9: red on these screens means on the air.
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(if (frac > s9) Amber else Accent))
        }
    }
}

/** "S7", "S9+10"; "–" with no reading. */
internal fun sUnitsFromDbm(dbm: Float?): String {
    if (dbm == null) return "–"
    val over = dbm + 73f
    return if (over > 0) "S9+%d".format(Math.round(over / 10f) * 10)
    else "S%d".format((9 + Math.floorDiv(Math.round(over), 6)).coerceIn(0, 9))
}

/** Why there is no display: the module has no radio, or the controller cannot be reached. */
@Composable
private fun FlexProblem(u: FlexUi, modifier: Modifier) {
    val st = u.state
    val text = when {
        u.link == FlexLink.DOWN -> u.problem?.let { problemText(it) } ?: stringResource(R.string.shack_problem_network)
        st == null -> stringResource(R.string.common_connecting)
        !st.connected -> st.error?.let { stringResource(R.string.flex_no_radio_why, it) } ?: stringResource(R.string.flex_no_radio)
        else -> return
    }
    Text(text, color = Amber, fontSize = 20.sp, modifier = modifier.background(Color(0xC0000000), RoundedCornerShape(8.dp)).padding(16.dp))
}

/**
 * The panadapter trace over the radio's waterfall, the worked slice's
 * passband shaded and its dial a white line, and the band plan as a strip
 * along the foot of the spectrum. Redrawn on each frame's version; the
 * waterfall bitmap is reused.
 *
 * Drawn in this device's [look] (colours, contrast, fill, averaging; core
 * ScopeLook). The trace is placed on the radio's own scale (the
 * panadapter's min/max dBm) when the module reports one, so the reference
 * level set here or in SmartSDR moves it as it moves the radio's; amber
 * round the edge while [adjusting].
 */
@Composable
private fun FlexScopeView(f: FlexSession, u: FlexUi, modifier: Modifier, look: ScopeLook = ScopeLook(),
                          adjusting: Boolean = false, big: Boolean = false) {
    val version by f.scopeVersion.collectAsStateWithLifecycle()
    val sc = f.scopeData
    val bitmap = remember { android.graphics.Bitmap.createBitmap(sc.width, sc.height, android.graphics.Bitmap.Config.ARGB_8888) }
    val image = remember { bitmap.asImageBitmap() }
    val pixels = remember { IntArray(sc.width * sc.height) }
    val table = remember(look) { look.table(Scope::colour) }
    val average = remember { TraceAverage() }
    val dial = u.dialHz?.toDouble()
    val slice = u.slice
    val pan = u.state?.pan
    val measurer = rememberTextMeasurer()
    val planWords = planWords()
    val density = LocalDensity.current
    // Legible across a room on a TV: the module's page uses 12 px, a TV gets a little more.
    val stripPx = with(density) { (if (big) 18.dp else 13.dp).toPx() }
    val labelStyle = TextStyle(color = Color.White, fontSize = if (big) 11.sp else 9.sp, fontWeight = FontWeight.Bold)
    Canvas(modifier.background(Color(Scope.BACKGROUND))) {
        @Suppress("UNUSED_EXPRESSION") version
        val lineH = size.height * 0.4f
        val raw: FloatArray
        val floor: Float
        synchronized(sc) {
            sc.render(pixels, table)
            bitmap.setPixels(pixels, 0, sc.width, 0, 0, sc.width, sc.height)
            raw = sc.line
            floor = sc.floorDbm
        }
        val line = average.apply(raw, look.averaging)
        drawImage(image, dstOffset = IntOffset(0, lineH.toInt()),
            dstSize = IntSize(size.width.toInt(), (size.height - lineH).toInt()), srcSize = IntSize(sc.width, sc.height))
        // The band plan along the foot of the spectrum. The trace is drawn in
        // the height above it, so the noise floor sits on the strip rather
        // than behind it: the module's page draws the strip over the floor,
        // and on the TV that hid the noise and every weak signal in it
        // (Brian, 13/10/2026). Without a plan the trace has the full height.
        val pieces = FlexPlanStrip.layout(u.tables.segments, sc.lowHz, sc.highHz, size.width, stripPx * 3f)
        val traceH = if (pieces.isNotEmpty()) lineH - stripPx else lineH
        if (line.isNotEmpty()) {
            val p = Path()
            for (i in line.indices) {
                val x = i * size.width / (line.size - 1).coerceAtLeast(1)
                val y = traceH * (1f - FlexScopeControl.traceY(line[i], pan?.minDbm, pan?.maxDbm, floor))
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            if (look.fill) {
                val fill = Path().apply { addPath(p); lineTo(size.width, traceH); lineTo(0f, traceH); close() }
                drawPath(fill, Color(0x5580DEEA))
            }
            drawPath(p, Color(0xFF80DEEA), style = Stroke(width = 2f))
        }
        if (pieces.isNotEmpty()) {
            val top = lineH - stripPx
            drawRect(Color(0xD90A0A14), Offset(0f, top), Size(size.width, stripPx))
            pieces.forEach { piece ->
                drawRect(Color(piece.colour).copy(alpha = 0.82f), Offset(piece.x, top), Size(piece.w, stripPx))
                val word = planWords[piece.kind] ?: piece.kind.uppercase()
                if (piece.label) {
                    val t = measurer.measure(word, labelStyle, maxLines = 1)
                    if (t.size.width + 6 <= piece.w) {
                        drawText(t, topLeft = Offset(piece.x + (piece.w - t.size.width) / 2, top + (stripPx - t.size.height) / 2))
                    }
                }
            }
        }
        if (dial != null && slice != null) {
            val x0 = sc.xOf(dial + slice.filterLo)
            val x1 = sc.xOf(dial + slice.filterHi)
            if (x0 != null || x1 != null) {
                val l = (x0 ?: 0f) * size.width
                val r = (x1 ?: 1f) * size.width
                drawRect(Color(0x33FFFFFF), Offset(l, 0f), Size((r - l).coerceAtLeast(2f), size.height))
            }
        }
        dial?.let { sc.xOf(it) }?.let { fx ->
            val x = fx * size.width
            drawLine(Color.White.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
        }
        if (adjusting) drawRect(Amber, Offset.Zero, size, style = Stroke(width = 4f))
    }
}

/** The plan strip's words by segment kind, from this app's catalog (the module's page has the same, plan.*). */
@Composable
private fun planWords(): Map<String, String> = mapOf(
    "cw" to stringResource(R.string.flex_plan_cw), "data" to stringResource(R.string.flex_plan_data),
    "phone" to stringResource(R.string.flex_plan_phone), "all" to stringResource(R.string.flex_plan_all),
    "beacon" to stringResource(R.string.flex_plan_beacon), "satellite" to stringResource(R.string.flex_plan_satellite),
    "repeater" to stringResource(R.string.flex_plan_repeater), "fm" to stringResource(R.string.flex_plan_fm),
    "image" to stringResource(R.string.flex_plan_image),
)

/** Which radio a scope look belongs to on this device: the controller's app and the radio's model. */
internal fun flexLookKey(f: FlexSession, u: FlexUi) = "flex|" + f.app.base + "|" + (u.state?.radioModel ?: "")

/** The reference level and span over the display while it is being adjusted. */
@Composable
private fun FlexScopeReadout(u: FlexUi, modifier: Modifier) {
    val pan = u.state?.pan ?: return
    val ref = pan.refDbm ?: pan.maxDbm
    val span = pan.spanSetHz ?: pan.spanHz.toLong()
    Text(stringResource(R.string.flex_scope_readout, Math.round(ref).toInt(), flexSpanText(span)),
        color = Amber, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.background(Color(0xC0000000), RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
}

/** "50 kHz", "1.2 MHz": a span. */
internal fun flexSpanText(hz: Long): String = when {
    hz >= 1_000_000 -> "%s MHz".format(trimSpan(hz / 1e6))
    hz >= 1_000 -> "%s kHz".format(trimSpan(hz / 1e3))
    else -> "$hz Hz"
}

private fun trimSpan(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, v)

/** The module's modes as the radio's mode screen: rows of at most six, the current one selected. */
internal fun flexModeRows(u: FlexUi): List<List<Choice>> =
    u.tables.modes.map { Choice(it.name, it.label, selected = it.name == u.slice?.mode) }.chunked(6)
