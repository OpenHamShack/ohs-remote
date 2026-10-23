package com.openhamshack.remote.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.core.tv.LocalTvInert
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.AntennaChoice
import com.openhamshack.remote.shack.Bookmark
import com.openhamshack.remote.shack.Bookmarks
import com.openhamshack.remote.shack.DecoderPanel
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.EngineUi
import com.openhamshack.remote.shack.AutoTxStatus
import com.openhamshack.remote.shack.EnableTxController
import com.openhamshack.remote.shack.EnableTxPhase
import com.openhamshack.remote.shack.SlotDecode
import com.openhamshack.remote.shack.TxChoice
import com.openhamshack.core.keys.FaceClass
import com.openhamshack.core.keys.Fired
import com.openhamshack.core.keys.KeyHints
import com.openhamshack.core.keys.RemoteAction
import com.openhamshack.core.keys.TxPhase
import com.openhamshack.core.tv.FaceKeys
import com.openhamshack.core.tv.FaceTx
import com.openhamshack.core.tv.RemoteKeysHost
import com.openhamshack.core.tv.SettingRow
import com.openhamshack.core.tv.SidePanel
import com.openhamshack.core.tv.TxPlan
import com.openhamshack.core.tv.TvTextDialog
import com.openhamshack.core.tv.isTelevision
import com.openhamshack.core.tv.remoteKeys
import com.openhamshack.core.tv.rememberRemoteKeys
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.input.KeyboardType
import com.openhamshack.remote.shack.Kind
import com.openhamshack.remote.shack.ModeAudio
import com.openhamshack.remote.shack.PanelPrefs
import com.openhamshack.remote.shack.PickerFocus
import com.openhamshack.remote.shack.PickerKey
import com.openhamshack.remote.shack.PickerRow
import com.openhamshack.remote.shack.PickerStep
import com.openhamshack.remote.shack.ScopeLabels
import com.openhamshack.remote.shack.Span
import com.openhamshack.remote.shack.Tuning

