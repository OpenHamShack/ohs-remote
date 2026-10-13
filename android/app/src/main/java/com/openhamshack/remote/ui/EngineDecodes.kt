package com.openhamshack.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.withContext
import com.openhamshack.core.decode.Cty
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.BandActivity
import com.openhamshack.remote.shack.BandColumns
import com.openhamshack.remote.shack.BandItem
import com.openhamshack.remote.shack.BandSections
import com.openhamshack.remote.shack.BandRow
import com.openhamshack.remote.shack.Emphasis
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.SectionHead
import com.openhamshack.remote.shack.SlotDecode
import com.openhamshack.remote.shack.SlotDecodes

// What the NB and Radio engines decode, on phone, tablet and TV: a
// character decoder's rolling line (CW, RTTY, PSK) and a slotted
// decoder's messages (FT8, FT4, Q65, JS8) as Band Activity - the desktop
// NB's list, with its columns and its colours (shack/BandActivity.kt has
// the rules). Nothing here transmits: a tap (phone, tablet) or OK on the
// full list (TV) hands a decode to Enable TX's arm, which names it and
// waits for a confirm (REMOTE-CONTRACT 12c).

/**
 * A slot's start as a UTC clock time, as WSJT-X shows it: 24-hour with
 * seconds, in the locale's own pattern for that ("Hms" skeleton) - a
 * 12-hour "10:01:00 PM" in UTC reads oddly and did not fit the column.
 */
@Composable
internal fun slotTime(slot: Double): String {
    val tag = localeTag()
    return remember(slot, tag) {
        val locale = java.util.Locale.forLanguageTag(tag)
        java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "Hms"), locale)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date((slot * 1000).toLong()))
    }
}

// The character decoder's line (CW, RTTY, PSK) is the shared kit's
// DecodedStrip (core decode/DecodedStrip.kt), which the Icom kind's CW strip
// uses too.

/**
 * The bundled cty.dat, for the Country column when the server sent none
 * (an NB/Radio box older than its "country" field, Flex, Icom). Parsed
 * once per process off the main thread; null until then, and the column
 * fills in when it arrives.
 */
@Composable
internal fun rememberCty(): Cty? {
    val context = LocalContext.current
    val cty by produceState<Cty?>(null) { value = withContext(Dispatchers.Default) { Cty.load(context) } }
    return cty
}

/**
 * The list's type: one size for every column, monospace so the columns
 * line up as WSJT-X's do. [cell] is one character's advance, which the
 * column widths and [BandColumns.forWidth] are counted in.
 */
internal class BandType(val size: TextUnit, val cell: Dp)

@Composable
internal fun bandType(size: TextUnit): BandType {
    // A monospace advance is about 0.6 em; 0.62 leaves the digits a hair of room.
    val cell = with(LocalDensity.current) { (size * 0.62f).toDp() }
    return BandType(size, cell)
}

private val TxText = Color(0xFFFF8A80)

/** Text and background for a row's emphasis: the desktop's tints (ohscore ui/panels/ft8.py), on black. */
private fun Emphasis.colours(): Pair<Color, Color> = when (this) {
    Emphasis.TX -> TxText to OnAirRed.copy(alpha = 0.20f)
    Emphasis.MINE -> Amber to Amber.copy(alpha = 0.22f)
    Emphasis.CQ -> Accent to Accent.copy(alpha = 0.12f)
    Emphasis.NONE -> Color.White to Color.Transparent
}

/** The fixed columns, in cells: "22:01:30", "-24", "-0.3", "1234". */
private const val TIME_CELLS = 8.5f
private const val DB_CELLS = 3.5f
private const val DT_CELLS = 4.5f
private const val FREQ_CELLS = 4.5f

/**
 * One row of columns: the fixed ones at their widths, Message and Country
 * sharing the rest (Message the larger share, clipped; Country cut with an
 * ellipsis). The column heads use it too, so they line up.
 */
@Composable
private fun BandCells(
    t: BandType, cols: BandColumns, modifier: Modifier = Modifier,
    time: @Composable (Modifier) -> Unit, db: @Composable (Modifier) -> Unit, dt: @Composable (Modifier) -> Unit,
    freq: @Composable (Modifier) -> Unit, message: @Composable (Modifier) -> Unit, country: @Composable (Modifier) -> Unit,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(t.cell), verticalAlignment = Alignment.CenterVertically) {
        if (cols.time) time(Modifier.width(t.cell * TIME_CELLS))
        db(Modifier.width(t.cell * DB_CELLS))
        if (cols.dt) dt(Modifier.width(t.cell * DT_CELLS))
        if (cols.freq) freq(Modifier.width(t.cell * FREQ_CELLS))
        message(if (cols.country) Modifier.weight(1.7f) else Modifier.weight(1f))
        if (cols.country) country(Modifier.weight(1f))
    }
}

