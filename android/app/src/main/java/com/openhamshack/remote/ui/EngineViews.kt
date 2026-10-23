package com.openhamshack.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.focusProperties
import com.openhamshack.remote.shack.BandButton
import com.openhamshack.remote.shack.EngineUi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openhamshack.core.decode.DecodedStrip
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.BeaconLock
import com.openhamshack.remote.shack.Bookmark
import com.openhamshack.remote.shack.DecoderPanel
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.SlotDecode
import com.openhamshack.remote.shack.Passband
import com.openhamshack.remote.shack.PlanStripSegment
import com.openhamshack.remote.shack.RadioPlanStrip
import com.openhamshack.remote.shack.Scope
import com.openhamshack.remote.shack.ScopeLabels

// The parts of the NB/Radio screen that TV and phone draw alike, laid out
// as OpenWebRX+ lays out a receiver: the waterfall first, the band plan's
// bookmarks along it, a signal meter by the dial, and the running
// decoder's panel docked beside or under the waterfall rather than over it.
//
// Kept calm (Brian, 10/10/2026: "too cluttered"): one accent colour
// ([Accent]) for what matters - the mode, focus, the bookmark named, a CQ -
// and amber only for what wants attention (a message to this station, the
// beacon lost, a simulator). Greys for everything else, and no boxes
// round things that are not focused.

/** The screen's one accent. The focus ring's blue, so focus and "this one" read as one colour. */
internal val Accent = TvFocus

/**
 * The bandscope over the waterfall, from the session's [Scope]: the line
 * on top (a third), the waterfall under it, a line at the dial, the mode's
 * [passband] shaded from the dial, and [marks] - the bookmarks on the
 * scope - as small ticks along the top edge, with only [named] given its
 * name ([ScopeLabels]: the picker's bookmark, else the one by the dial).
 * The first pass named every bookmark over three staggered rows, which on
 * NB's wide span was a block of text over the spectrum.
 *
 * [plan], Radio's: the band plan as a strip along the foot of the spectrum,
 * over the line's floor, where Flex draws its own (FlexScopeView): CW, DATA,
 * PHONE... in the module's colours, a word where a segment has room for
 * it. [stripHeight] and [stripText] size it per device - a tablet's 9 sp
 * word was unreadable at arm's length, so callers pass more there.
 * Redrawn on each frame's version; the waterfall bitmap is reused.
 */