/**
 * NB or Radio on a TV, laid out like OpenWebRX+ (Brian, 09/10/2026) and
 * then made calm (10/10/2026, "too cluttered"): by default just one header
 * line (the dial, the mode, beacon lock, a slim meter, the segment as a
 * quiet subtitle), the waterfall with its bookmarks as ticks and one of
 * them named, and the running decoder's panel docked beside the waterfall
 * (FT8's list, the SSTV picture at its own shape) or under it (CW, RTTY
 * and PSK text), never over it.
 *
 * The keys are the standard map (core keys/, openhamshack REMOTE-KEYS.md
 * §4), with focus on the waterfall: Left/Right tune by the step (held, it
 * runs on); Up opens the picker over the foot of the waterfall (bookmarks,
 * then modes: [PickerFocus], closing by itself when left); Down goes to the
 * decoder panel, where OK shows it full screen; OK opens the controls row,
 * held types a frequency (a digit key does too, with that digit typed);
 * rewind/fast-forward change the step; the channel keys change band on
 * Radio and step the bookmarks on NB, which has one band; Play/Pause
 * mutes; Menu opens the settings, held the key legend. Before 12/10/2026
 * the channel keys changed the step here and Menu and Play/Pause did
 * nothing. Back closes the picker, the controls row, the settings or a
 * full-screen view, and otherwise leaves for the apps from wherever focus is.
 *
 * Enable TX (REMOTE-CONTRACT §12c) in the digital modes: Play/Pause held,
 * Transmit… in the controls row, or OK on a decode in the full list arms
 * it - a red overlay names the mode, the dial and the message, nothing is
 * sent - and OK or Play/Pause within 5 s starts it. While it is on, any key
 * stops it and does nothing else. A text mode asks for the line first. On
 * a TV the controller refuses it unless an administrator has allowed that
 * TV digital transmit; the refusal is shown as the server says it.
 *
 * Everything on this screen is unfocusable while the picker, the controls
 * row, the settings or a full-screen view is up ([LocalTvInert] and the
 * scope's own canFocus), so the D-pad cannot reach a control nobody can
 * see (GUIDE 5, "focus leaks out of overlays").
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TvEngine(e: EngineSession, onBack: () -> Unit, onRepair: () -> Unit) {
    val u by e.ui.collectAsStateWithLifecycle()
    val txState by e.enableTx.state.collectAsStateWithLifecycle()
    // Like a music app: awake while receive audio plays, so the Fire TV's
    // screensaver does not come on over a QSO someone is listening to - and
    // always while Enable TX is on: the screen going off would end it.
    if (keepAwake(u.audioRate, u.muted, keyed = txState.phase != EnableTxPhase.Idle)) KeepScreenOn()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val res = LocalResources.current
    var controls by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    // Radio's antenna inputs, a side panel at the right like the settings.
    var antennaPanel by remember { mutableStateOf(false) }
    // The frequency dialog, with any digit already typed; null when closed.
    var typing by remember { mutableStateOf<String?>(null) }
    var askLine by remember { mutableStateOf(false) }
    var logOpen by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickerFocus?>(null) }
    var lastMark by remember { mutableIntStateOf(-1) }
    // What the armed plan will send, once confirmed.
    var choice by remember { mutableStateOf<TxChoice?>(null) }
    val covered = typing != null || logOpen || full || askLine
    // DAB's station list: open while the DAB area is (Ch± or Band ◀ ▶ onto it opens it).
    val dabOpen = u.dab != null
    val inert = controls || settings || antennaPanel || dabOpen || covered || picker != null

    val tuner = remember { FocusRequester() }
    val firstControl = remember { FocusRequester() }
    val firstSetting = remember { FocusRequester() }
    val firstAntenna = remember { FocusRequester() }
    val firstDab = remember { FocusRequester() }
    val pickerFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    var panelFocused by remember { mutableStateOf(false) }

    var hints by remember { mutableStateOf(KeyHints().opened(SystemClock.uptimeMillis())) }

    val shown = u.panel.shown
    // Only a change of span (a band, wide/zoom) gives a new set of bookmarks.
    val range = scopeRange(e)
    val marks = remember(u.bookmarks, range, u.state.dialHz == null) {
        Bookmarks.inView(u.bookmarks, range?.first, range?.second, u.state.dialHz)
    }
    val current = Bookmarks.current(marks, u.state.dialHz, u.state.mode)

    // --- Enable TX: what each arm would send, in the words of the red overlay.
    val mode = u.state.mode
    val txTitle = stringResource(R.string.shack_tx_title)
    val where = stringResource(R.string.shack_tx_where, mode.uppercase(), dialText(u.dialHz))
    val cq = stringResource(R.string.shack_tx_cq_text, u.state.callsign.ifBlank { "—" }, u.state.locator.take(4)).trim()
    val notDigital = stringResource(R.string.shack_tx_not_digital, mode.uppercase())
    fun replyPlan(d: SlotDecode): TxPlan {
        val call = d.sender
        if (call.isEmpty()) return TxPlan(txTitle, emptyList(), refusal = res.getString(R.string.shack_tx_no_call))
        choice = TxChoice.Reply(d)
        return TxPlan(txTitle, listOf(where, res.getString(R.string.shack_tx_reply, call), d.text))
    }
    val tx = object : FaceTx {
        override fun plan(): TxPlan? = when (mode) {
            in AutoTxStatus.SEQUENCED -> { choice = TxChoice.Cq; TxPlan(txTitle, listOf(where, cq)) }
            in AutoTxStatus.LINES -> { controls = false; askLine = true; null }
            else -> TxPlan(txTitle, emptyList(), refusal = notDigital)
        }
        override fun start(plan: TxPlan) { choice?.let { e.enableTx.enable(it) } }
        override fun stop() = e.enableTx.stop()
    }

    // --- The map's actions, as this face does them.
    val face = object : FaceKeys {
        override val faceClass = FaceClass.RADIO
        override fun can(a: RemoteAction): Boolean = when (a) {
            RemoteAction.TUNE_DOWN, RemoteAction.TUNE_UP, RemoteAction.STEP_DOWN, RemoteAction.STEP_UP,
            RemoteAction.CONTROLS, RemoteAction.FREQUENCY, RemoteAction.SETTINGS, RemoteAction.MUTE -> true
            RemoteAction.CHOOSE -> marks.isNotEmpty() || u.modes.isNotEmpty()
            RemoteAction.FOCUS_DOWN -> shown != DecoderPanel.NONE
            RemoteAction.BAND_PREV, RemoteAction.BAND_NEXT -> e.kind == Kind.RADIO || u.bookmarks.isNotEmpty()
            // Offered in every mode: in a voice mode the plan says why not, rather than the key doing nothing.
            RemoteAction.TRANSMIT -> true
            else -> false
        }
        override fun perform(f: Fired) {
            when (f.action) {
                RemoteAction.TUNE_DOWN -> e.tune(-1)
                RemoteAction.TUNE_UP -> e.tune(1)
                RemoteAction.STEP_DOWN -> e.step(-1)
                RemoteAction.STEP_UP -> e.step(1)
                RemoteAction.CHOOSE -> {
                    picker = PickerFocus.open(marks, u.modes, u.dialHz, u.state.mode, SystemClock.uptimeMillis())
                    lastMark = picker?.takeIf { it.row == PickerRow.MARKS }?.index ?: -1
                }
                RemoteAction.FOCUS_DOWN -> runCatching { panelFocus.requestFocus() }
                RemoteAction.CONTROLS -> controls = true
                RemoteAction.FREQUENCY -> typing = f.digit?.toString().orEmpty()
                RemoteAction.SETTINGS -> settings = true
                RemoteAction.MUTE -> e.mute(!u.muted)
                RemoteAction.BAND_PREV, RemoteAction.BAND_NEXT -> {
                    val by = if (f.action == RemoteAction.BAND_NEXT) 1 else -1
                    if (e.kind == Kind.RADIO) e.band(by) else Bookmarks.step(u.bookmarks, u.dialHz, by)?.let { e.goTo(it) }
                }
                else -> {}
            }
        }
        @Composable override fun label(a: RemoteAction): String? = when {
            a == RemoteAction.BAND_PREV && e.kind != Kind.RADIO -> stringResource(R.string.shack_key_bookmark_prev)
            a == RemoteAction.BAND_NEXT && e.kind != Kind.RADIO -> stringResource(R.string.shack_key_bookmark_next)
            a == RemoteAction.CHOOSE -> stringResource(R.string.shack_key_choose)
            a == RemoteAction.FOCUS_DOWN -> stringResource(R.string.shack_key_focus_down)
            a == RemoteAction.TRANSMIT -> stringResource(R.string.shack_key_transmit)
            else -> null
        }
    }
    val keys = rememberRemoteKeys(
        face, tx, overlayOpen = inert,
        txOn = txState.phase == EnableTxPhase.Armed, txBusy = txState.phase == EnableTxPhase.Arming,
        onFired = { hints = hints.pressed(it.action, SystemClock.uptimeMillis()) },
        onBack = onBack,
    )
    // The face's own overlays close before the keys' stack and before leaving: registered after it, so innermost.
    BackHandler(enabled = picker != null || controls) {
        when {
            picker != null -> picker = null
            else -> controls = false
        }
    }
    // A refusal or an end the operator did not ask for, said as the server said it.
    val isTv = isTelevision(context)
    LaunchedEffect(txState.error) {
        val err = txState.error ?: return@LaunchedEffect
        keys.note = when (err.code) {
            EnableTxController.RELEASED -> return@LaunchedEffect
            "level_too_low" -> res.getString(if (isTv) R.string.shack_tx_level_tv else R.string.shack_tx_level, err.text)
            EnableTxController.SERVER_OFF -> e.ui.value.status?.autoTx?.why?.takeIf { it.isNotBlank() }
                ?.let { res.getString(R.string.shack_tx_off_why, it) } ?: res.getString(R.string.shack_tx_off)
            EnableTxController.LINK_LOST -> res.getString(R.string.shack_tx_link_lost)
            EnableTxController.NO_ANSWER -> res.getString(R.string.shack_tx_no_answer, err.text)
            else -> res.getString(R.string.shack_tx_refused, err.text.ifBlank { err.code })
        }
    }

    // Focus always comes back somewhere when an overlay closes: with
    // nothing focused, the next key goes nowhere on a TV.
    LaunchedEffect(controls, settings, antennaPanel, dabOpen, covered, picker != null, keys.legend) {
        delay(80)
        runCatching {
            when {
                keys.legend -> {}
                picker != null -> pickerFocus.requestFocus()
                settings -> firstSetting.requestFocus()
                antennaPanel -> firstAntenna.requestFocus()
                dabOpen -> firstDab.requestFocus()
                controls -> firstControl.requestFocus()
                !covered -> tuner.requestFocus()
            }
        }
    }
    LaunchedEffect(shown) {
        // The decoder stopped: its full-screen view and the focus in its panel go with it.
        if (shown == DecoderPanel.NONE) {
            full = false
            if (panelFocused) runCatching { tuner.requestFocus() }
        }
    }

    // The bookmarks or modes changed under an open picker: keep its highlight on something real.
    LaunchedEffect(marks.size, u.modes.size) { picker = picker?.fit(marks.size, u.modes.size) }
    // Left alone, the picker goes away by itself; each key puts that off.
    LaunchedEffect(picker?.touchedMs) {
        val p = picker ?: return@LaunchedEffect
        delay((p.touchedMs + PickerFocus.IDLE_MS - SystemClock.uptimeMillis()).coerceAtLeast(0))
        if (picker?.idle(SystemClock.uptimeMillis()) == true) picker = null
    }
    val antenna = if (e.kind == Kind.RADIO) AntennaChoice.of(u.state) else null
    // The inputs went while the panel was open (another radio, an older server): close it rather than strand focus in nothing.
    LaunchedEffect(antenna == null) { if (antenna == null) antennaPanel = false }
    val looking = picker?.takeIf { it.row == PickerRow.MARKS }?.let { marks.getOrNull(it.index) }
    val named = marks.getOrNull(ScopeLabels.named(marks, u.dialHz, looking, range?.first, range?.second))

    // The step is shown for a moment when the screen opens and whenever it changes, not all the time.
    var stepShown by remember { mutableStateOf(true) }
    LaunchedEffect(u.stepHz) { stepShown = true; delay(4_000); stepShown = false }

    // Starts a transmission from the controls row: the same plan and confirm as Play/Pause held.
    val armFromControls: () -> Unit = {
        controls = false
        tx.plan()?.let { keys.armWith(it) }
    }

    Box(Modifier.fillMaxSize().remoteKeys(keys)) {
        CompositionLocalProvider(LocalTvInert provides inert) {
            Column(Modifier.fillMaxSize().padding(horizontal = TvSafeH, vertical = TvSafeV)) {
                Header(e, u)
                // Radio's bands, the dial's lit: where the channel keys go next.
                if (e.kind == Kind.RADIO) TvBandStrip(u, Modifier.padding(top = 8.dp))
                // The quiet subtitle: where on the band the dial is, the step
                // for a moment after it changes, and the engine's last word.
                Row(Modifier.padding(top = 2.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (u.state.segment.isNotBlank()) Text(u.state.segment, fontSize = 16.sp, color = Dim, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (stepShown) Text(stringResource(R.string.shack_step, stepText(u.stepHz)), fontSize = 16.sp, color = Dim, maxLines = 1)
                    // In the quiet line rather than the header: it is worth seeing from the
                    // sofa (which socket is this?) but not something acted on every minute.
                    antenna?.let { Text(stringResource(R.string.shack_antenna_summary, antennaNow(it)), fontSize = 16.sp, color = Dim, maxLines = 1,
                        overflow = TextOverflow.Ellipsis) }
                    u.toast?.let { Text(it, color = Dim, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(
                            Modifier.weight(1f).fillMaxWidth()
                                .focusRequester(tuner)
                                .focusProperties { canFocus = !inert }
                                // The surface: with focus here every key of the map acts (RemoteKeys);
                                // elsewhere the D-pad and OK are the focused control's.
                                .onFocusChanged { keys.onSurface = it.isFocused }
                                // No focus ring: the waterfall is where focus lives,
                                // and a ring round it all evening was one more box.
                                .focusable()
                                .clip(RoundedCornerShape(6.dp)),
                        ) {
                            ScopeView(e, u.dialHz, Modifier.fillMaxSize(), marks = marks, named = named, labelSize = 16.sp,
                                passband = ModeAudio.of(u.state), plan = rememberPlanStrip(e, u), stripHeight = 22.dp, stripText = 13.sp)
                        }
                        if (shown != DecoderPanel.NONE && !shown.beside) {
                            PanelFrame(shown, panelFocus, inert, onFocus = { panelFocused = it }, onFull = {}, onUp = { runCatching { tuner.requestFocus() } },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                                DecoderPanelBody(e, u, big = true, listHeight = 0.dp)
                            }
                        }
                    }
                    if (shown.beside) {
                        PanelFrame(shown, panelFocus, inert, onFocus = { panelFocused = it }, onFull = { full = true },
                            onUp = { runCatching { tuner.requestFocus() } },
                            // The list needs the room for its messages; SSTV's 5:4
                            // picture fills a narrower panel's width at the same height.
                            modifier = Modifier.weight(if (shown == DecoderPanel.SLOTS) 0.85f else 0.75f).fillMaxHeight()) {
                            // 17 sp, not 18: at 18 the list beside the waterfall was a
                            // cell short of room for the Country column (BandColumns).
                            DecoderPanelBody(e, u, big = true, listHeight = 600.dp, modifier = Modifier.fillMaxSize(), listSize = 17.sp)
                        }
                    }
                }
                u.problem?.let { p ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(problemText(p), color = OnAirRed, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (p.http == 401) TvItem(onClick = onRepair) { Text(stringResource(R.string.shack_pair_again_signin), fontSize = 16.sp) }
                    }
                }
            }
        }

        // Key hints, floating over the foot of the waterfall rather than
        // taking a line of the screen, and fading once read (core KeyHints).
        val available = buildSet {
            add(RemoteAction.TUNE_UP); add(RemoteAction.STEP_UP); add(RemoteAction.CONTROLS); add(RemoteAction.HELP)
            if (face.can(RemoteAction.CHOOSE)) add(RemoteAction.CHOOSE)
            if (shown != DecoderPanel.NONE) add(RemoteAction.FOCUS_DOWN)
        }
        var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
        LaunchedEffect(hints.untilMs) {
            now = SystemClock.uptimeMillis()
            delay((hints.untilMs - now).coerceAtLeast(0))
            now = SystemClock.uptimeMillis()
        }
        AnimatedVisibility(
            visible = !inert && !keys.anyOverlay && keys.txPhase == TxPhase.IDLE && hints.visible(now, available),
            enter = fadeIn(), exit = fadeOut(tween(800)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TvSafeV + 12.dp),
        ) {
            Text(hints.pending(available).mapNotNull { hintText(it) }.joinToString("   ·   "), color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp,
                modifier = Modifier.background(Color(0xD0000000), RoundedCornerShape(20.dp)).padding(horizontal = 18.dp, vertical = 8.dp))
        }

        picker?.let { p ->
            TvPicker(
                p, marks, u.modes, current = current, mode = u.state.mode, focus = pickerFocus,
                // Over the foot of the waterfall, so the spectrum and the name of the
                // bookmark being looked at stay in sight above it.
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = TvSafeV, start = TvSafeH, end = TvSafeH),
                onKey = { k ->
                    // The live highlight, not the `p` this lambda was composed
                    // with: keys arriving faster than a recomposition (eleven
                    // Rights from adb, a remote's key repeat) each moved the
                    // same stale highlight, so eleven presses moved it two.
                    val live = picker ?: return@TvPicker
                    val currentMode = u.modes.indexOf(u.state.mode)
                    when (val step = live.key(k, SystemClock.uptimeMillis(), currentMode, lastMark)) {
                        is PickerStep.Move -> {
                            if (step.to.row == PickerRow.MARKS) lastMark = step.to.index
                            picker = step.to
                        }
                        is PickerStep.Choose -> {
                            picker = null
                            if (step.row == PickerRow.MARKS) marks.getOrNull(step.index)?.let { e.goTo(it) }
                            else u.modes.getOrNull(step.index)?.let { e.setMode(it) }
                        }
                        PickerStep.Close -> picker = null
                    }
                },
            )
        }

        if (controls) {
            // The controls row, over the foot of the screen like the picker
            // over its head: the screen beneath does not move when it opens.
            // Focus scrolls each item 16 dp clear of the row's ends, into the
            // end padding inside the scroll, so the last one's ring is not cut by its clip.
            val clear = with(LocalDensity.current) { 16.dp.toPx() }
            CompositionLocalProvider(LocalBringIntoViewSpec provides remember(clear) { ScrollWithMargin(clear) }) {
                Row(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = TvSafeH, vertical = TvSafeV)
                        .background(Color(0xF0101010), RoundedCornerShape(12.dp)).padding(10.dp)
                        .horizontalScroll(rememberScrollState())
                        // Inside the scroll: room past the last item for ScrollWithMargin to scroll into.
                        .padding(end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // Step first (REMOTE-KEYS.md §4: OK, OK still changes the step).
                    // OK only ever went up, held at the top, so on a TV the small
                    // steps were out of reach once left (Brian, 23/10/2026: on the
                    // TV "the step only goes up"). Left lowers it and Right raises
                    // it, held at the ends, as the settings panel's Step row
                    // (SettingRow) already did. At an end the key is not taken,
                    // so Right from the largest step moves on along the row as
                    // before; Down moves on from any step, so Mute and the rest
                    // are not only reachable by walking the step to its top.
                    // A held key repeats the step but never carries focus off it.
                    TvItem(onClick = { e.step(1) }, focus = firstControl, modifier = Modifier.onPreviewKeyEvent { k ->
                        if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val by = when (k.key) {
                            Key.DirectionLeft -> -1
                            Key.DirectionRight -> 1
                            Key.DirectionDown -> { focusManager.moveFocus(FocusDirection.Right); return@onPreviewKeyEvent true }
                            else -> return@onPreviewKeyEvent false
                        }
                        val now = e.ui.value.stepHz
                        if (Tuning.nextStep(e.kind, now, by) != now) { e.step(by); true }
                        else k.nativeKeyEvent.repeatCount > 0
                    }) { Text(stringResource(R.string.shack_step_adjust, stepText(u.stepHz)), fontSize = 16.sp) }
                    TvItem(onClick = { e.mute(!u.muted) }) { Text(stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute), fontSize = 16.sp) }
                    TvItem(onClick = { e.span(if (u.span == Span.WIDE) Span.ZOOM else Span.WIDE) }) {
                        Text(stringResource(if (u.span == Span.WIDE) R.string.shack_span_wide else R.string.shack_span_zoom), fontSize = 16.sp)
                    }
                    if (u.hasNr) {
                        val refused = stringResource(R.string.shack_nr_view_only)
                        TvItem(onClick = { if (u.viewOnly) e.note(refused) else e.nr(u.state.nr != true) }, selected = u.state.nr == true) {
                            Text(nrLabel(u.state.nr), fontSize = 16.sp, color = if (u.viewOnly) Dim else Color.Unspecified)
                        }
                    }
                    if (e.kind == Kind.RADIO) {
                        TvItem(onClick = { e.band(-1) }) { Text(stringResource(R.string.shack_band_down), fontSize = 16.sp) }
                        TvItem(onClick = { e.band(1) }) { Text(stringResource(R.string.shack_band_up), fontSize = 16.sp) }
                    }
                    antenna?.let { a ->
                        TvItem(onClick = { controls = false; antennaPanel = true }) {
                            Text(stringResource(R.string.shack_antenna_summary, antennaNow(a)), fontSize = 16.sp)
                        }
                    }
                    TvItem(onClick = { typing = "" }) { Text(stringResource(R.string.shack_frequency), fontSize = 16.sp) }
                    TvItem(onClick = { controls = false; logOpen = true }) { Text(stringResource(R.string.shack_log), fontSize = 16.sp) }
                    // Clear is here rather than on the panel, which is one focus target
                    // beside the scope (REMOTE-KEYS section 4): the controls row is where
                    // the D-pad already goes for everything that is not tuning.
                    if (shown == DecoderPanel.SLOTS && u.decodes.items.isNotEmpty()) {
                        TvItem(onClick = { e.clearDecodes(); controls = false }) { Text(stringResource(R.string.shack_decodes_clear_item), fontSize = 16.sp) }
                    }
                    if (u.panel.active != DecoderPanel.NONE) {
                        TvItem(onClick = { if (shown == DecoderPanel.NONE) e.openPanel() else e.closePanel() }) {
                            Text(stringResource(if (shown == DecoderPanel.NONE) R.string.shack_panel_show else R.string.shack_panel_hide), fontSize = 16.sp)
                        }
                    }
                    // The D-pad-only way to Enable TX, for a remote without Play/Pause (§5.2).
                    if (mode in AutoTxStatus.SEQUENCED || mode in AutoTxStatus.LINES) {
                        TvItem(onClick = armFromControls) { Text(stringResource(R.string.shack_tx_item), fontSize = 16.sp, color = OnAirRed) }
                    }
                    TvItem(onClick = { controls = false; keys.legend = true }) { Text(stringResource(R.string.shack_keys_item), fontSize = 16.sp) }
                    TvItem(onClick = { controls = false }) { Text(stringResource(R.string.shack_done), fontSize = 16.sp) }
                }
            }
        }

        if (settings) {
            // Menu: the settings, at the right, leaving the waterfall in view. Not timed out: it edits.
            SidePanel(stringResource(R.string.shack_settings_title), onClose = { settings = false }, modifier = Modifier.align(Alignment.CenterEnd)) {
                SettingRow(stringResource(R.string.shack_settings_step), stepText(u.stepHz), onPlus = { e.step(1) }, onMinus = { e.step(-1) },
                    focus = firstSetting)
                TvItem(onClick = { e.span(if (u.span == Span.WIDE) Span.ZOOM else Span.WIDE) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (u.span == Span.WIDE) R.string.shack_span_wide else R.string.shack_span_zoom), fontSize = 17.sp)
                }
                TvItem(onClick = { e.mute(!u.muted) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute), fontSize = 17.sp)
                }
                if (u.hasNr) {
                    val refused = stringResource(R.string.shack_nr_view_only)
                    TvItem(onClick = { if (u.viewOnly) e.note(refused) else e.nr(u.state.nr != true) }, selected = u.state.nr == true,
                        modifier = Modifier.fillMaxWidth()) { Text(nrLabel(u.state.nr), fontSize = 17.sp) }
                }
                if (u.panel.active != DecoderPanel.NONE) {
                    TvItem(onClick = { if (shown == DecoderPanel.NONE) e.openPanel() else e.closePanel() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (shown == DecoderPanel.NONE) R.string.shack_panel_show else R.string.shack_panel_hide), fontSize = 17.sp)
                    }
                }
                TvItem(onClick = { val on = !u.autoOpen; PanelPrefs.setAutoOpen(context, on); e.setAutoOpen(on) }, selected = u.autoOpen,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (u.autoOpen) R.string.shack_panel_auto_on else R.string.shack_panel_auto_off), fontSize = 17.sp)
                }
                TvItem(onClick = { settings = false; keys.legend = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.shack_keys_item), fontSize = 17.sp)
                }
            }
        }

        if (antennaPanel && antenna != null) {
            // The inputs, one focusable row each, as Flex's antenna page: Up and
            // Down between them, OK chooses and closes, BACK closes (SidePanel's).
            // A view-only TV is told why rather than sent to be refused again.
            val refused = stringResource(R.string.shack_antenna_view_only)
            // Opaque, as Flex's side panel is: SidePanel's own background lets the
            // subtitle row ("Simulator", "Between bands") show through its title.
            SidePanel(stringResource(R.string.shack_antenna_title), onClose = { antennaPanel = false },
                modifier = Modifier.align(Alignment.CenterEnd).background(Color(0xFF101418))) {
                Text(stringResource(R.string.shack_antenna_summary, antennaNow(antenna)), fontSize = 16.sp, color = Dim)
                val lit = antenna.rows.indexOf(antenna.chosen).coerceAtLeast(0)
                antenna.rows.forEachIndexed { i, name ->
                    TvItem(onClick = { if (u.viewOnly) e.note(refused) else { e.setAntenna(name); antennaPanel = false } },
                        selected = name == antenna.chosen, focus = if (i == lit) firstAntenna else null, modifier = Modifier.fillMaxWidth()) {
                        Text(antennaName(name, antenna), fontSize = 18.sp, color = if (u.viewOnly) Dim else Color.Unspecified)
                    }
                }
                if (u.viewOnly) Text(refused, fontSize = 14.sp, color = Amber)
                Text(stringResource(R.string.shack_antenna_rx_only), fontSize = 14.sp, color = Dim)
            }
        }

        u.dab?.let { d ->
            // Opaque for the same reason as the antenna panel. Nothing here keys:
            // a service plays through Radio's own receive audio.
            val refused = stringResource(R.string.shack_dab_view_only)
            SidePanel(stringResource(R.string.shack_area_dab), onClose = { e.closeDab() },
                modifier = Modifier.align(Alignment.CenterEnd).background(Color(0xFF101418))) {
                TvDabRows(e, d, u.viewOnly, firstDab, refused)
            }
        }

        // On the air: what the server says is going on, for the red overlay.
        val auto = u.status?.autoTx
        val onLines = buildList {
            add(stringResource(R.string.shack_tx_on_title, mode.uppercase()))
            add(where)
            auto?.dx?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.shack_tx_with, it)) }
            auto?.next?.takeIf { it.isNotBlank() }?.let { add(stringResource(R.string.shack_tx_next, it)) }
            (txState.choice as? TxChoice.Line)?.let { add(stringResource(R.string.shack_tx_line, it.text)) }
        }
        RemoteKeysHost(keys, face, onLines)
    }
    typing?.let { start ->
        FrequencyDialog(initial = start, onTune = { hz -> e.setDial(hz); typing = null; controls = false }, onDismiss = { typing = null })
    }
    if (askLine) {
        val lineTitle = stringResource(R.string.shack_tx_line_title, mode.uppercase())
        TvTextDialog(lineTitle, stringResource(R.string.shack_tx_line_help), KeyboardType.Text,
            onDismiss = { askLine = false },
            onOk = { text, _ ->
                askLine = false
                choice = TxChoice.Line(text)
                keys.armWith(TxPlan(txTitle, listOf(where, res.getString(R.string.shack_tx_line, text))))
            })
    }
    // Drawn over the screen with everything under it unfocusable: the
    // controls row is closed first so its keys cannot be reached beneath.
    if (logOpen) TvLog(e, onClose = { logOpen = false })
    if (full && shown == DecoderPanel.SLOTS) {
        // OK on a message answers it: the same arm and confirm as any Enable TX (REMOTE-CONTRACT 12c).
        val canReply = mode in AutoTxStatus.SEQUENCED
        TvDecodesPanel(e, onClose = { full = false }, onPick = if (!canReply) null else { d ->
            full = false
            keys.armWith(replyPlan(d))
        })
    }
    if (full && shown == DecoderPanel.SSTV) TvSstvPanel(e, onClose = { full = false })
}

/**
 * The one header line: the dial, the mode, and only what matters - NB's
 * beacon lock as a dot and a word, Radio's band, the simulator and the
 * warnings only when they are true, the meter. The station's name, the
 * audio rate, the SNR and the dBFS figure all went (Brian, 10/10/2026):
 * none is something to act on from a sofa.
 */
