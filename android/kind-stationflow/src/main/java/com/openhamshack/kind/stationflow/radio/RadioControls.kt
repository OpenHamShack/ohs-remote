package com.openhamshack.kind.stationflow.radio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.openhamshack.core.tv.LocalTvInert
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.Waterfall
import com.openhamshack.kind.stationflow.api.RadioState
import kotlin.math.roundToInt

/** What the face's controls tell the screen around them. */
interface FaceHost {
    fun openTune()
    fun openDecodes()
    fun openSstv()
    fun focusChanged(what: FaceFocus)
    /** One of the face's own overlays (FacePanels). */
    fun open(panel: FacePanel)
}

enum class FaceFocus { None, Dial, Key }

/**
 * The face's own overlays: the radio's MODE and FIL screens as grids, the
 * front end (P.AMP and ATT), the settings sheet behind the SET key, the
 * scope's settings, and typing a frequency.
 */
enum class FacePanel { Mode, Filter, FrontEnd, Settings, Scope, Entry }

/** A key on the face doing what it says: receive-side only (see [KeyAction]). */
fun runKey(a: KeyAction, s: StationFlowSession, host: FaceHost, page: () -> Unit) {
    val st = s.state.value
    when (a) {
        KeyAction.Page -> page()
        // The radio's MODE and FIL keys open its MODE and filter screens; so do these.
        KeyAction.Mode -> host.open(FacePanel.Mode)
        KeyAction.Filter -> host.open(FacePanel.Filter)
        KeyAction.Band -> s.cycleBand()
        KeyAction.Vfo -> s.setVfo(if (st?.vfo == "VFOB") "VFOA" else "VFOB")
        KeyAction.Tune -> host.openTune()
        KeyAction.Settings -> host.open(FacePanel.Settings)
        KeyAction.Decodes -> host.openDecodes()
        KeyAction.Sstv -> host.openSstv()
    }
}

/** A badge or label on the face doing what the radio's own touch screen does there. */
fun runSpot(spot: FaceSpot, s: StationFlowSession, host: FaceHost) {
    val st = s.state.value
    when (spot) {
        FaceSpot.Mode -> host.open(FacePanel.Mode)
        FaceSpot.Filter -> host.open(FacePanel.Filter)
        FaceSpot.Amp -> host.open(FacePanel.FrontEnd)
        FaceSpot.Vfo -> s.setVfo(if (st?.vfo == "VFOB") "VFOA" else "VFOB")
        FaceSpot.Scope -> host.open(FacePanel.Scope)
        FaceSpot.Dial -> s.cycleStep()
        is FaceSpot.Key -> Unit
    }
}

/** Whether the face offers a front-end spot: only for a radio whose model lists a preamp or an attenuator. */
fun hasFrontEnd(st: RadioState?): Boolean = st?.preamps?.isNotEmpty() == true || st?.attenuators?.isNotEmpty() == true ||
    st?.preampModes?.isNotEmpty() == true

/** A rectangle of the face, in the dp the face is drawn at. */
@Composable
private fun Modifier.at(r: Px, scalePx: Float): Modifier {
    val d = LocalDensity.current
    return this
        .offset { IntOffset((r.x * scalePx).roundToInt(), (r.y * scalePx).roundToInt()) }
        .size(with(d) { (r.w * scalePx).toDp() }, with(d) { (r.h * scalePx).toDp() })
}

/** OK, Enter or the centre of the D-pad. */
private fun isOk(k: Key) = k == Key.DirectionCenter || k == Key.Enter || k == Key.NumPadEnter

/**
 * The face's D-pad controls, laid over its picture. Invisible: the face
 * draws the ring round whatever has focus ([FaceExtras.spot]).
 *
 * - The frequency, the face's surface: its keys are the standard map
 *   (RadioFaceTv): Left/Right tune, OK quick controls, OK held types a
 *   frequency (as F-INP), Up the MODE screen, Down the scope.
 * - The badges along the top - mode, filter, P.AMP/ATT, the VFO label -
 *   each OK opening what the radio's own touch there opens.
 * - The scope: OK puts it in adjust mode (amber ring), where Up/Down set
 *   the reference level and Left/Right zoom out/in on the model's span
 *   ladder; OK then opens the scope settings and Back leaves adjust mode.
 *   With a module that cannot set the scope, OK opens the settings at once.
 * - The keys along the bottom; Up returns to the scope.
 *
 * Every direction that leaves a control is set by hand: focus search is
 * geometric, and from the dial "down" would otherwise land on whatever key
 * sits under its centre. [dialFocus] is requested by the screen on open and
 * whenever a panel closes, so a TV is never left with focus nowhere.
 */