/** The column heads, as the desktop labels them. */
@Composable
private fun BandHead(t: BandType, cols: BandColumns, inset: Dp = 0.dp) {
    val size = t.size * 0.8f
    val head = @Composable { text: String, m: Modifier, end: Boolean ->
        Text(text, color = Dim, fontSize = size, maxLines = 1, textAlign = if (end) TextAlign.End else TextAlign.Start, modifier = m)
    }
    BandCells(t, cols, Modifier.fillMaxWidth().padding(horizontal = inset + 4.dp),
        time = { head(stringResource(R.string.shack_decodes_utc), it, false) },
        db = { head(stringResource(R.string.shack_decodes_db), it, true) },
        dt = { head(stringResource(R.string.shack_decodes_dt), it, true) },
        freq = { head(stringResource(R.string.shack_decodes_hz), it, true) },
        message = { head(stringResource(R.string.shack_decodes_message), it, false) },
        country = { head(stringResource(R.string.shack_decodes_country), it, false) })
}

/**
 * One decode, as the desktop's Band Activity row: UTC, dB, DT, Freq,
 * Message, Country, as [cols] allows. The time is on every row, muted
 * (Brian, 13/10/2026): shown on a slot's first row only, a list scrolled
 * to the middle of a slot had no time on screen at all. It costs no height,
 * and the slot rules still group the rows. Where the time column has gone
 * for want of width the slot's rule carries it, pinned ([BandEntry.Rule]).
 * A CQ, a message naming this station and our own over each have their
 * colour and a tinted band behind the whole row, so any of them is seen
 * across a room; ours says TX where the dB would be.
 */
@Composable
private fun BandRowView(r: BandRow, t: BandType, cols: BandColumns, modifier: Modifier = Modifier) {
    val d = r.decode
    val (fg, bg) = r.emphasis.colours()
    val mono = FontFamily.Monospace
    val bold = if (r.emphasis == Emphasis.MINE || r.emphasis == Emphasis.TX) FontWeight.Bold else FontWeight.Normal
    val cell = @Composable { text: String, m: Modifier, colour: Color, end: Boolean ->
        Text(text, color = colour, fontSize = t.size, fontFamily = mono, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Clip, textAlign = if (end) TextAlign.End else TextAlign.Start, modifier = m)
    }
    BandCells(t, cols, modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
        time = { cell(slotTime(d.slot), it, Dim, false) },
        db = { cell(if (d.tx) stringResource(R.string.shack_decodes_tx) else d.snr.toString(), it, if (d.tx) fg else fg.copy(alpha = 0.85f), true) },
        // Wide enough for "-0.0": at 44 dp the TV wrapped a negative DT onto a second line.
        dt = { cell(if (d.tx) "" else db(d.dt), it, fg, true) },
        freq = { cell(d.df.toString(), it, fg, true) },
        message = {
            Text(d.text + if (d.lowConfidence) " ?" else "", color = fg, fontSize = t.size, fontFamily = mono, fontWeight = bold,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Clip, modifier = it)
        },
        country = {
            Text(r.country, color = if (r.emphasis == Emphasis.NONE) Color(0xFFBDBDBD) else fg, fontSize = t.size * 0.9f,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = it)
        })
}

/**
 * A slot separator: a rule above each slot; where the time column has gone
 * for want of room, the rule carries the slot's time, and is [pinned] by
 * the list so the time of the slot at the top is always on screen.
 */
@Composable
private fun SlotRule(slot: Double, t: BandType, withTime: Boolean, dimmed: Boolean = false, pinned: Boolean = false) {
    val fade = if (dimmed) Modifier.alpha(DIMMED) else Modifier
    if (!withTime) {
        HorizontalDivider(color = Dim.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 3.dp).then(fade))
        return
    }
    Row(Modifier.fillMaxWidth().then(if (pinned) Modifier.background(PanelBg) else Modifier).padding(top = 4.dp).then(fade),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(slotTime(slot), color = Dim, fontSize = t.size * 0.75f, fontFamily = FontFamily.Monospace)
        HorizontalDivider(color = Dim.copy(alpha = 0.25f), modifier = Modifier.weight(1f))
    }
}

/** The docked panels' background, which a pinned rule must match to hide the rows scrolling under it. */
private val PanelBg = Color(0xFF0C0C0C)

/**
 * What a list lays out: a section's heading, a slot's rule (a divider, or
 * the pinned time where the rows have none) and a decode. [dimmed] is
 * everything above the newest section.
 */