@Composable
private fun Header(e: EngineSession, u: EngineUi) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Text(dialText(u.dialHz), fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1,
            color = if (u.pendingDialHz != null) Accent else Color.White)
        // Never squeezed: given the row's spare width by weight, "HSMODEM"
        // broke onto a second line that the row then clipped.
        Text(u.state.mode.uppercase(), fontSize = 26.sp, color = Accent, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        val small = 16.sp
        if (!u.connected) Text(stringResource(if (u.state.dialHz == null) R.string.shack_connecting else R.string.shack_reconnecting),
            color = Amber, fontSize = small, maxLines = 1)
        if (u.status?.onAir == true) Text(stringResource(R.string.shack_on_air_short), color = OnAirRed, fontSize = small, maxLines = 1)
        if (u.status?.simulator == true) Text(stringResource(R.string.shack_simulator), color = Amber, fontSize = small, maxLines = 1)
        if (u.muted) Text(stringResource(R.string.shack_muted), color = Dim, fontSize = small, maxLines = 1)
        else if (u.audioRate <= 0 && u.connected) Text(stringResource(R.string.shack_no_audio), color = Dim, fontSize = small, maxLines = 1)
        if (e.kind == Kind.QO100NB) LockDot(u.state.beaconLock, small)
        if (e.kind == Kind.RADIO) Text(if (u.state.band.isBlank()) stringResource(R.string.shack_band_none) else stringResource(R.string.shack_band, u.state.band),
            color = Dim, fontSize = small, maxLines = 1)
        // 110 dp: with the longest mode (HSMODEM) and Simulator showing, 140 ran into "Locked".
        SignalMeter(u.rxDb, 110.dp, big = true)
    }
}

