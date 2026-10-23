package com.openhamshack.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import com.openhamshack.remote.shack.Receiver
import com.openhamshack.remote.shack.TxSlot
import com.openhamshack.remote.shack.splitTail
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.AntennaChoice
import com.openhamshack.remote.shack.BandButton
import com.openhamshack.remote.shack.BandFilter
import com.openhamshack.remote.shack.BandGrid
import com.openhamshack.remote.shack.BandSection
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.EngineUi
import com.openhamshack.remote.shack.Tuning

// Radio on a TV, as Brian's Tv mockup (approved 23/10/2026), for reading
// from a sofa: the readout at the top left, the mode and the band or area
// at the top right over the S-meter, the scope in the middle, and one row
// of focusable controls along the foot - Bands, Step, Mode, Antenna, Mute -
// with the keys' two-line hint at its right. TvEngine arranges these and
// keeps everything else (the picker, the controls row, the settings, the
// decoder panels, Enable TX) as it was; NB's TV screen is unchanged.
//
// The mockup is drawn at 1280x720; a Fire TV is 960x540 dp, so its sizes
// are taken at three quarters (72 px digits are 54 sp here, 64 px buttons
// 48 dp), with nothing under the 16 sp a TV is read at.

/**
 * The readout (Tv's top, polished as TvPolish): a heading line - RADIO, the
 * box, its receiver and the antenna in use, and at the right the unusual
 * states and "Receive only" where the area, the radio or the mode cannot
 * transmit - then the frequency in light Roboto with tabular figures, its
 * last two digits smaller and dimmer, the mode as a pill, the band or area
 * and its edges; the Signal card at the right. In DAB only the heading
 * line: the station is the Now playing card below (TvDabScreen).
 */
