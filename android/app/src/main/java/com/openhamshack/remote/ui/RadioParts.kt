package com.openhamshack.remote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.AntennaChoice
import com.openhamshack.remote.shack.BandButton
import com.openhamshack.remote.shack.BandFilter
import com.openhamshack.remote.shack.BandGrid
import com.openhamshack.remote.shack.BandSection
import com.openhamshack.remote.shack.Bookmark
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.EngineUi
import com.openhamshack.remote.shack.ListenAreas
import com.openhamshack.remote.shack.ModeAudio
import com.openhamshack.remote.shack.PlanStripSegment
import com.openhamshack.remote.shack.Receiver
import com.openhamshack.remote.shack.RecentBands
import com.openhamshack.remote.shack.RxOnlyWhy
import com.openhamshack.remote.shack.Span
import com.openhamshack.remote.shack.Tuning
import com.openhamshack.remote.shack.TxSlot

// The pieces of Radio's overhauled screen on a phone and a tablet (Brian's
// mockups, approved 23/10/2026: Main, Bands, Dab and Tablet), drawn here
// once and arranged by RadioPhone: the header, one readout block at the
// top (the frequency, its chips, the S-meter), the scope, the tuning row
// (big ◀ ▶ that only tune, the step between them with its own − and +),
// the bands (a button opening the picker, and the last few used), and the
// bottom row (Mute, and the transmit button or the amber "Receive only"
// strip). What they decide is in shack/RadioScreen.kt, tested there.
//
// The colours are the mockups', put into the app's palette: the one accent
// ([Accent], the mockups' #4FC3F7 already), [Amber] for receive-only, and
// these greys for the cards and the lines between them.

internal val RadioCard = Color(0xFF15181D)
internal val RadioRaised = Color(0xFF1E232A)
internal val RadioLine = Color(0xFF2C333C)
internal val RadioTrack = Color(0xFF262C34)
internal val RadioScopeBg = Color(0xFF05080C)
internal val RxStripBg = Color(0xFF1E1A10)
internal val RxStripLine = Color(0xFF5C4718)
/** Text on the accent: the mockups' near-black blue. */
internal val OnAccent = Color(0xFF06222F)
private val OnAmber = Color(0xFF1A1406)

/** The receiver's word from this app's catalog; null for a device the app does not know. */
@Composable
internal fun receiverName(r: Receiver?): String? = when (r) {
    null -> null
    Receiver.SIMULATOR -> stringResource(R.string.shack_rx_simulator)
    Receiver.PLUTO -> stringResource(R.string.shack_rx_pluto)
    Receiver.LIBRESDR -> stringResource(R.string.shack_rx_libresdr)
    Receiver.HL2 -> stringResource(R.string.shack_rx_hl2)
    Receiver.RTLSDR -> stringResource(R.string.shack_rx_rtlsdr)
    Receiver.SDRPLAY -> stringResource(R.string.shack_rx_sdrplay)
    Receiver.SOAPY -> stringResource(R.string.shack_rx_soapy)
}

/** "GM4BRI · SDRplay": the box, and the receiver when the engine names it. */
@Composable
internal fun boxAndReceiver(e: EngineSession, u: EngineUi): String =
    receiverName(Receiver.of(u.state.device))?.let { stringResource(R.string.shack_header_box_rx, e.controllerName, it) } ?: e.controllerName

/** A mode as the readout shows it: the server's name upper-cased ("AM", "WFM"). */
internal fun modeWord(mode: String): String = mode.uppercase().ifEmpty { "–" }

/** The small amber RX tag a receive-only area carries. */
@Composable
internal fun RxTag(size: TextUnit = 11.sp) {
    Text(stringResource(R.string.shack_area_rx), fontSize = size, fontWeight = FontWeight.Bold, color = OnAmber, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Amber).padding(horizontal = 5.dp))
}

/** One of the readout's chips: [filled] is the mode (the accent), the others are outlined greys. */
@Composable
internal fun ReadoutChip(filled: Boolean, onClick: (() -> Unit)?, tag: String, height: Dp = 36.dp, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        Modifier.height(height).clip(shape)
            .background(if (filled) Accent else RadioRaised)
            .then(if (filled) Modifier else Modifier.border(1.dp, RadioLine, shape))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = content,
    )
}

/** The band chip's words: the band or area, and the plan's segment for an amateur band ("2 · FM calling"). */
@Composable
internal fun bandChipText(u: EngineUi, b: BandButton): String {
    val seg = u.state.segment.takeIf { b.area == null && it.isNotBlank() }
    return if (seg != null) stringResource(R.string.shack_band_segment, bandLabel(b), seg) else bandLabel(b)
}