private sealed interface BandEntry {
    val dimmed: Boolean
    data class Head(val head: SectionHead, override val dimmed: Boolean) : BandEntry
    data class Rule(val slot: Double, override val dimmed: Boolean) : BandEntry
    data class Line(val row: BandRow, override val dimmed: Boolean) : BandEntry
}

/**
 * [items] as entries. A rule goes above each slot after the first of its
 * section; where the rows carry no time ([timeInRows] false) one goes above
 * every slot, since it is the only place the time is.
 */
private fun entries(items: List<BandItem>, timeInRows: Boolean): List<BandEntry> {
    val out = ArrayList<BandEntry>()
    for (it in items) when (it) {
        is BandItem.Heading -> out += BandEntry.Head(it.head, it.dimmed)
        is BandItem.Line -> {
            if (it.row.startsSlot && (!timeInRows || out.lastOrNull() is BandEntry.Line)) out += BandEntry.Rule(it.row.decode.slot, it.dimmed)
            out += BandEntry.Line(it.row, it.dimmed)
        }
    }
    return out
}

/** The opacity of a section we have left: still readable, plainly not the band we are on. */
private const val DIMMED = 0.45f

/**
 * "— 20 m · 14.074 FT8 · 21:43:07 UTC —": where the decodes below were
 * heard. Radio names the band; NB has none, and says the dial.
 */
@Composable
private fun SectionHeading(h: SectionHead, t: BandType, dimmed: Boolean) {
    val freq = com.openhamshack.i18n.ShackFormat.frequency(Math.round(h.dialHz), localeTag(), "MHz", 3)
    val mode = h.mode.uppercase()
    val time = slotTime(h.since)
    val text = if (h.band.isEmpty()) stringResource(R.string.shack_section, freq, mode, time)
    else stringResource(R.string.shack_section_band, BandSections.bandText(h.band), freq, mode, time)
    Text(text, color = Accent, fontSize = t.size * 0.85f, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).then(if (dimmed) Modifier.alpha(DIMMED) else Modifier))
}

/**
 * Band Activity: every kept decode, oldest slot at the top and the newest
 * at the foot, followed as it arrives - unless the list has been scrolled
 * back, when it stays where it was put until scrolled to the foot again
 * (a tablet's list jumped away from the line being read every 15 s).
 * The columns are what fits its width ([BandColumns.forWidth]): all six
 * on a tablet, Message and Country with dB on a phone. No row takes focus:
 * on a TV this sits beside the scope while the D-pad goes on tuning; the
 * full list to scroll through there is [TvDecodesPanel].
 *
 * [onPick] answers a station heard (phone and tablet: Enable TX's arm,
 * which asks first); our own over is never offered.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DecodeList(decodes: SlotDecodes, myCall: String, size: TextUnit, modifier: Modifier = Modifier,
                        heads: Boolean = true, onPick: ((SlotDecode) -> Unit)? = null) {
    if (decodes.items.isEmpty()) return
    val cty = rememberCty()
    val items = remember(decodes, myCall, cty) { BandSections.items(decodes, myCall, cty) }
    val t = bandType(size)
    val state = rememberLazyListState()
    val follow = remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        // Decided each time a scroll by hand ends: at the foot, keep
        // following; anywhere else, stay put. The flow's first value is
        // the idle list before the follow below has run, at the top of a
        // long list - taken as "scrolled back", it left a tablet turned
        // upright two minutes behind the band; drop(1) skips it.
        snapshotFlow { state.isScrollInProgress }.drop(1).collect { moving -> if (!moving) follow.value = !state.canScrollForward }
    }
    BoxWithConstraints(modifier) {
        val cols = BandColumns.forWidth(maxWidth / t.cell)
        val laid = remember(items, cols.time) { entries(items, cols.time) }
        LaunchedEffect(laid.size) { if (follow.value) state.scrollToItem((laid.size - 1).coerceAtLeast(0)) }
        Column(Modifier.fillMaxSize()) {
            if (heads) BandHead(t, cols)
            LazyColumn(Modifier.weight(1f), state = state) {
                laid.forEach { e ->
                    when (e) {
                        is BandEntry.Head -> item { SectionHeading(e.head, t, e.dimmed) }
                        // Pinned while its slot scrolls under it, so the first slot in view always says when.
                        is BandEntry.Rule -> if (cols.time) item { SlotRule(e.slot, t, withTime = false, dimmed = e.dimmed) }
                        else stickyHeader { SlotRule(e.slot, t, withTime = true, dimmed = e.dimmed, pinned = true) }
                        is BandEntry.Line -> item {
                            // A station on a band we have left is not answered from here.
                            BandRowView(e.row, t, cols, Modifier.then(if (e.dimmed) Modifier.alpha(DIMMED) else Modifier)
                                .then(if (onPick != null && e.row.answerable && !e.dimmed) Modifier.clickable { onPick(e.row.decode) } else Modifier))
                        }
                    }
                }
            }
        }
    }
}

/**
 * The same list for a caller that sizes it by height and the old
 * big/compact switch (Flex's docked decodes): 18 sp on a TV, 14 sp on a
 * phone, and no heads when compact.
 */
