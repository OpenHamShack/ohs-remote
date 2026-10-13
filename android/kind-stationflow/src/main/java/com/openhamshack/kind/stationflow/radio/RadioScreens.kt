package com.openhamshack.kind.stationflow.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.api.ExtPreampNote
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.formatStep
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import com.openhamshack.core.tv.LocalTvInert
import com.openhamshack.core.tv.TvItem
import com.openhamshack.core.tv.FaceKeys
import com.openhamshack.core.tv.RemoteKeysHost
import com.openhamshack.core.tv.remoteKeys
import com.openhamshack.core.tv.rememberRemoteKeys
import com.openhamshack.core.keys.FaceClass
import com.openhamshack.core.keys.Fired
import com.openhamshack.core.keys.RemoteAction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.openhamshack.core.scope.rememberScopeLook

/**
 * The TV's main screen for a radio that has a face: the radio's own screen,
 * filling the TV bar a small margin (fitFace), with a help line for what has
 * focus laid over the waterfall's foot for a few seconds. The frequency has
 * focus first - left/right tunes, OK changes the step - and the badges, the
 * scope and the radio's key bar are each a press away (FaceTvControls). The
 * face's own overlays (MODE, FIL, P.AMP/ATT, SET, the scope's settings) are
 * opened and held here, the face inert beneath them.
 */
@Composable
fun RadioFaceTv(
    s: StationFlowSession, face: Face, st: RadioState?, pending: Long?, step: Long, muted: Boolean,
    linkProblem: String?, dial: FocusRequester, host: FaceHost, viewOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    var page by remember(face.key) { mutableIntStateOf(0) }
    var spot by remember { mutableStateOf<FaceSpot?>(null) }
    var adjusting by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<FacePanel?>(null) }
    val (look, setLook) = rememberScopeLook(lookKey(s, face))
    val utc = rememberUtc()
    // Where focus was when one of the face's own overlays opened: it goes back there when the overlay
    // closes (on the emulator it went to the frequency, so a second look at SET meant finding SET again).
    var returnTo by remember { mutableStateOf<FaceSpot?>(null) }
    // Once used: a later panel of the screen's own (Tune, Decodes) closing puts focus on the frequency, as before.
    LaunchedEffect(panel) { if (panel == null) { kotlinx.coroutines.delay(400); returnTo = null } }
    // The face replaces the plain layout when the first state names the model, after the screen's own
    // "focus the dial" has already run against the plain layout's dial; ask again for this one's.
    LaunchedEffect(face.key) {
        kotlinx.coroutines.delay(80)
        runCatching { dial.requestFocus() }
    }
    val inner = remember(host) { panelHost(host) { if (panel == null) returnTo = spot; panel = it } }
    // The quick controls (OK on the frequency), over the foot of the face.
    var quick by remember { mutableStateOf(false) }
    val firstQuick = remember { FocusRequester() }
    val outerInert = LocalTvInert.current
    val focusManager = LocalFocusManager.current
    // The remote's standard map (REMOTE-KEYS.md §4, core keys/). Until
    // 12/10/2026 OK on the frequency changed the step, Up reached the
    // badges, rewind/FF tuned and Menu opened the Tune panel; now the
    // same keys do the same jobs as on every other face. The badges are in
    // the quick controls and stay under a finger.
    val remoteFace = object : FaceKeys {
        override val faceClass = FaceClass.RADIO
        override fun can(a: RemoteAction): Boolean = when (a) {
            RemoteAction.TUNE_DOWN, RemoteAction.TUNE_UP, RemoteAction.STEP_DOWN, RemoteAction.STEP_UP,
            RemoteAction.BAND_PREV, RemoteAction.BAND_NEXT, RemoteAction.CHOOSE, RemoteAction.FOCUS_DOWN,
            RemoteAction.CONTROLS, RemoteAction.FREQUENCY, RemoteAction.SETTINGS, RemoteAction.MUTE -> true
            // Receive only (REMOTE-CONTRACT 13): Play/Pause held says so.
            else -> false
        }
        override fun perform(f: Fired) {
            when (f.action) {
                RemoteAction.TUNE_DOWN -> s.tuneBy(-1)
                RemoteAction.TUNE_UP -> s.tuneBy(+1)
                RemoteAction.STEP_DOWN -> s.stepBy(-1)
                RemoteAction.STEP_UP -> s.stepBy(+1)
                RemoteAction.BAND_PREV -> s.cycleBand(-1)
                RemoteAction.BAND_NEXT -> s.cycleBand(+1)
                RemoteAction.CHOOSE -> inner.open(FacePanel.Mode)
                RemoteAction.FOCUS_DOWN -> focusManager.moveFocus(FocusDirection.Down)
                RemoteAction.CONTROLS -> quick = true
                RemoteAction.FREQUENCY -> inner.open(FacePanel.Entry)
                RemoteAction.SETTINGS -> inner.open(FacePanel.Settings)
                RemoteAction.MUTE -> s.toggleMute()
                else -> {}
            }
        }
        @Composable override fun label(a: RemoteAction): String? = when (a) {
            RemoteAction.CHOOSE -> stringResource(R.string.face_key_choose)
            RemoteAction.FOCUS_DOWN -> stringResource(R.string.face_key_focus_down)
            else -> null
        }
    }
    val keys = rememberRemoteKeys(remoteFace, overlayOpen = outerInert || panel != null || quick)
    keys.onSurface = spot == FaceSpot.Dial
    // Back in adjust mode leaves adjust mode, then the quick controls close; registered after the keys', so first.
    BackHandler(enabled = (adjusting && panel == null) || quick) { if (quick) quick = false else adjusting = false }
    LaunchedEffect(quick, keys.legend) {
        kotlinx.coroutines.delay(80)
        runCatching { if (quick) firstQuick.requestFocus() else if (!keys.legend && panel == null) dial.requestFocus() }
    }
    Box(modifier.remoteKeys(keys)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                // The whole room, the face reshaped to it rather than letterboxed at the radio's proportions.
                val shown = remember(face, maxWidth, maxHeight) { fitFace(face, maxWidth.value / maxHeight.value) }
                val help = faceHelp(shown, page, st, step, spot, adjusting)
                val vo = stringResource(R.string.hint_view_only)
                CompositionLocalProvider(LocalTvInert provides (LocalTvInert.current || panel != null || quick)) {
                    RadioFaceView(
                        shown, st,
                        FaceExtras(pendingHz = pending, step = step, muted = muted, keyPage = page, linkProblem = linkProblem,
                            spot = spot.takeIf { panel == null }, adjusting = adjusting, utc = utc, look = look),
                        s.waterfall,
                    ) { scale ->
                        FaceTvControls(shown, scale, s, inner, page, onPage = { page = (page + 1) % pageCount(shown, st) }, dial,
                            onSpot = { spot = it; if (it != FaceSpot.Scope) adjusting = false },
                            adjusting = adjusting, onAdjust = { adjusting = it }, st = st,
                            restore = returnTo.takeIf { panel == null })
                        if (panel == null && !quick) {
                            FaceHint(listOfNotNull(vo.takeIf { viewOnly }, help.ifEmpty { null }).joinToString("  ·  "), shown, scale,
                                sticky = adjusting)
                        }
                    }
                }
            }
        }
        FacePanels(panel, s, st, muted, step, look, setLook, onOpen = { panel = it }, onClose = { panel = null })
        if (quick) QuickControls(s, st, step, muted, firstQuick, Modifier.align(Alignment.BottomCenter),
            onPanel = { quick = false; inner.open(it) },
            onKeys = { quick = false; keys.legend = true },
            onTune = { quick = false; host.openTune() },
            onDone = { quick = false })
        RemoteKeysHost(keys, remoteFace)
    }
}