/** The S-meter line: the letter, the bar, and the calibrated reading when the server has one. */
@Composable
internal fun SMeterLine(u: EngineUi, barHeight: Dp = 8.dp, textSize: TextUnit = 13.sp, letterSize: TextUnit = 12.sp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_smeter_letter), color = Dim, fontSize = letterSize, modifier = Modifier.widthIn(min = 14.dp))
        // -120 dBFS (a receiver's floor) to -20 (a strong local), as SignalMeter has always drawn it.
        val f = u.rxDb?.let { ((it + 120.0) / 100.0).coerceIn(0.0, 1.0).toFloat() } ?: 0f
        Box(Modifier.weight(1f).height(barHeight).clip(RoundedCornerShape(barHeight / 2)).background(RadioTrack)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(barHeight / 2)).background(Accent))
        }
        Text(u.sMeter.reading.orEmpty(), color = Color.White, fontFamily = FontFamily.Monospace, fontSize = textSize, maxLines = 1,
            textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 44.dp).testTag("sMeter"))
    }
}

/**
 * The readout block (Main and Tablet): the frequency big - a tap types one -
 * then the chips: the mode (▾ opens the modes), the band or area the dial
 * is in (an area tagged RX; a tap opens the band picker; nothing between
 * bands, where "Between bands" used to say so), and the antenna (the
 * existing picker). Under them the S-meter. On a tablet held sideways
 * ([row]) the chips and the meter sit to the right of the frequency.
 */