/** A hint's words: NB's own phrases for the keys it offers, as the footer once said them all at once. */
@Composable
private fun hintText(a: RemoteAction): String? = when (a) {
    RemoteAction.TUNE_UP -> stringResource(R.string.shack_hint_tune)
    RemoteAction.CHOOSE -> stringResource(R.string.shack_hint_picker)
    RemoteAction.FOCUS_DOWN -> stringResource(R.string.shack_hint_decoder)
    RemoteAction.STEP_UP -> stringResource(R.string.shack_hint_step)
    RemoteAction.CONTROLS -> stringResource(R.string.shack_hint_controls)
    RemoteAction.HELP -> stringResource(R.string.shack_hint_help)
    else -> null
}

/**
 * The picker overlay: the bookmarks on the scope in one row, the engine's
 * modes in the next, the highlight moved by [onKey] through [PickerFocus].
 * One focus target for the whole overlay, so there is no chip for focus
 * to leak from onto the screen beneath. Moves act on key down; OK acts on
 * key up, so the release does not land on the waterfall after it closes
 * and open the controls row. BACK is taken here too: the first BACK on a
 * Compose TV otherwise only clears focus (GUIDE 5).
 */
@Composable
private fun TvPicker(
    p: PickerFocus, marks: List<Bookmark>, modes: List<String>, current: Bookmark?, mode: String,
    focus: FocusRequester, modifier: Modifier, onKey: (PickerKey) -> Unit,
) {
    Column(
        modifier
            .focusRequester(focus)
            .onPreviewKeyEvent { k ->
                val down = k.type == KeyEventType.KeyDown
                val up = k.type == KeyEventType.KeyUp
                when (k.key) {
                    Key.DirectionLeft -> { if (down) onKey(PickerKey.LEFT); true }
                    Key.DirectionRight -> { if (down) onKey(PickerKey.RIGHT); true }
                    Key.DirectionUp -> { if (down) onKey(PickerKey.UP); true }
                    Key.DirectionDown -> { if (down) onKey(PickerKey.DOWN); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (up) onKey(PickerKey.OK); true }
                    Key.Back, Key.Escape -> { if (up) onKey(PickerKey.BACK); true }
                    else -> false
                }
            }
            .focusable()
            .fillMaxWidth()
            .background(Color(0xF0101010), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (marks.isNotEmpty()) {
            Text(stringResource(R.string.shack_picker_bookmarks), color = Dim, fontSize = 14.sp)
            PickerRowView(marks.size, if (p.row == PickerRow.MARKS) p.index else -1, active = p.row == PickerRow.MARKS,
                selected = marks.indexOf(current)) { i -> marks[i].name }
        }
        if (modes.isNotEmpty()) {
            Text(stringResource(R.string.shack_picker_modes), color = Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            PickerRowView(modes.size, if (p.row == PickerRow.MODES) p.index else -1, active = p.row == PickerRow.MODES,
                selected = modes.indexOf(mode)) { i -> modes[i].uppercase() }
        }
    }
}

/**
 * One row of the picker: [highlight] ringed in the accent, [selected] (what
 * the radio is on) in accent text, the row that does not hold the
 * highlight dimmed. Scrolls to keep the highlight in view.
 */
@Composable
private fun PickerRowView(count: Int, highlight: Int, active: Boolean, selected: Int, label: (Int) -> String) {
    val state = rememberLazyListState()
    LaunchedEffect(highlight) { if (highlight >= 0) state.animateScrollToItem((highlight - 2).coerceAtLeast(0)) }
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.alpha(if (active) 1f else 0.55f)) {
        items(count) { i ->
            val lit = i == highlight
            val shape = RoundedCornerShape(8.dp)
            Text(
                label(i), fontSize = 18.sp, maxLines = 1,
                color = if (i == selected) Accent else Color.White,
                fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clip(shape)
                    .background(if (lit) Color(0xFF243440) else Color(0xFF1C1C1C))
                    .border(2.dp, if (lit) Accent else Color.Transparent, shape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * The docked decoder panel's frame, and the focus that Down from the
 * waterfall brings here. Up goes back to tuning; OK shows a list or
 * picture full screen ([onFull]); the other keys stay here rather than
 * wandering off geometrically to whatever is nearest. No heading (the
 * header already says the mode) and no border until focused.
 */
@Composable
private fun PanelFrame(
    shown: DecoderPanel, focus: FocusRequester, inert: Boolean,
    onFocus: (Boolean) -> Unit, onFull: () -> Unit, onUp: () -> Unit, modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .focusRequester(focus)
            .focusProperties { canFocus = !inert }
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .onPreviewKeyEvent { k ->
                if (inert) return@onPreviewKeyEvent false
                when (k.key) {
                    Key.DirectionUp -> { if (k.type == KeyEventType.KeyDown) onUp(); true }
                    Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> true
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (k.type == KeyEventType.KeyUp) onFull(); true }
                    else -> false
                }
            }
            .focusable()
            .background(Color(0xFF0C0C0C), RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        content()
        if (focused && shown.beside) Text(stringResource(R.string.shack_panel_keys), fontSize = 14.sp, color = Dim,
            modifier = Modifier.align(Alignment.TopEnd).background(Color(0xD0000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
    }
}
