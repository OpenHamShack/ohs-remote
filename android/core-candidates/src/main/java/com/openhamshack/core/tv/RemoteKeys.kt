package com.openhamshack.core.tv

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openhamshack.core.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.delay
import com.openhamshack.core.keys.FaceClass
import com.openhamshack.core.keys.Fired
import com.openhamshack.core.keys.KeyDispatcher
import com.openhamshack.core.keys.KeyOutcome
import com.openhamshack.core.keys.KeyScope
import com.openhamshack.core.keys.OverlayStack
import com.openhamshack.core.keys.RemoteAction
import com.openhamshack.core.keys.RemoteKey
import com.openhamshack.core.keys.TxKey
import com.openhamshack.core.keys.TxPhase

/*
 * The Compose side of the standard key map (REMOTE-KEYS.md §6.1): a face
 * says what it can do ([FaceKeys]) and, if it transmits, how ([FaceTx]);
 * this feeds the remote's keys through the shared KeyDispatcher and carries
 * out what comes back. The rules themselves live in core keys/, where they
 * are tested without a screen.
 *
 * Two modifiers on the face's root, in this order of precedence:
 * - onPreviewKeyEvent, transmit only: while armed or on air it must win over
 *   whatever control has focus, Back included, so "any key stops" is true
 *   wherever focus is;
 * - onKeyEvent for the rest, after the focused control has had its turn: a
 *   focused button keeps its OK, a row its arrows.
 */

/** A face's half of the map: which actions it has, and doing them. */
interface FaceKeys {
    val faceClass: FaceClass
    fun can(a: RemoteAction): Boolean
    fun perform(f: Fired)
    /** The face's own words for an action in the legend (NB's channel keys step bookmarks); null: the shared words. */
    @Composable fun label(a: RemoteAction): String? = null
}

/**
 * What arming would send, in the words of the red overlay: a title
 * ("Enable TX") and lines naming the frequency, the mode and the message,
 * so nothing goes out that was not on the screen first (§5.2). [refusal]
 * set: it cannot, and why, said instead of arming.
 */
data class TxPlan(val title: String, val lines: List<String>, val refusal: String? = null)

/** A face that can transmit from the remote. */
interface FaceTx {
    /**
     * Play/Pause was held (or Transmit… chosen): what would go. Null when the
     * face must ask something first (a line of text to type) and will call
     * [RemoteKeysState.armWith] itself.
     */
    fun plan(): TxPlan?
    /** Confirmed: send it. */
    fun start(plan: TxPlan)
    /** Any key while starting or on: stop, whatever it was. */
    fun stop()
}

/** A face's keys, its overlays and its transmit arm, for one screen. */
class RemoteKeysState internal constructor(faceClass: FaceClass) {
    val dispatcher = KeyDispatcher(faceClass)

    /** The face, its transmit and what it has open of its own: refreshed on every recomposition. */
    internal var face: FaceKeys? = null
    internal var tx: FaceTx? = null
    internal var faceOverlay: Boolean = false

    /** Overlays opened through [open]: Back closes the top one first. */
    var overlays by mutableStateOf(OverlayStack())
        private set
    var legend by mutableStateOf(false)
    /** Where focus is: the face's surface (dial, waterfall, picture), or one of its other controls. */
    var onSurface by mutableStateOf(true)

    var txPhase by mutableStateOf(TxPhase.IDLE)
        private set
    var plan by mutableStateOf<TxPlan?>(null)
        private set
    var armUntilMs by mutableLongStateOf(0L)
        private set
    /** A line for the face to show for a few seconds (a refusal said before anything was sent). */
    var note by mutableStateOf<String?>(null)

    /** Every action fired, for the face's hints. */
    internal var onFired: (Fired) -> Unit = {}

    val anyOverlay: Boolean get() = overlays.any || legend || faceOverlay

    fun open(id: String, idleClose: Boolean = false) { overlays = overlays.push(id, idleClose, SystemClock.uptimeMillis()) }
    fun close(id: String) { overlays = overlays.close(id) }
    fun isOpen(id: String): Boolean = overlays.isOpen(id)

    /**
     * Arm with a plan the face built itself (OK on a decode to answer it,
     * Transmit… in quick controls, a line just typed): the same red overlay
     * and the same confirm as Play/Pause held.
     */
    fun armWith(p: TxPlan) {
        if (txPhase != TxPhase.IDLE) return
        if (p.refusal != null) { note = p.refusal; return }
        val now = SystemClock.uptimeMillis()
        dispatcher.tx.arm(now)
        plan = p
        sync()
    }

    /** The Start button, by touch. */
    fun confirmTouch() {
        val p = plan ?: return
        if (dispatcher.tx.confirm(SystemClock.uptimeMillis())) {
            sync()
            tx?.start(p)
        }
    }

    /** Cancel by touch while armed; Stop by touch while on. */
    fun cancelTouch() {
        when (txPhase) {
            TxPhase.ARMED -> { dispatcher.tx.ended(); plan = null }
            TxPhase.STARTING, TxPhase.ON -> { dispatcher.tx.ended(); tx?.stop() }
            TxPhase.IDLE -> {}
        }
        sync()
    }

    /** What the face's transmit says: on for this device, or a request still out. */
    internal fun serverSays(on: Boolean, busy: Boolean) {
        if (txPhase == TxPhase.STARTING && !on && !busy) {
            // Refused or failed: the face shows why (its own error line).
            dispatcher.tx.ended()
        } else {
            dispatcher.tx.sync(on)
        }
        sync()
    }

    private fun scope(): KeyScope = when {
        anyOverlay -> KeyScope.OVERLAY
        onSurface -> KeyScope.SURFACE
        else -> KeyScope.CONTROL
    }