@Composable
internal fun RadioReadout(
    u: EngineUi, antenna: AntennaChoice?, row: Boolean, digits: TextUnit, unit: TextUnit,
    onType: () -> Unit, onMode: () -> Unit, onBands: () -> Unit, onAntenna: () -> Unit, modifier: Modifier = Modifier,
) {
    val (number, unitWord) = dialParts(u.dialHz)
    val dial = @Composable { m: Modifier ->
        Row(m, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                number,
                style = TextStyle(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = if (u.pendingDialHz != null) Accent else Color.White),
                maxLines = 1, softWrap = false,
                autoSize = TextAutoSize.StepBased(minFontSize = 22.sp, maxFontSize = digits, stepSize = 2.sp),
                modifier = Modifier.weight(1f, fill = false).clickable(onClick = onType).testTag("dial"),
            )
            Text(unitWord, color = Dim, fontSize = unit, maxLines = 1, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
    val chips = @Composable { m: Modifier ->
        FlowRow(m, horizontalArrangement = Arrangement.spacedBy(8.dp, if (row) Alignment.End else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadoutChip(true, onMode, "mode") {
                Text(stringResource(R.string.shack_chip_open, modeWord(u.state.mode)), color = OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            u.currentBand?.let { b ->
                ReadoutChip(false, onBands, "bandChip") {
                    Text(bandChipText(u, b), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 180.dp))
                    if (b.rxTag) RxTag()
                }
            }
            // The ▾ outside the name: a long label ("Antenna C — Loop Antenna") is cut, never the sign that it opens.
            if (antenna != null) ReadoutChip(false, onAntenna, "antenna") {
                Text(antennaNow(antenna), color = Color.White, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                Text("▾", color = Color.White, fontSize = 12.sp)
            }
        }
    }
    val shape = RoundedCornerShape(20.dp)
    if (row) {
        Row(modifier.clip(shape).background(RadioCard).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            dial(Modifier.weight(1.1f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                chips(Modifier.fillMaxWidth())
                SMeterLine(u)
            }
        }
    } else {
        Column(modifier.clip(shape).background(RadioCard).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            dial(Modifier.fillMaxWidth())
            chips(Modifier.fillMaxWidth())
            SMeterLine(u)
        }
    }
}

/**
 * The scope (Main's middle): Radio's ScopeView - spectrum, waterfall, the
 * plan strip, the passband shaded and the dial's line - in a rounded card,
 * with its edges' frequencies and "drag to tune" along the foot. A tap tunes
 * to the signal tapped (where the mode hears it, ModeAudio), as before; a
 * drag sweeps the dial channel by channel (Tuning.dragged); a long press
 * opens the scope's sheet.
 */
@Composable
internal fun RadioScope(
    e: EngineSession, u: EngineUi, marks: List<Bookmark>, named: Bookmark?, plan: List<PlanStripSegment>,
    big: Boolean, onLong: () -> Unit, modifier: Modifier,
) {
    val range = scopeRange(e)
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(RadioScopeBg)
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = { onLong() }) { pos ->
                val sc = e.scopeData
                if (sc.endHz > sc.startHz) {
                    val hz = sc.startHz + (sc.endHz - sc.startHz) * (pos.x / size.width)
                    // A tap is on a signal: the dial goes where the mode hears it (ModeAudio).
                    e.setDial(ModeAudio.dialFor(hz, ModeAudio.of(e.ui.value.state)))
                }
            }
        }
        .pointerInput(Unit) {
            var from = 0.0
            var dx = 0f
            detectHorizontalDragGestures(onDragStart = { from = e.ui.value.dialHz ?: Double.NaN; dx = 0f }) { change, d ->
                change.consume()
                val now = e.ui.value
                val sc = e.scopeData
                if (from.isNaN() || sc.endHz <= sc.startHz) return@detectHorizontalDragGestures
                dx += d
                val hz = Tuning.dragged(from, (dx / size.width).toDouble(), sc.endHz - sc.startHz, now.stepHz, grab = now.span == Span.ZOOM,
                    origin = ListenAreas.origin(from, now.stepHz), min = now.dialMin, max = now.dialMax)
                if (hz != now.dialHz) e.setDial(hz)
            }
        }
        .testTag("scope")) {
        ScopeView(e, u.dialHz, Modifier.fillMaxSize(), marks = marks, named = named, passband = ModeAudio.of(u.state),
            labelSize = if (big) 16.sp else 13.sp, plan = plan, stripHeight = if (big) 22.dp else 16.dp, stripText = if (big) 13.sp else 10.sp)
        val small = if (big) 12.sp else 11.sp
        range?.let { (lo, hi) ->
            Text(edgeText(lo), color = Dim, fontSize = small, modifier = Modifier.align(Alignment.BottomStart).padding(12.dp, 8.dp))
            Text(edgeText(hi), color = Dim, fontSize = small, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp, 8.dp))
        }
        Text(stringResource(R.string.shack_scope_drag), color = Dim, fontSize = small, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}

/** A plain square-ish button of the new screen: a rounded card that is the button, with [description] for a screen reader. */
@Composable
internal fun RadioKey(
    onClick: () -> Unit, modifier: Modifier, tag: String, description: String? = null, colour: Color = RadioRaised,
    radius: Dp = 18.dp, enabled: Boolean = true, content: @Composable () -> Unit,
) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(colour).clickable(enabled = enabled, onClick = onClick)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier).testTag(tag),
        contentAlignment = Alignment.Center) { content() }
}

/**
 * The tuning row (Main, Tablet): ◀ and ▶ either side that only tune, and
 * between them the step in its own box with its own − and + - the step went
 * only up before (Brian, Tab S9, 13/10/2026), and then sat in a row of six
 * small buttons; now it is a thing of its own between the two that tune.
 */
@Composable
internal fun TuningRow(e: EngineSession, u: EngineUi, height: Dp, modifier: Modifier = Modifier) {
    val inner = height - 16.dp
    Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioKey({ e.tune(-1) }, Modifier.width(height).fillMaxHeight(), "tuneDown", stringResource(R.string.shack_tune_down_desc)) {
            Text("◀", fontSize = 22.sp, color = Color.White)
        }
        Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(RadioCard).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            RadioKey({ e.step(-1) }, Modifier.size(inner), "stepDown", stringResource(R.string.shack_step_smaller_desc), RadioTrack, 14.dp) {
                Text("−", fontSize = 22.sp, color = Color.White)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.shack_step_label), color = Dim, fontSize = 11.sp)
                Text(stepText(u.stepHz), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1,
                    color = Color.White, modifier = Modifier.testTag("step"))
            }
            RadioKey({ e.step(1) }, Modifier.size(inner), "stepUp", stringResource(R.string.shack_step_bigger_desc), RadioTrack, 14.dp) {
                Text("+", fontSize = 22.sp, color = Color.White)
            }
        }
        RadioKey({ e.tune(1) }, Modifier.width(height).fillMaxHeight(), "tuneUp", stringResource(R.string.shack_tune_up_desc)) {
            Text("▶", fontSize = 22.sp, color = Color.White)
        }
    }
}