/**
 * The quick controls (OK on the frequency), the standard map's OK row: Step
 * first, so OK, OK still changes the step as it used to; mute; the badges
 * the D-pad no longer reaches from the frequency (filter, P.AMP/ATT, VFO);
 * a typed frequency; the face's settings, the Tune panel; the key legend.
 */
@Composable
private fun QuickControls(
    s: StationFlowSession, st: RadioState?, step: Long, muted: Boolean, first: FocusRequester, modifier: Modifier,
    onPanel: (FacePanel) -> Unit, onKeys: () -> Unit, onTune: () -> Unit, onDone: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
            .background(Color(0xF0101010), RoundedCornerShape(12.dp)).padding(10.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TvItem(onClick = { s.cycleStep() }, focus = first) { Text(stringResource(R.string.face_quick_step, formatStep(step)), fontSize = 16.sp) }
        TvItem(onClick = { s.toggleMute() }) { Text(stringResource(if (muted) R.string.face_quick_unmute else R.string.face_quick_mute), fontSize = 16.sp) }
        TvItem(onClick = { onPanel(FacePanel.Filter) }) { Text(stringResource(R.string.face_spot_filter), fontSize = 16.sp) }
        if (hasFrontEnd(st)) TvItem(onClick = { onPanel(FacePanel.FrontEnd) }) { Text(stringResource(R.string.face_spot_front_end), fontSize = 16.sp) }
        TvItem(onClick = { s.setVfo(if (st?.vfo == "VFOB") "VFOA" else "VFOB") }) { Text(stringResource(R.string.face_spot_vfo), fontSize = 16.sp) }
        TvItem(onClick = { onPanel(FacePanel.Entry) }) { Text(stringResource(R.string.face_quick_frequency), fontSize = 16.sp) }
        TvItem(onClick = { onPanel(FacePanel.Settings) }) { Text(stringResource(R.string.face_quick_settings), fontSize = 16.sp) }
        TvItem(onClick = onTune) { Text(stringResource(R.string.chip_tune), fontSize = 16.sp) }
        TvItem(onClick = onKeys) { Text(stringResource(R.string.face_quick_keys), fontSize = 16.sp) }
        TvItem(onClick = onDone) { Text(stringResource(R.string.face_quick_done), fontSize = 16.sp) }
    }
}