@Composable
fun FaceTvControls(
    face: Face, scalePx: Float, s: StationFlowSession, host: FaceHost,
    page: Int, onPage: () -> Unit, dialFocus: FocusRequester,
    onSpot: (FaceSpot?) -> Unit, adjusting: Boolean, onAdjust: (Boolean) -> Unit,
    st: RadioState? = s.state.value,
    /** Focus this spot (an overlay just closed that was opened from it); null leaves focus alone. */
    restore: FaceSpot? = null,
) {
    val inert = LocalTvInert.current
    val keys = faceKeys(face, page, st)
    val rects = keyRects(face)
    val keyFocus = remember(rects.size) { List(rects.size) { FocusRequester() } }
    val mode = remember { FocusRequester() }
    val filter = remember { FocusRequester() }
    val amp = remember { FocusRequester() }
    val vfo = remember { FocusRequester() }
    val scope = remember { FocusRequester() }
    val noRipple = remember { MutableInteractionSource() }
    val frontEnd = hasFrontEnd(st)
    val firstKey = keyFocus.getOrElse(1) { keyFocus.getOrElse(0) { FocusRequester.Default } }
    // Read here: a semantics block is not composable.
    val dialWord = stringResource(R.string.face_frequency)
    val scopeWord = stringResource(R.string.face_scope)

    fun Modifier.spot(spot: FaceSpot, req: FocusRequester): Modifier = this
        .focusRequester(req)
        .onFocusChanged { if (it.isFocused) onSpot(spot) }

    // The badges, left to right, then down to the frequency.
    val badges = listOfNotNull(FaceSpot.Mode to mode, FaceSpot.Filter to filter, (FaceSpot.Amp to amp).takeIf { frontEnd }, FaceSpot.Vfo to vfo)
    badges.forEachIndexed { i, (spot, req) ->
        val r = spotRect(face, spot) ?: return@forEachIndexed
        val label = when (spot) {
            FaceSpot.Mode -> stringResource(R.string.face_spot_mode)
            FaceSpot.Filter -> stringResource(R.string.face_spot_filter)
            FaceSpot.Amp -> stringResource(R.string.face_spot_front_end)
            else -> stringResource(R.string.face_spot_vfo)
        }
        Box(
            Modifier.at(r, scalePx).spot(spot, req)
                .focusProperties {
                    canFocus = !inert
                    left = badges.getOrNull(i - 1)?.second ?: FocusRequester.Default
                    right = badges.getOrNull(i + 1)?.second ?: FocusRequester.Default
                    down = dialFocus
                }
                .clickable(interactionSource = noRipple, indication = null) { runSpot(spot, s, host) }
                .semantics { contentDescription = label },
        )
    }

    // The frequency: the face's surface. Its keys are the remote's standard
    // map (core keys/, RadioFaceTv's FaceKeys): Left/Right tune, OK quick
    // controls, OK held a typed frequency, Up the MODE screen. They used to
    // be handled here, with OK changing the step; Step is now first in the
    // quick controls, so OK, OK still changes it.
    Box(
        Modifier.at(spotRect(face, FaceSpot.Dial)!!, scalePx)
            .spot(FaceSpot.Dial, dialFocus)
            .focusProperties { canFocus = !inert; up = mode; down = scope }
            .focusable()
            .semantics { contentDescription = dialWord },
    )

    // The scope: a place to focus, and adjust mode on OK.
    val canAdjust = st?.scopeCaps != null
    Box(
        Modifier.at(spotRect(face, FaceSpot.Scope)!!, scalePx)
            .spot(FaceSpot.Scope, scope)
            .focusProperties { canFocus = !inert; up = dialFocus; down = firstKey }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent isOk(e.key)
                val first = e.nativeKeyEvent.repeatCount == 0
                if (!adjusting) {
                    if (!isOk(e.key)) return@onPreviewKeyEvent false
                    if (first) { if (canAdjust) onAdjust(true) else host.open(FacePanel.Scope) }
                    return@onPreviewKeyEvent true
                }
                when (e.key) {
                    Key.DirectionUp -> s.stepScopeRef(up = true)
                    Key.DirectionDown -> s.stepScopeRef(up = false)
                    Key.DirectionLeft -> if (first) s.zoomScope(zoomIn = false)
                    Key.DirectionRight -> if (first) s.zoomScope(zoomIn = true)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (first) { onAdjust(false); host.open(FacePanel.Scope) }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusable()
            .semantics { contentDescription = scopeWord },
    )
    // Leaving the scope ends adjust mode, so the ring is never amber anywhere else.
    LaunchedEffect(inert) { if (inert) onAdjust(false) }
    LaunchedEffect(restore, inert) {
        val to = restore ?: return@LaunchedEffect
        if (inert) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        val req = when (to) {
            FaceSpot.Dial -> dialFocus
            FaceSpot.Mode -> mode
            FaceSpot.Filter -> filter
            FaceSpot.Amp -> amp
            FaceSpot.Vfo -> vfo
            FaceSpot.Scope -> scope
            is FaceSpot.Key -> keyFocus.getOrNull(to.index) ?: dialFocus
        }
        runCatching { req.requestFocus() }
    }

    rects.forEachIndexed { i, r ->
        val k = keys.getOrNull(i) ?: return@forEachIndexed
        Box(
            Modifier.at(r, scalePx)
                .spot(FaceSpot.Key(i), keyFocus[i])
                .focusProperties { canFocus = !inert; up = scope }
                .clickable(interactionSource = noRipple, indication = null) { runKey(k.action, s, host, onPage) }
                .semantics { contentDescription = k.label },
        )
    }
}

/**
 * The same face by touch (phone, tablet): the badges, frequency, scope and
 * keys where the radio's own touch screen has them. Drag along the
 * frequency to tune a step per notch, tap it to change the step, hold it to
 * type a frequency; tap the scope or waterfall to tune there (to the step's
 * grid), hold it for the scope's settings (zoom, reference level, colours).
 * No focus rings - a finger knows where it is.
 */
@Composable
fun FaceTouchControls(
    face: Face, scalePx: Float, s: StationFlowSession, host: FaceHost,
    page: Int, onPage: () -> Unit, waterfall: Waterfall,
    st: RadioState? = s.state.value,
) {
    val keys = faceKeys(face, page, st)
    val notchPx = with(LocalDensity.current) { 22.dp.toPx() }
    var dragged by remember { mutableFloatStateOf(0f) }
    // Read here: a semantics block is not composable.
    val dialWords = stringResource(R.string.face_frequency_touch)
    val scopeWords = stringResource(R.string.face_scope_touch)
    listOfNotNull(FaceSpot.Mode, FaceSpot.Filter, FaceSpot.Amp.takeIf { hasFrontEnd(st) }, FaceSpot.Vfo).forEach { spot ->
        val r = spotRect(face, spot) ?: return@forEach
        Box(Modifier.at(r, scalePx).clickable { runSpot(spot, s, host) })
    }
    Box(
        Modifier.at(spotRect(face, FaceSpot.Dial)!!, scalePx)
            .pointerInput(s) {
                detectHorizontalDragGestures(onDragStart = { dragged = 0f }) { _, dx ->
                    dragged += dx
                    while (dragged > notchPx) { s.tuneBy(+1); dragged -= notchPx }
                    while (dragged < -notchPx) { s.tuneBy(-1); dragged += notchPx }
                }
            }
            .pointerInput(s) { detectTapGestures(onLongPress = { host.open(FacePanel.Entry) }) { s.cycleStep() } }
            .semantics { contentDescription = dialWords },
    )
    Box(
        Modifier.at(spotRect(face, FaceSpot.Scope)!!, scalePx).pointerInput(s) {
            detectTapGestures(onLongPress = { host.open(FacePanel.Scope) }) { pos ->
                val sp = waterfall.latest ?: return@detectTapGestures
                val frac = (pos.x / size.width).coerceIn(0f, 1f)
                val hz = sp.startHz + (sp.spanHz * frac).toLong()
                val step = s.effectiveStep()
                s.tuneTo(Math.round(hz.toDouble() / step) * step)
            }
        }.semantics { contentDescription = scopeWords },
    )
    keyRects(face).forEachIndexed { i, r ->
        val k = keys.getOrNull(i) ?: return@forEachIndexed
        Box(
            Modifier.at(r, scalePx)
                .clickable { runKey(k.action, s, host, onPage) }
                .semantics { contentDescription = k.label },
        )
    }
}

/** The UTC clock for the status row, ticking once a second while composed. */
@Composable
fun rememberUtc(): String {
    var now by remember { mutableStateOf(clock()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); now = clock() } }
    return now
}

private fun clock(): String {
    val t = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
    return "%02d:%02d".format(t.hour, t.minute)
}