/** The phone's bands line (Main): "Bands ▾", which opens the picker, then the last few bands used, one press each. */
@Composable
internal fun RecentBandsRow(e: EngineSession, u: EngineUi, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioKey(onOpen, Modifier.fillMaxHeight(), "bands", colour = RadioTrack, radius = 14.dp) {
            Text(stringResource(R.string.shack_bands_open), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White,
                modifier = Modifier.padding(horizontal = 14.dp))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecentBands.shown(u.recentBands, u.bandButtons, u.currentBand).forEach { b ->
                val shape = RoundedCornerShape(14.dp)
                Box(Modifier.height(44.dp).widthIn(min = 52.dp).clip(shape).border(1.dp, RadioLine, shape)
                    .clickable(enabled = !u.viewOnly) { e.bandTo(b) }.padding(horizontal = 12.dp).testTag("recent-${b.name}"),
                    contentAlignment = Alignment.Center) {
                    Text(bandLabel(b), fontSize = 15.sp, maxLines = 1, color = if (u.viewOnly) Dim else Color.White)
                }
            }
        }
    }
}

/**
 * One band's tile in the picker or the tablet's grid: its word, an RX tag
 * on a receive-only area, the dial's own band lit. A press goes as a band
 * button always has (EngineSession.bandTo: the remembered spot; again,
 * FT8, SSB and CW in turn).
 */
@Composable
private fun BandTile(e: EngineSession, u: EngineUi, b: BandButton, height: Dp, text: TextUnit, modifier: Modifier, onPicked: () -> Unit) {
    val on = b == u.currentBand
    val shape = RoundedCornerShape(if (height > 48.dp) 12.dp else 10.dp)
    Column(
        modifier.height(height).clip(shape).background(if (on) Accent else RadioRaised)
            .then(if (on) Modifier else Modifier.border(1.dp, RadioLine, shape))
            .clickable(enabled = !u.viewOnly) { e.bandTo(b); onPicked() }.testTag("band-${b.name}"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(bandLabel(b), fontSize = text, fontWeight = FontWeight.Medium, maxLines = 1,
            color = if (on) OnAccent else if (u.viewOnly) Dim else Color.White)
        if (b.rxTag) RxTag(if (height > 48.dp) 10.sp else 9.sp)
    }
}

/** [buttons] as rows of [columns] equal tiles. */
@Composable
private fun TileGrid(e: EngineSession, u: EngineUi, buttons: List<BandButton>, columns: Int, height: Dp, gap: Dp, text: TextUnit, onPicked: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        buttons.chunked(columns).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                line.forEach { b -> BandTile(e, u, b, height, text, Modifier.weight(1f), onPicked) }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The receiver's reach for the picker's title line, when the server says it (rxRange). */
@Composable
private fun reachText(e: EngineSession, u: EngineUi): String? {
    val r = u.status?.rxRange ?: return null
    val tag = localeTag()
    val range = stringResource(R.string.shack_bands_range, reachEnd(r.first, tag), reachEnd(r.second, tag))
    return receiverName(Receiver.of(u.state.device))?.let { stringResource(R.string.shack_header_box_rx, it, range) } ?: range
}

/** One end of a receiver's reach, in the unit that makes it short: "1 kHz", "2 GHz", "30 MHz". */
internal fun reachEnd(hz: Double, tag: String): String {
    val n = Math.round(hz)
    val unit = when { n >= 1_000_000_000L -> "GHz"; n >= 1_000_000L -> "MHz"; n >= 1_000L -> "kHz"; else -> "Hz" }
    val scale = com.openhamshack.i18n.ShackFormat.UNITS.getValue(unit)
    return com.openhamshack.i18n.ShackFormat.frequency(n, tag, unit, if (n % scale == 0L) 0 else 1)
}

/**
 * A band's edges in one unit, each to as few decimals as it needs (no more
 * than three): (87.5, 108, MHz) for broadcast FM, (118, 137, MHz) for
 * airband, (148.5, 283.5, kHz) for LW. The unit is the upper edge's.
 */
internal fun rangeParts(loHz: Long, hiHz: Long, tag: String): Triple<String, String, String> {
    val unit = when { hiHz >= 1_000_000_000L -> "GHz"; hiHz >= 1_000_000L -> "MHz"; hiHz >= 1_000L -> "kHz"; else -> "Hz" }
    val scale = com.openhamshack.i18n.ShackFormat.UNITS.getValue(unit)
    fun one(hz: Long): String {
        var d = 0
        var step = scale
        while (d < 3 && hz % step != 0L) { d++; step /= 10 }
        return com.openhamshack.i18n.ShackFormat.frequency(hz, tag, unit, d).substringBeforeLast('\u00a0')
    }
    return Triple(one(loHz), one(hiHz), unit)
}

/**
 * The band picker (Bands): the filter chips, then "LF · MF · HF" and "VHF
 * · UHF · Microwave", tiles in frequency order, the dial's band lit. A
 * phone's bottom sheet; [onPicked] closes it.
 */
@Composable
internal fun BandPicker(e: EngineSession, u: EngineUi, columns: Int, onPicked: () -> Unit, onLocal: (() -> Unit)? = null) {
    var filter by remember { mutableStateOf(BandFilter.ALL) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.shack_bands_title), fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            reachText(e, u)?.let { Text(it, fontSize = 12.sp, color = Dim, maxLines = 1) }
        }
        // Local frequencies (TvPolishLocal): what is on the air near the station, a sheet of its own.
        if (onLocal != null) RadioKey(onLocal, Modifier.fillMaxWidth().height(44.dp), "localOpen", colour = RadioRaised, radius = 14.dp) {
            Text(stringResource(R.string.shack_local_title), fontSize = 15.sp, color = Accent)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((f, word) in listOf(BandFilter.ALL to R.string.shack_bands_all, BandFilter.AMATEUR to R.string.shack_bands_amateur,
                BandFilter.LISTEN to R.string.shack_bands_listen)) {
                val on = f == filter
                val shape = RoundedCornerShape(18.dp)
                Box(Modifier.height(36.dp).clip(shape).background(if (on) Accent else Color.Transparent)
                    .then(if (on) Modifier else Modifier.border(1.dp, RadioLine, shape))
                    .clickable { filter = f }.padding(horizontal = 14.dp).testTag("filter-${f.name.lowercase()}"),
                    contentAlignment = Alignment.Center) {
                    Text(stringResource(word), fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (on) OnAccent else Color.White)
                }
            }
        }
        BandGrid.sections(u.bandButtons, filter).forEach { (section, list) ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(if (section == BandSection.LOW) R.string.shack_bands_low else R.string.shack_bands_high),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Dim, letterSpacing = 0.6.sp)
                TileGrid(e, u, list, columns, 52.dp, 8.dp, 15.sp, onPicked)
            }
        }
        Text(stringResource(R.string.shack_bands_note), fontSize = 13.sp, color = Dim, lineHeight = 19.sp)
    }
}