    /** Whether [a] does anything on this face now: the legend's dimming, a face's own hints. */
    fun canDo(a: RemoteAction): Boolean = can(a)

    private fun can(a: RemoteAction): Boolean = when (a) {
        RemoteAction.HELP -> true
        RemoteAction.TRANSMIT -> tx != null && face?.can(a) == true
        else -> face?.can(a) == true
    }

    internal fun preview(e: KeyEvent): Boolean {
        // Transmit's guard: only while armed, starting or on - and for the
        // rest of a key that just stopped or disarmed it, which must not then
        // reach the control under focus.
        val key = RemoteKey.fromKeyCode(e.nativeKeyEvent.keyCode) ?: return false
        if (dispatcher.tx.phase == TxPhase.IDLE && !dispatcher.swallowing(key)) return false
        return handle(e)
    }

    internal fun key(e: KeyEvent): Boolean = handle(e)

    private fun handle(e: KeyEvent): Boolean {
        val n = e.nativeKeyEvent
        val key = RemoteKey.fromKeyCode(n.keyCode) ?: return false
        val now = n.eventTime
        val out = when (e.type) {
            KeyEventType.KeyDown -> dispatcher.onKeyDown(key, n.repeatCount, now, scope(), ::can)
            KeyEventType.KeyUp -> dispatcher.onKeyUp(key, now, ::can)
            else -> return false
        }
        apply(out)
        if (out.consumed && overlays.any) overlays = overlays.touched(SystemClock.uptimeMillis())
        return out.consumed
    }

    internal fun tick() {
        val now = SystemClock.uptimeMillis()
        val due = dispatcher.nextDueMs()
        if (due != null && now >= due) apply(dispatcher.tick(now, ::can))
        val expired = overlays.expire(now)
        if (expired != overlays) overlays = expired
        sync()
    }

    private fun apply(out: KeyOutcome) {
        if (out.txUnavailable) note = UNAVAILABLE
        when (out.tx) {
            TxKey.ARM -> {
                val p = tx?.plan()
                when {
                    p == null -> dispatcher.tx.ended()            // the face asks first, then arms itself
                    p.refusal != null -> { dispatcher.tx.ended(); note = p.refusal }
                    else -> plan = p
                }
            }
            TxKey.CONFIRM -> plan?.let { tx?.start(it) }
            TxKey.DISARM -> plan = null
            TxKey.STOP -> tx?.stop()
            TxKey.PASS, TxKey.TAKEN -> {}
        }
        for (f in out.fired) {
            when (f.action) {
                RemoteAction.HELP -> legend = !legend
                else -> face?.perform(f)
            }
            onFired(f)
        }
        sync()
    }

    private fun sync() {
        txPhase = dispatcher.tx.phase
        armUntilMs = dispatcher.tx.untilMs
        if (txPhase == TxPhase.IDLE) plan = null
    }

    companion object {
        /** Stands for the catalog's sentence; the host draws [note] through [noteText]. */
        const val UNAVAILABLE = "\u0000tx_unavailable"
    }
}

/**
 * The keys of one face. [overlayOpen]: something of the face's own is up
 * (its controls row, a panel), so only mute and transmit reach past it.
 * [txOn]/[txBusy]: the face's transmit as its server sees it. [onBack]:
 * Back with nothing open - leave (null: the screen around it handles that).
 */
@Composable
fun rememberRemoteKeys(
    face: FaceKeys,
    tx: FaceTx? = null,
    overlayOpen: Boolean = false,
    txOn: Boolean = false,
    txBusy: Boolean = false,
    onFired: (Fired) -> Unit = {},
    onBack: (() -> Unit)? = null,
): RemoteKeysState {
    val s = remember(face.faceClass) { RemoteKeysState(face.faceClass) }
    s.face = face
    s.tx = tx
    s.faceOverlay = overlayOpen
    s.onFired = onFired
    val back by rememberUpdatedState(onBack)
    // Registered before the face's own overlays compose theirs, so theirs,
    // being innermost, take Back first; then the stack's top; then leave.
    // Null [onBack]: the screen around the face leaves by its own Back (a phone's).
    BackHandler(enabled = s.overlays.any || onBack != null) {
        val top = s.overlays.top
        if (top != null) s.close(top) else back?.invoke()
    }
    LaunchedEffect(txOn, txBusy) { s.serverSays(txOn, txBusy) }
    // The clock: holds without repeats, the arm's five seconds, idle overlays.
    LaunchedEffect(s) {
        while (true) {
            delay(TICK_MS)
            s.tick()
        }
    }
    LaunchedEffect(s.note) { if (s.note != null) { delay(NOTE_MS); s.note = null } }
    return s
}

/** On the face's root: transmit's guard first, then the map after the focused control. */
fun Modifier.remoteKeys(s: RemoteKeysState): Modifier = this
    .onPreviewKeyEvent { s.preview(it) }
    .onKeyEvent { s.key(it) }

/**
 * What the keys draw over a face: the transmit overlay and the legend.
 * Put it last in the face's root Box, so it is on top of everything.
 */
@Composable
fun BoxScope.RemoteKeysHost(s: RemoteKeysState, face: FaceKeys, onLines: List<String> = emptyList()) {
    if (s.txPhase != TxPhase.IDLE) TxOverlay(s, onLines)
    if (s.legend) KeyLegend(face, s::canDo, onClose = { s.legend = false })
    s.note?.let { n ->
        Text(
            if (n == RemoteKeysState.UNAVAILABLE) stringResource(R.string.core_tx_unavailable) else n,
            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp).widthIn(max = 900.dp)
                .background(Color(0xE0101010), RoundedCornerShape(14.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
        )
    }
}

private const val TICK_MS = 50L
private const val NOTE_MS = 5_000L