@Composable
internal fun DecodeList(decodes: SlotDecodes, myCall: String, big: Boolean, height: Dp, modifier: Modifier = Modifier, compact: Boolean = false,
                        onPick: ((SlotDecode) -> Unit)? = null) {
    DecodeList(decodes, myCall, if (big) 18.sp else 14.sp, modifier.then(Modifier.fillMaxWidth().heightOrFill(height)),
        heads = !compact, onPick = onPick)
}

/** A fixed height, or (0 dp) whatever the parent gives. */
private fun Modifier.heightOrFill(h: Dp): Modifier = if (h > 0.dp) height(h) else this

/**
 * Every decode kept, full screen on a TV, to scroll back through with the
 * D-pad: each row is focusable so the list moves under the focus, and
 * BACK closes it. Drawn with everything beneath unfocusable (the caller
 * holds the scope's canFocus off while it is open; GUIDE 5).
 */
@Composable
internal fun TvDecodesPanel(e: EngineSession, onClose: () -> Unit, onPick: ((SlotDecode) -> Unit)? = null) {
    val u by e.ui.collectAsStateWithLifecycle()
    TvDecodesPanel(u.decodes, u.state.callsign, onClose, onPick)
}

/**
 * The same for any session's decodes: Flex's as NB/Radio's. [onPick]: OK
 * on a station heard hands it on (NB/Radio's Enable TX reply); on our own
 * over, or with no [onPick], OK does nothing but scroll.
 */
@Composable
internal fun TvDecodesPanel(decodes: SlotDecodes, myCall: String, onClose: () -> Unit, onPick: ((SlotDecode) -> Unit)? = null) {
    val last = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(100); runCatching { last.requestFocus() } }
    val cty = rememberCty()
    val items = remember(decodes, myCall, cty) { BandSections.items(decodes, myCall, cty) }
    val t = bandType(18.sp)
    // The foot of the list takes the focus: the last decode, the newest section's.
    val lastLine = items.indexOfLast { it is BandItem.Line }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = TvSafeH, vertical = TvSafeV),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.shack_decodes_title, decodes.items.lastOrNull()?.mode?.uppercase().orEmpty()), fontSize = 30.sp,
            fontWeight = FontWeight.Bold)
        if (lastLine < 0) Text(stringResource(R.string.shack_decodes_empty), color = Dim, fontSize = 18.sp)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Each row sits inside a compact TvItem (12 dp each side, for its focus ring);
            // the heads are inset to match, and the columns chosen on what is left.
            val cols = BandColumns.forWidth((maxWidth - 24.dp) / t.cell)
            Column(Modifier.fillMaxSize()) {
                BandHead(t, cols, inset = 12.dp)
                val laid = remember(items, cols.time) { entries(items, cols.time) }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (laid.size - 1).coerceAtLeast(0))
                LazyColumn(Modifier.weight(1f), state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    itemsIndexed(laid) { i, e ->
                        when (e) {
                            is BandEntry.Head -> SectionHeading(e.head, t, e.dimmed)
                            is BandEntry.Rule -> SlotRule(e.slot, t, withTime = !cols.time, dimmed = e.dimmed)
                            is BandEntry.Line -> {
                                val r = e.row
                                TvItem(onClick = { if (r.answerable && !e.dimmed) onPick?.invoke(r.decode) },
                                    focus = if (i == laid.indexOfLast { it is BandEntry.Line }) last else null,
                                    // Compact: the full-size chip's padding let six rows fill a TV.
                                    modifier = Modifier.fillMaxWidth().then(if (e.dimmed) Modifier.alpha(DIMMED) else Modifier), compact = true) {
                                    BandRowView(r, t, cols, Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
        if (onPick != null && lastLine >= 0) Text(stringResource(R.string.shack_decodes_reply_hint), color = Dim, fontSize = 15.sp)
        TvItem(onClick = onClose, focus = if (lastLine < 0) last else null) { Text(stringResource(R.string.shack_done), fontSize = 16.sp) }
    }
}