/** The tablet's band grid (Tablet's right column), always there: every band and area in one grid, in frequency order. */
@Composable
internal fun BandGridCard(e: EngineSession, u: EngineUi, modifier: Modifier, onLocal: (() -> Unit)? = null) {
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(RadioCard).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.shack_bands_heading), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Dim,
                letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
            RxTag(10.sp)
            Text(stringResource(R.string.shack_bands_legend), fontSize = 12.sp, color = Dim, modifier = Modifier.padding(start = 4.dp))
        }
        if (onLocal != null) RadioKey(onLocal, Modifier.fillMaxWidth().height(40.dp), "localOpen", colour = RadioRaised, radius = 10.dp) {
            Text(stringResource(R.string.shack_local_title), fontSize = 14.sp, color = Accent)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            TileGrid(e, u, u.bandButtons, 5, 46.dp, 6.dp, 14.sp, onPicked = {})
        }
    }
}

/** A speaker, crossed when muted: drawn, as the app carries no icon set. */
@Composable
internal fun SpeakerIcon(muted: Boolean, size: Dp = 22.dp, colour: Color = Color.White) {
    Canvas(Modifier.size(size)) {
        val u = this.size.width / 24f
        val stroke = Stroke(width = 2 * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val body = Path().apply {
            moveTo(4 * u, 9 * u); lineTo(8 * u, 9 * u); lineTo(13 * u, 5 * u); lineTo(13 * u, 19 * u); lineTo(8 * u, 15 * u)
            lineTo(4 * u, 15 * u); close()
        }
        drawPath(body, colour, style = stroke)
        if (muted) {
            drawLine(colour, Offset(16 * u, 9 * u), Offset(22 * u, 15 * u), strokeWidth = 2 * u, cap = StrokeCap.Round)
            drawLine(colour, Offset(22 * u, 9 * u), Offset(16 * u, 15 * u), strokeWidth = 2 * u, cap = StrokeCap.Round)
        } else {
            drawArc(colour, -50f, 100f, false, Offset(13 * u, 8 * u), androidx.compose.ui.geometry.Size(8 * u, 8 * u), style = stroke)
        }
    }
}

/** The words of the amber strip: the server's sentence where it gave one, else "Receive only", with the area when there is one. */
@Composable
internal fun rxOnlyText(slot: TxSlot): String {
    if ((slot.why == RxOnlyWhy.RADIO || slot.why == RxOnlyWhy.MODE) && slot.sentence.isNotBlank()) return slot.sentence
    return slot.area?.let { stringResource(R.string.shack_rx_only_area, bandLabel(it)) } ?: stringResource(R.string.shack_rx_only)
}

/**
 * The bottom row (Main, Tablet, Dab): Mute, and beside it "Hold to
 * transmit" (EnginePtt, unchanged: the lease, the hold, the refusals) or,
 * wherever nothing could be sent (TxSlot), the amber "Receive only" strip.
 */
@Composable
internal fun RadioBottomRow(e: EngineSession, u: EngineUi, height: Dp, modifier: Modifier = Modifier) {
    val slot = TxSlot.of(e.ptt != null, u.currentBand, u.state)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        RadioKey({ e.mute(!u.muted) }, Modifier.size(height), "mute",
            stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute), radius = 16.dp,
            colour = if (u.muted) Amber.copy(alpha = 0.25f) else RadioRaised) {
            SpeakerIcon(u.muted, if (height > 56.dp) 24.dp else 22.dp, if (u.muted) Amber else Color.White)
        }
        if (slot.ptt) Column(Modifier.weight(1f)) { EnginePtt(e, height = height) }
        else Box(Modifier.weight(1f).height(height).clip(RoundedCornerShape(16.dp)).background(RxStripBg)
            .border(1.dp, RxStripLine, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp).testTag("rxOnly"),
            contentAlignment = Alignment.Center) {
            Text(rxOnlyText(slot), color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The header (Main, Tablet): Back, "Radio" over the box and its receiver,
 * the status chips that only show when something is unusual (simulator,
 * on the air, reconnecting...), and at the right [actions] (⋮ on a phone,
 * Scope and Controls on a tablet).
 */
@Composable
internal fun RadioHeader(e: EngineSession, u: EngineUi, onBack: () -> Unit, inline: Boolean, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RadioKey(onBack, Modifier.size(44.dp), "back", stringResource(R.string.shack_back), colour = Color.Transparent, radius = 22.dp) {
            Canvas(Modifier.size(22.dp)) {
                val k = size.width / 24f
                drawLine(Color.White, Offset(15 * k, 5 * k), Offset(8 * k, 12 * k), strokeWidth = 2 * k, cap = StrokeCap.Round)
                drawLine(Color.White, Offset(8 * k, 12 * k), Offset(15 * k, 19 * k), strokeWidth = 2 * k, cap = StrokeCap.Round)
            }
        }
        if (inline) {
            Text(e.app.name, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(boxAndReceiver(e, u), fontSize = 14.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                Text(e.app.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(boxAndReceiver(e, u), fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RadioStatusChips(u)
        actions()
    }
}

/** Only what is unusual, as Flex's header: on the air, reconnecting, NR on, muted, no audio, the simulator. */
@Composable
private fun RadioStatusChips(u: EngineUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (u.status?.onAir == true) StatusPill(stringResource(R.string.shack_on_air_short), OnAirRed)
        if (!u.connected) StatusPill(stringResource(if (u.state.dialHz == null) R.string.shack_connecting else R.string.shack_reconnecting), Amber)
        if (u.state.nr == true) StatusPill(stringResource(R.string.shack_nr_chip), Accent)
        if (u.muted) StatusPill(stringResource(R.string.shack_muted), Amber)
        else if (u.audioRate <= 0 && u.connected) StatusPill(stringResource(R.string.shack_no_audio), Dim)
        if (u.status?.simulator == true) StatusPill(stringResource(R.string.shack_simulator), Amber)
    }
}

@Composable
private fun StatusPill(text: String, colour: Color) {
    Text(text, color = colour, fontSize = 12.sp, maxLines = 1,
        modifier = Modifier.border(1.dp, colour, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 3.dp))
}

/** A heading in the mockups' small capitals style: 12 sp, bold, spaced. */
@Composable
internal fun SmallHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Dim, letterSpacing = 0.6.sp, maxLines = 1, modifier = modifier)
}

/** How many stations a scan found on a block, said with the catalog's plural. */
@Composable
internal fun foundServices(n: Int): String = pluralStringResource(R.plurals.shack_dab_found_services, n, n)