/** The screen's host, with the face's own overlays kept here rather than passed out. */
private fun panelHost(outer: FaceHost, open: (FacePanel) -> Unit) = object : FaceHost by outer {
    override fun open(panel: FacePanel) = open(panel)
}

/** Which radio a scope look belongs to on this device: the box and the radio's model. */
private fun lookKey(s: StationFlowSession, face: Face) = "icom|" + s.client.base.display + "|" + face.key

/** What the focused control does, in a line; empty with nothing focused. */
@Composable
private fun faceHelp(face: Face, page: Int, st: RadioState?, step: Long, spot: FaceSpot?, adjusting: Boolean): String = when (spot) {
    null -> ""
    FaceSpot.Dial -> stringResource(R.string.face_hint_dial, formatStep(step))
    FaceSpot.Mode -> stringResource(R.string.face_hint_mode)
    FaceSpot.Filter -> stringResource(R.string.face_hint_filter)
    FaceSpot.Vfo -> stringResource(R.string.face_hint_vfo)
    // Say why EXT is not among the settings, rather than leave it missing unexplained.
    FaceSpot.Amp -> when (st?.extPreampNote) {
        ExtPreampNote.MenuOff -> stringResource(R.string.face_hint_preamp_note,
            st.preampName ?: "—", stringResource(R.string.preamp_ext_menu_off))
        ExtPreampNote.MenuUnread -> stringResource(R.string.face_hint_preamp_note,
            st.preampName ?: "—", stringResource(R.string.preamp_ext_menu_unread))
        else -> stringResource(R.string.face_hint_preamp, st?.preampName ?: "—")
    }
    FaceSpot.Scope -> when {
        adjusting -> stringResource(R.string.face_hint_scope_adjusting)
        st?.scopeCaps != null -> stringResource(R.string.face_hint_scope)
        else -> stringResource(R.string.face_hint_scope_look)
    }
    is FaceSpot.Key -> faceKeys(face, page, st).getOrNull(spot.index)?.let { k ->
        when (k.action) {
            KeyAction.Page -> stringResource(R.string.face_hint_page)
            // The key's own label is the radio's (MODE, FIL): data, lower-cased as before.
            else -> stringResource(R.string.face_hint_key, k.label.lowercase(Locale.ROOT))
        }
    } ?: ""
}

/**
 * The help line, laid over the foot of the waterfall rather than under the
 * face: a line of its own cost the face its height on every screen, for
 * words that matter for a moment. It shows when focus moves and fades after
 * a few seconds, as Flex's and NB's hints do.
 */
@Composable
private fun FaceHint(text: String, face: Face, scale: Float, sticky: Boolean = false) {
    if (text.isEmpty()) return
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(text) { visible = true; kotlinx.coroutines.delay(HINT_MS); visible = false }
    // In adjust mode the keys mean something else, so what they mean stays on screen until it ends.
    if (!visible && !sticky) return
    val d = LocalDensity.current
    Box(Modifier.fillMaxWidth().height(with(d) { (face.waterfall.bottom * scale).toDp() }), contentAlignment = Alignment.BottomCenter) {
        Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp)
                .background(Color(0xC0000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

private const val HINT_MS = 5_000L

/**
 * Phones and tablets: the same face, by touch, reshaped to fill the screen
 * (fitFace): wider or taller than the radio, the scope takes the room;
 * upright, it is [compactOf] the face - the radio's top half kept, the scope
 * taller, every key in two rows. Spec: RADIO-DISPLAYS.md 5.4. The face's
 * overlays are the TV's, which work by touch.
 */
@Composable
fun RadioFaceTouch(
    s: StationFlowSession, face: Face, st: RadioState?, pending: Long?, step: Long, muted: Boolean,
    linkProblem: String?, host: FaceHost, modifier: Modifier = Modifier,
) {
    var page by remember(face.key) { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf<FacePanel?>(null) }
    val (look, setLook) = rememberScopeLook(lookKey(s, face))
    val utc = rememberUtc()
    val inner = remember(host) { panelHost(host) { panel = it } }
    Box(modifier.background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val shown = remember(face, maxWidth, maxHeight) {
                    fitFace(face, maxWidth.value / maxHeight.value, allowCompact = true)
                }
                RadioFaceView(
                    shown, st,
                    FaceExtras(pendingHz = pending, step = step, muted = muted, keyPage = page, linkProblem = linkProblem,
                        utc = utc, look = look),
                    s.waterfall,
                ) { scale ->
                    FaceTouchControls(shown, scale, s, inner, page, onPage = { page = (page + 1) % pageCount(shown, st) }, s.waterfall,
                        st = st)
                }
            }
        }
        FacePanels(panel, s, st, muted, step, look, setLook, onOpen = { panel = it }, onClose = { panel = null })
    }
}