@Composable
internal fun TvRadioReadout(e: EngineSession, u: EngineUi) {
    val dab = u.dab
    val antenna = AntennaChoice.of(u.state)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(if (dab != null) R.string.shack_radio_dab_heading else R.string.shack_radio_heading), fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Accent, maxLines = 1)
            Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A4654)))
            val line = if (dab != null) listOfNotNull(stringResource(R.string.shack_dab_block, dab.block.label), edgeText(dab.block.centreHz),
                dab.ensemble.ifEmpty { null })
            else listOfNotNull(e.controllerName, receiverName(Receiver.of(u.state.device)), antenna?.let { antennaNow(it) })
            Text(line.joinToString(" · "), fontSize = 15.sp, color = Color(0xFFA7B3C0), maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            val small = 15.sp
            if (!u.connected) Text(stringResource(if (u.state.dialHz == null) R.string.shack_connecting else R.string.shack_reconnecting),
                color = Amber, fontSize = small, maxLines = 1)
            if (u.status?.onAir == true) Text(stringResource(R.string.shack_on_air_short), color = OnAirRed, fontSize = small, maxLines = 1)
            if (u.status?.simulator == true) Text(stringResource(R.string.shack_simulator), color = Amber, fontSize = small, maxLines = 1)
            if (u.muted) Text(stringResource(R.string.shack_muted), color = PolishDim, fontSize = small, maxLines = 1)
            else if (u.audioRate <= 0 && u.connected) Text(stringResource(R.string.shack_no_audio), color = PolishDim, fontSize = small, maxLines = 1)
            // A TV never transmits, so "Receive only" here is about the area, the radio or the mode, not the TV.
            val slot = TxSlot.of(true, u.currentBand, u.state)
            if (!slot.ptt || dab != null) Text(stringResource(R.string.shack_rx_only), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = PolishAmber, maxLines = 1, modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(PolishAmber.copy(alpha = 0.12f))
                    .border(1.dp, PolishAmber.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
        }
        if (dab == null) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val (number, unit) = dialParts(u.dialHz)
                val (head, tail) = splitTail(number, unit)
                val colour = if (u.pendingDialHz != null) Accent else PolishText
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(head, fontSize = 80.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, color = colour, maxLines = 1,
                        style = TextStyle(fontFeatureSettings = TABULAR, lineHeight = 80.sp))
                    if (tail.isNotEmpty()) Text(tail, fontSize = 38.sp, fontWeight = FontWeight.Light, color = Color(0xFF6F7C8A), maxLines = 1,
                        style = TextStyle(fontFeatureSettings = TABULAR), modifier = Modifier.padding(bottom = 8.dp))
                    Text(unit, fontSize = 18.sp, color = PolishDim, maxLines = 1, modifier = Modifier.padding(start = 10.dp, bottom = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (u.state.mode == "wfm") stringResource(R.string.shack_mode_wfm) else modeWord(u.state.mode), fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, color = Color(0xFF06202C), maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Accent).padding(horizontal = 9.dp, vertical = 3.dp))
                    u.currentBand?.let { b ->
                        Text(bandChipText(u, b), fontSize = 17.sp, color = Color(0xFFD6DEE6), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 300.dp))
                        val (lo, hi, unitWord) = rangeParts(b.band.loHz, b.band.hiHz, localeTag())
                        Text(stringResource(R.string.shack_band_range, lo, hi, unitWord), fontSize = 14.sp, color = PolishQuiet, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            SignalCard(u, Modifier.width(300.dp))
        }
        u.toast?.let { Text(it, color = PolishDim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/**
 * The bottom row (Tv's foot): one focusable row - Bands, Step, Mode,
 * Antenna, Mute - and the hint. The left/right conflict the first TV Step
 * had (23/10/2026: Left and Right both moved focus and changed the step) is
 * settled the mockup's way: on Step, Left and Right change the step, and at
 * the smallest or largest step the key is passed on so focus still moves
 * along the row; elsewhere in the row they move focus. Up leaves the row
 * for [onUp] (the scope, or DAB's list) from any item; Down stays. A held
 * key repeats the step but never carries focus off it.
 */
@Composable
internal fun TvRadioRow(
    e: EngineSession, u: EngineUi, antenna: AntennaChoice?, first: FocusRequester,
    onBands: () -> Unit, onMode: () -> Unit, onAntenna: () -> Unit, onUp: () -> Unit, modifier: Modifier = Modifier,
    /** Focus is on the scope (the keys' surface): the hint says Left and Right tune. */
    onSurface: Boolean = true,
) {
    var onStep by remember { mutableStateOf(false) }
    Row(
        modifier.onKeyEvent { k ->
            if (k.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (k.key) {
                Key.DirectionUp -> { onUp(); true }
                Key.DirectionDown -> true
                else -> false
            }
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        TvItem(onClick = onBands, focus = first) { Text(stringResource(R.string.shack_tv_bands), fontSize = 17.sp) }
        if (u.dab == null) {
            // OK raises it, as the controls row's Step always did (REMOTE-KEYS §4: OK, OK changes the step).
            TvItem(onClick = { e.step(1) }, modifier = Modifier.onFocusChanged { onStep = it.isFocused }.onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val by = when (k.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                val now = e.ui.value.stepHz
                if (Tuning.nextStep(e.kind, now, by) != now) { e.step(by); true }
                else k.nativeKeyEvent.repeatCount > 0
            }) {
                Text("◀", fontSize = 18.sp, color = Accent)
                // A fixed width, so the row does not shift along as the step's words change length.
                Column(Modifier.padding(horizontal = 10.dp).widthIn(min = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.shack_step_label), fontSize = 12.sp, color = Dim)
                    Text(stepText(u.stepHz), fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Text("▶", fontSize = 18.sp, color = Accent)
            }
            if (u.offered.isNotEmpty()) TvItem(onClick = onMode) {
                Text(stringResource(R.string.shack_tv_mode_item, modeWord(u.state.mode)), fontSize = 17.sp, maxLines = 1)
            }
        } else {
            // DAB's row (TvPolishDab): the block, stepped as Step is - Left and Right change it,
            // passed on at the ends of Band III - and Scan all blocks, or Stop while it runs.
            val refused = stringResource(R.string.shack_dab_view_only)
            val d = u.dab
            TvItem(onClick = { if (u.viewOnly) e.note(refused) else e.dabBlock(1) }, modifier = Modifier.onFocusChanged { onStep = it.isFocused }.onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val by = when (k.key) {
                    Key.DirectionLeft -> -1
                    Key.DirectionRight -> 1
                    else -> return@onPreviewKeyEvent false
                }
                if (u.viewOnly) { e.note(refused); return@onPreviewKeyEvent true }
                val now = e.ui.value.dab?.block ?: return@onPreviewKeyEvent false
                if (com.openhamshack.remote.shack.DabBlocks.step(now, by) != now) { e.dabBlock(by); true }
                else k.nativeKeyEvent.repeatCount > 0
            }) {
                Text("◀", fontSize = 18.sp, color = Accent)
                Text(stringResource(R.string.shack_tv_block_item, d.block.label), fontSize = 17.sp, maxLines = 1,
                    modifier = Modifier.padding(horizontal = 12.dp).widthIn(min = 100.dp))
                Text("▶", fontSize = 18.sp, color = Accent)
            }
            if (u.dabScan == null) TvItem(onClick = { if (u.viewOnly) e.note(refused) else e.dabScan() }) {
                Text(stringResource(R.string.shack_dab_scan), fontSize = 17.sp, maxLines = 1)
            } else TvItem(onClick = { e.dabScanStop() }) {
                Text(stringResource(R.string.shack_dab_scan_stop), fontSize = 17.sp, maxLines = 1)
            }
        }
        if (antenna != null && u.dab == null) TvItem(onClick = onAntenna) {
            // The input's own short name ("Antenna C"): its label is in the panel OK opens, and the row has no room for both.
            Text(stringResource(R.string.shack_tv_antenna_item, antenna.current.removePrefix("Antenna ").ifEmpty { antennaNow(antenna) }), fontSize = 17.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
        TvItem(onClick = { e.mute(!u.muted) }) {
            Text(stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute), fontSize = 17.sp, color = if (u.muted) Amber else Color.Unspecified)
        }
        // Two short lines, as TvPolish: what Left and Right do where focus is now, then the channel keys.
        // It takes what the row leaves, so a long item squeezes the hint rather than pushing it off the screen.
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val lr = when {
                onStep && u.dab != null -> stringResource(R.string.shack_tv_block_keys)
                onStep -> stringResource(R.string.shack_tv_keys_step)
                onSurface && u.dab == null -> stringResource(R.string.shack_tv_keys_tune)
                else -> stringResource(R.string.shack_tv_keys_move)
            }
            KeyHint(lr)
            KeyHint(stringResource(R.string.shack_tv_keys_band))
        }
    }
}

/**
 * The TV's band picker (TvPolishBands): a panel sliding in from the right
 * over the dimmed screen - the filters, then the two sections as grids of
 * focusable tiles in frequency order, the dial's band ringed, and at the
 * foot a card saying what the focused tile is (its edges; an area's mode,
 * channel spacing and receive only). Focus opens on the lit band; OK goes
 * there (EngineSession.bandTo, as a phone's tile) and closes. BACK closes
 * it (the caller's). Everything beneath is inert while it is up.
 */
@Composable
internal fun TvBandsPanel(e: EngineSession, u: EngineUi, onClose: () -> Unit, modifier: Modifier, onLocal: (() -> Unit)? = null) {
    var filter by remember { mutableStateOf(BandFilter.ALL) }
    var looking by remember { mutableStateOf(u.currentBand) }
    val lit = remember { FocusRequester() }
    val firstFilter = remember { FocusRequester() }
    val refused = stringResource(R.string.shack_dab_view_only)
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { lit.requestFocus() }.onFailure { runCatching { firstFilter.requestFocus() } }
    }
    val tag = localeTag()
    Column(
        modifier.glass(RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp), 30.dp, Color(0xFF0E131A))
            .padding(horizontal = 30.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.shack_bands_title), fontSize = 24.sp, fontWeight = FontWeight.Medium, color = PolishText,
                modifier = Modifier.weight(1f))
            u.status?.rxRange?.let { r ->
                Text(stringResource(R.string.shack_bands_range, reachEnd(r.first, tag), reachEnd(r.second, tag)), fontSize = 13.sp,
                    color = PolishDim, maxLines = 1)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((f, word) in listOf(BandFilter.ALL to R.string.shack_bands_all, BandFilter.AMATEUR to R.string.shack_bands_amateur,
                BandFilter.LISTEN to R.string.shack_bands_listen)) {
                TvItem(onClick = { filter = f }, selected = f == filter, compact = true, focus = if (f == BandFilter.ALL) firstFilter else null) {
                    Text(stringResource(word), fontSize = 15.sp, color = if (f == filter) Accent else Color.Unspecified)
                }
            }
            Spacer(Modifier.weight(1f))
            // Local frequencies (TvPolishLocal): a screen of its own over the radio.
            if (onLocal != null) TvItem(onClick = onLocal, compact = true) {
                Text(stringResource(R.string.shack_local_title), fontSize = 15.sp, color = Accent)
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val current = u.currentBand
            BandGrid.sections(u.bandButtons, filter).forEach { (section, list) ->
                PolishLabel(stringResource(if (section == BandSection.LOW) R.string.shack_bands_low else R.string.shack_bands_high))
                list.chunked(TV_BAND_COLUMNS).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        line.forEach { b ->
                            TvBandTile(b, b == current, if (b == current) lit else null, Modifier.weight(1f), onFocus = { looking = b }) {
                                if (u.viewOnly) e.note(refused) else { e.bandTo(b); onClose() }
                            }
                        }
                        repeat(TV_BAND_COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        looking?.let { b -> BandCard(b) }
    }
}

/** What the focused tile is: its name, its edges, and for an area its mode, channels and receive only. */
@Composable
private fun BandCard(b: BandButton) {
    val (lo, hi, unit) = rangeParts(b.band.loHz, b.band.hiHz, localeTag())
    val parts = buildList {
        add(stringResource(R.string.shack_band_range, lo, hi, unit))
        b.area?.mode?.let { add(modeWord(it)) }
        // DAB's "channels" are its blocks, which the step does not describe.
        b.area?.takeIf { it.mode != null }?.let { add(stringResource(R.string.shack_band_channels, stepText(it.stepHz))) }
        if (b.rxTag) add(stringResource(R.string.shack_band_rx_only))
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.08f))
        .border(1.dp, Accent.copy(alpha = 0.25f), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(bandLabel(b), fontSize = 17.sp, fontWeight = FontWeight.Medium, color = PolishText)
        Text(parts.joinToString(" · "), fontSize = 14.sp, color = Color(0xFFA7B3C0), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val TV_BAND_COLUMNS = 6

@Composable
private fun TvBandTile(b: BandButton, on: Boolean, focus: FocusRequester?, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    TvItem(onClick = onClick, selected = on, compact = true, focus = focus,
        modifier = modifier.height(56.dp).onFocusChanged { if (it.isFocused) onFocus() }) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bandLabel(b), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                color = if (on) Accent else Color.Unspecified)
            if (b.rxTag) RxTag(9.sp)
        }
    }
}

/** One line of the row's key hint: the keys light, what they do grey, split at the first space after the keys. */
@Composable
private fun KeyHint(text: String) {
    // The key part is everything up to the last of the key glyphs or the "CH ±" pair; the rest is what it does.
    val cut = text.indexOfLast { it == '▶' || it == '±' }.takeIf { it >= 0 }?.plus(1) ?: 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cut > 0) Text(text.substring(0, cut), fontSize = 12.sp, color = Color(0xFFD6DEE6), maxLines = 1,
            modifier = Modifier.border(1.dp, Color(0xFF3A4654), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp))
        Text(text.substring(cut).trim(), fontSize = 13.sp, color = PolishDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