@Composable
internal fun ScopeView(
    e: EngineSession, dialHz: Double?, modifier: Modifier,
    marks: List<Bookmark> = emptyList(), named: Bookmark? = null, labelSize: TextUnit = 13.sp,
    passband: Passband? = null,
    plan: List<PlanStripSegment> = emptyList(), stripHeight: Dp = 16.dp, stripText: TextUnit = 10.sp,
) {
    val version by e.scopeVersion.collectAsStateWithLifecycle()
    val sc = e.scopeData
    val bitmap = remember { android.graphics.Bitmap.createBitmap(sc.width, sc.height, android.graphics.Bitmap.Config.ARGB_8888) }
    val image = remember { bitmap.asImageBitmap() }
    val measurer = rememberTextMeasurer()
    val words = radioPlanWords()
    val stripStyle = TextStyle(color = Color.White, fontSize = stripText, fontWeight = FontWeight.Bold)
    Canvas(modifier.background(Color(Scope.BACKGROUND))) {
        @Suppress("UNUSED_EXPRESSION") version
        val lineH = size.height / 3f
        val line: FloatArray
        val floor: Float
        synchronized(sc) {
            bitmap.setPixels(sc.pixels, 0, sc.width, 0, 0, sc.width, sc.height)
            line = sc.line
            floor = sc.floorDb
        }
        drawImage(image, dstOffset = IntOffset(0, lineH.toInt()),
            dstSize = IntSize(size.width.toInt(), (size.height - lineH).toInt()), srcSize = IntSize(sc.width, sc.height))
        if (line.isNotEmpty()) {
            val p = Path()
            val top = floor + Scope.RANGE_DB
            for (i in line.indices) {
                val x = i * size.width / (line.size - 1).coerceAtLeast(1)
                val y = lineH * (1f - ((line[i] - floor + 5f) / (top - floor + 5f)).coerceIn(0f, 1f))
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            drawPath(p, Color(0xFF80DEEA), style = Stroke(width = 2f))
        }
        // The band plan, at the foot of the spectrum just above the waterfall.
        if (plan.isNotEmpty() && sc.endHz > sc.startHz) {
            val h = stripHeight.toPx()
            val pieces = RadioPlanStrip.layout(plan, sc.startHz, sc.endHz, size.width, h * 2.5f)
            if (pieces.isNotEmpty()) {
                val top = lineH - h
                drawRect(Color(0xD90A0A14), Offset(0f, top), Size(size.width, h))
                for (piece in pieces) {
                    drawRect(Color(piece.colour).copy(alpha = 0.82f), Offset(piece.x, top), Size(piece.w, h))
                    if (!piece.label) continue
                    val t = measurer.measure(words[piece.kind] ?: piece.kind.uppercase(), stripStyle, maxLines = 1)
                    if (t.size.width + 6 <= piece.w) {
                        drawText(t, topLeft = Offset(piece.x + (piece.w - t.size.width) / 2, top + (h - t.size.height) / 2))
                    }
                }
            }
        }
        // Bookmarks: a short tick each along the top edge, the named one
        // longer and in the accent, its name under the ticks on a dark pill.
        val tick = 7.dp.toPx()
        for (b in marks) {
            if (b == named) continue
            val x = (sc.xOf(b.hz) ?: continue) * size.width
            drawLine(Color.White.copy(alpha = 0.45f), Offset(x, 0f), Offset(x, tick), strokeWidth = 1.5.dp.toPx())
        }
        named?.let { b -> sc.xOf(b.hz) }?.let { fx ->
            val x = fx * size.width
            drawLine(Accent, Offset(x, 0f), Offset(x, tick * 2), strokeWidth = 2.dp.toPx())
            val label = measurer.measure(named.name, TextStyle(fontSize = labelSize, color = Color.White))
            val pad = 6.dp.toPx()
            val w = label.size.width + 2 * pad
            // Beside its tick, not centred on it: the bookmark the dial is
            // on has the dial line through that tick, and it ran through
            // the name. To the left when there is no room on the right.
            val gap = 6.dp.toPx()
            val side = if (x + gap + w <= size.width) x + gap + w / 2 else x - gap - w / 2
            val placed = ScopeLabels.place(listOf(ScopeLabels.Want(0, side, w)), size.width).first()
            val y = tick * 2 + 2.dp.toPx()
            drawRoundRect(Color(0xC0000000), Offset(placed.left, y), Size(w, label.size.height.toFloat()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
            drawText(label, topLeft = Offset(placed.left + pad, y))
        }
        // Where the decoder listens: shaded, so a signal can be seen to sit
        // inside it (an FT8 or FreeDV signal belongs 1500 Hz above the dial).
        if (dialHz != null && passband != null) {
            val x0 = sc.xOf(dialHz + passband.lo)
            val x1 = sc.xOf(dialHz + passband.hi)
            if (x0 != null || x1 != null) {
                val l = (x0 ?: 0f) * size.width
                val r = (x1 ?: 1f) * size.width
                drawRect(Color(0x26FFFFFF), Offset(l, 0f), Size((r - l).coerceAtLeast(2f), size.height))
            }
        }
        dialHz?.let { sc.xOf(it) }?.let { fx ->
            val x = fx * size.width
            // White, not red: red on this screen means on the air.
            drawLine(Color.White.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/**
 * The receive level, as a slim bar: a number in dBFS read from a sofa was
 * noise, and the engine's figure is uncalibrated anyway (ohscore/ui/smeter.py:
 * S-units need a calibration only the station has), so [number] is off
 * unless asked for. The bar spans -120 to -20 dBFS, a receiver's noise floor
 * to a strong local signal on both engines.
 *
 * Once the server calibrates (SMeter, 23/10/2026) its S reading ([s]) goes
 * beside the bar in bold, big enough on a TV to read from a sofa: that is a
 * number that means something, as the dBFS figure was not.
 */
@Composable
internal fun SignalMeter(rxDb: Double?, width: Dp, big: Boolean, number: Boolean = false, s: String? = null, sSize: TextUnit = 16.sp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s != null) Text(s, color = Color.White, fontWeight = FontWeight.Bold, fontSize = sSize, maxLines = 1,
            modifier = Modifier.testTag("sMeter"))
        val f = rxDb?.let { ((it + 120.0) / 100.0).coerceIn(0.0, 1.0).toFloat() } ?: 0f
        val shape = RoundedCornerShape(3.dp)
        Box(Modifier.width(width).height(if (big) 6.dp else 4.dp).clip(shape).background(Color(0xFF2A2A2A))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(Accent))
        }
        if (number) Text(rxDb?.let { stringResource(R.string.shack_signal, db(it)) } ?: stringResource(R.string.shack_signal_none),
            color = Dim, fontSize = if (big) 16.sp else 12.sp)
    }
}

/**
 * NB's beacon lock as a dot and a word: the accent when locked (that the
 * dial can be trusted is all most people need), amber with the reason when
 * not. The SNR figure went: it changed every second and meant little.
 */
@Composable
internal fun LockDot(lock: BeaconLock, size: TextUnit) {
    val (colour, text) = when (lock) {
        BeaconLock.LOCKED -> Accent to stringResource(R.string.shack_lock_short_locked)
        BeaconLock.HELD -> Amber to stringResource(R.string.shack_lock_short_held)
        BeaconLock.SEARCHING -> Amber to stringResource(R.string.shack_lock_short_searching)
        BeaconLock.UNKNOWN -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
        Text(text, color = if (lock == BeaconLock.LOCKED) Dim else Amber, fontSize = size, maxLines = 1)
    }
}

/**
 * The running decoder's output, as its panel shows it docked by the
 * waterfall: the FT8-style list, the SSTV picture being painted, or a
 * character decoder's line. Before the first output, what it is listening
 * for, so an empty panel does not look broken (FT8's first decodes are up
 * to 15 s away). Never focusable itself: the TV's panel frame is.
 */
@Composable
internal fun DecoderPanelBody(e: EngineSession, u: EngineUi, big: Boolean, listHeight: Dp, modifier: Modifier = Modifier,
                              onPick: ((SlotDecode) -> Unit)? = null,
                              /** Band Activity's type size: 18 sp on a TV or a tablet, read from across a room or at arm's length. */
                              listSize: androidx.compose.ui.unit.TextUnit = if (big) 18.sp else 14.sp,
                              /** Band Activity's column heads; a phone's docked list has no room for the line. */
                              heads: Boolean = true) {
    val waiting = @Composable {
        Text(stringResource(R.string.shack_panel_waiting, u.state.mode.uppercase()), color = Dim, fontSize = if (big) 18.sp else 13.sp,
            modifier = Modifier.padding(8.dp))
    }
    Column(modifier) {
        when (u.panel.shown) {
            DecoderPanel.SLOTS ->
                if (u.decodes.items.isEmpty()) waiting()
                else DecodeList(u.decodes, u.state.callsign, listSize, heads = heads, onPick = onPick,
                    modifier = Modifier.fillMaxWidth().then(if (listHeight > 0.dp) Modifier.height(listHeight) else Modifier.weight(1f)))
            DecoderPanel.TEXT -> if (u.decoded.isEmpty) waiting() else DecodedStrip(u.decoded, big = big)
            DecoderPanel.SSTV -> {
                val p = synchronized(e.sstv) { e.sstv.current }
                if (p == null) waiting() else {
                    Text(listOf(sstvFormat(p.fmt), sstvProgress(p)).joinToString(" · "), color = Dim,
                        fontSize = if (big) 16.sp else 12.sp)
                    SstvImage(e, p, u.sstvVersion, Modifier.fillMaxWidth().height(listHeight))
                }
            }
            DecoderPanel.NONE -> Unit
        }
    }
}

/** Whether a panel sits beside the waterfall (a list or a picture: tall) or under it (one line of text). */
internal val DecoderPanel.beside: Boolean get() = this == DecoderPanel.SLOTS || this == DecoderPanel.SSTV

/** The plan strip's words by segment kind (RadioPlanStrip.kindOf), from this app's catalog. */
@Composable
internal fun radioPlanWords(): Map<String, String> = mapOf(
    "cw" to stringResource(R.string.shack_plan_cw), "data" to stringResource(R.string.shack_plan_data),
    "phone" to stringResource(R.string.shack_plan_phone), "all" to stringResource(R.string.shack_plan_all),
    "beacon" to stringResource(R.string.shack_plan_beacon), "satellite" to stringResource(R.string.shack_plan_satellite),
    "repeater" to stringResource(R.string.shack_plan_repeater), "fm" to stringResource(R.string.shack_plan_fm),
    "image" to stringResource(R.string.shack_plan_image),
)

/**
 * Radio's band-plan strip for what the scope shows now: recomputed when the
 * span moves (a band, wide/zoom) or the plan in force changes, never per
 * frame. Empty for NB, and until the data is read.
 */
@Composable
internal fun rememberPlanStrip(e: EngineSession, u: EngineUi): List<PlanStripSegment> {
    val range = scopeRange(e)
    val data = u.plan
    val planId = u.state.planInUse
    return remember(data, range, planId) {
        if (data == null || range == null) emptyList() else data.strip(planId.ifEmpty { null }, range.first, range.second)
    }
}

/**
 * Radio's band buttons: one big button per band, the band the dial is in
 * lit, a press going to this device's last spot there ([EngineSession.bandTo]).
 * One row that scrolls sideways where it does not fit (a phone, a tablet
 * held upright). Disabled once the controller has refused this person a
 * receive-side change, as the other controls are.
 */
@Composable
internal fun BandButtons(e: EngineSession, u: EngineUi, height: Dp, textSize: TextUnit, modifier: Modifier = Modifier) {
    val buttons = u.bandButtons
    if (buttons.isEmpty()) return
    val lit = u.currentBand
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // The lit band in sight, with one before it: on a phone 2 m sat off the
    // right-hand end, and a row whose lit button cannot be seen says nothing.
    val at = buttons.indexOf(lit)
    androidx.compose.runtime.LaunchedEffect(at, buttons.size) { if (at >= 0) list.animateScrollToItem((at - 1).coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyRow(modifier, state = list, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(buttons.size) { i ->
            val b = buttons[i]
            val on = b == lit
            Box(
                Modifier.height(height).widthIn(min = height * 1.3f).clip(RoundedCornerShape(8.dp))
                    .background(if (on) Accent else Color(0xFF262626))
                    .clickable(enabled = !u.viewOnly) { e.bandTo(b) }
                    .padding(horizontal = 12.dp)
                    .testTag("band-${b.name}"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(bandLabel(b), fontSize = textSize, fontWeight = FontWeight.Bold, maxLines = 1,
                        color = if (on) Color.Black else if (u.viewOnly) Dim else Color.White)
                    // A listening area says it is receive only, under its name.
                    if (b.rxTag) Text(stringResource(R.string.shack_area_rx), fontSize = textSize * 0.5f, maxLines = 1,
                        color = if (on) Color.Black else Dim)
                }
            }
        }
    }
}

/** A button's word: the band's own ("20", "70cm"), or a listening area's from the catalog ("Air", "PMR"). */
@Composable
internal fun bandLabel(b: BandButton): String = when (b.area?.id) {
    null -> b.label
    "lw" -> stringResource(R.string.shack_area_lw)
    "mw" -> stringResource(R.string.shack_area_mw)
    "cb" -> stringResource(R.string.shack_area_cb)
    "cb-uk" -> stringResource(R.string.shack_area_cb_uk)
    "fm-broadcast" -> stringResource(R.string.shack_area_fm)
    "airband" -> stringResource(R.string.shack_area_air)
    "marine" -> stringResource(R.string.shack_area_marine)
    "dab" -> stringResource(R.string.shack_area_dab)
    "lpd433" -> stringResource(R.string.shack_area_lpd)
    "pmr446" -> stringResource(R.string.shack_area_pmr)
    else -> b.name
}

/**
 * The TV's band strip: the same bands as a quiet line under the header, the
 * dial's lit, for the channel keys to move along (REMOTE-KEYS §4: Ch± is
 * band ± on Radio). Not focusable: on a TV Up and Down already mean the
 * picker and the decoder panel, and a row of focus stops between them
 * would cost every operator a key press. With the listening areas and the
 * microwave bands (23/10/2026) the row no longer fits a screen, so it
 * scrolls itself to keep the lit one in sight with two before it; it is
 * never scrolled by hand or focused.
 */
@Composable
internal fun TvBandStrip(u: EngineUi, modifier: Modifier = Modifier) {
    val buttons = u.bandButtons
    if (buttons.isEmpty()) return
    val lit = u.currentBand
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val at = buttons.indexOf(lit)
    androidx.compose.runtime.LaunchedEffect(at, buttons.size) { if (at >= 0) list.animateScrollToItem((at - 2).coerceAtLeast(0)) }
    androidx.compose.foundation.lazy.LazyRow(modifier.focusProperties { canFocus = false }, state = list, userScrollEnabled = false,
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        items(buttons.size) { i ->
            val b = buttons[i]
            val on = b == lit
            Row(Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Accent else Color(0xFF1A1A1A))
                .padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(bandLabel(b), fontSize = 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                    color = if (on) Color.Black else Dim)
                if (b.rxTag) Text(stringResource(R.string.shack_area_rx), fontSize = 11.sp, maxLines = 1,
                    color = if (on) Color.Black else Dim, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
