package com.openhamshack.remote.ui

import androidx.compose.foundation.background
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
 * The readout (Tv's top): "Radio · GM4BRI" over the frequency, or over the
 * station's name in DAB; at the right the unusual states, the mode ("Wide
 * FM" for wfm) and the band or area with its RX tag, the S-meter under them.
 */
@Composable
internal fun TvRadioReadout(e: EngineSession, u: EngineUi) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.shack_header_box_rx, e.app.name, e.controllerName), fontSize = 16.sp, color = Dim, maxLines = 1)
            val dab = u.dab
            if (dab != null) {
                val now = dab.services.firstOrNull { it.sid == dab.playing }
                Text(now?.label ?: stringResource(R.string.shack_dab_pick), fontSize = 40.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (now == null) Dim else Color.White, modifier = Modifier.widthIn(max = 520.dp))
            } else {
                val (number, unit) = dialParts(u.dialHz)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(number, fontSize = 54.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1,
                        color = if (u.pendingDialHz != null) Accent else Color.White)
                    Text(unit, fontSize = 20.sp, color = Dim, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
        Column(Modifier.weight(1f).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                val small = 16.sp
                if (!u.connected) Text(stringResource(if (u.state.dialHz == null) R.string.shack_connecting else R.string.shack_reconnecting),
                    color = Amber, fontSize = small, maxLines = 1)
                if (u.status?.onAir == true) Text(stringResource(R.string.shack_on_air_short), color = OnAirRed, fontSize = small, maxLines = 1)
                if (u.status?.simulator == true) Text(stringResource(R.string.shack_simulator), color = Amber, fontSize = small, maxLines = 1)
                if (u.muted) Text(stringResource(R.string.shack_muted), color = Dim, fontSize = small, maxLines = 1)
                else if (u.audioRate <= 0 && u.connected) Text(stringResource(R.string.shack_no_audio), color = Dim, fontSize = small, maxLines = 1)
                val dab = u.dab
                val modeText = when {
                    dab != null -> stringResource(R.string.shack_area_dab)
                    u.state.mode == "wfm" -> stringResource(R.string.shack_mode_wfm)
                    else -> modeWord(u.state.mode)
                }
                Text(modeText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Accent, maxLines = 1)
                val place = if (dab != null) stringResource(R.string.shack_dab_block, dab.block.label) else u.currentBand?.let { bandChipText(u, it) }
                if (place != null) {
                    Text("·", fontSize = 18.sp, color = Dim)
                    Text(place, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 280.dp))
                }
                if (dab != null || u.currentBand?.rxTag == true) RxTag(13.sp)
            }
            SMeterLine(u, barHeight = 10.dp, textSize = 16.sp, letterSize = 16.sp)
            u.toast?.let { Text(it, color = Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth()) }
        }
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
) {
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
            TvItem(onClick = { e.step(1) }, modifier = Modifier.onPreviewKeyEvent { k ->
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
                Column(Modifier.padding(horizontal = 12.dp).widthIn(min = 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.shack_step_label), fontSize = 12.sp, color = Dim)
                    Text(stepText(u.stepHz), fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
                Text("▶", fontSize = 18.sp, color = Accent)
            }
            if (u.offered.isNotEmpty()) TvItem(onClick = onMode) {
                Text(stringResource(R.string.shack_tv_mode_item, modeWord(u.state.mode)), fontSize = 17.sp, maxLines = 1)
            }
        }
        if (antenna != null) TvItem(onClick = onAntenna) {
            // The input's own short name ("Antenna C"): its label is in the panel OK opens, and the row has no room for both.
            Text(stringResource(R.string.shack_tv_antenna_item, antenna.current.ifEmpty { antennaNow(antenna) }), fontSize = 17.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
        TvItem(onClick = { e.mute(!u.muted) }) {
            Text(stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute), fontSize = 17.sp, color = if (u.muted) Amber else Color.Unspecified)
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            if (u.dab == null) Text(stringResource(R.string.shack_tv_hint_step), fontSize = 13.sp, color = Dim, maxLines = 1)
            Text(stringResource(R.string.shack_tv_hint_tune), fontSize = 13.sp, color = Dim, maxLines = 1)
        }
    }
}

/**
 * The TV's band picker (Bands on Tv), over the screen: the filters, then
 * the two sections as grids of focusable tiles in frequency order, the dial's
 * band ringed. Focus opens on the lit band; the D-pad moves between tiles;
 * OK goes there (EngineSession.bandTo, as a phone's tile) and closes. BACK
 * closes it (the caller's). Everything beneath is inert while it is up.
 */
@Composable
internal fun TvBandsPanel(e: EngineSession, u: EngineUi, onClose: () -> Unit, modifier: Modifier) {
    var filter by remember { mutableStateOf(BandFilter.ALL) }
    val lit = remember { FocusRequester() }
    val firstFilter = remember { FocusRequester() }
    val refused = stringResource(R.string.shack_dab_view_only)
    LaunchedEffect(Unit) {
        delay(80)
        runCatching { lit.requestFocus() }.onFailure { runCatching { firstFilter.requestFocus() } }
    }
    Column(
        modifier.background(Color(0xFF101418), RoundedCornerShape(16.dp)).padding(horizontal = 24.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.shack_bands_title), fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((f, word) in listOf(BandFilter.ALL to R.string.shack_bands_all, BandFilter.AMATEUR to R.string.shack_bands_amateur,
                BandFilter.LISTEN to R.string.shack_bands_listen)) {
                TvItem(onClick = { filter = f }, selected = f == filter, compact = true, focus = if (f == BandFilter.ALL) firstFilter else null) {
                    Text(stringResource(word), fontSize = 16.sp)
                }
            }
        }
        val current = u.currentBand
        BandGrid.sections(u.bandButtons, filter).forEach { (section, list) ->
            SmallHeading(stringResource(if (section == BandSection.LOW) R.string.shack_bands_low else R.string.shack_bands_high))
            list.chunked(TV_BAND_COLUMNS).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEach { b -> TvBandTile(b, b == current, if (b == current) lit else null, Modifier.weight(1f)) {
                        if (u.viewOnly) e.note(refused) else { e.bandTo(b); onClose() }
                    } }
                    repeat(TV_BAND_COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private const val TV_BAND_COLUMNS = 8

@Composable
private fun TvBandTile(b: BandButton, on: Boolean, focus: FocusRequester?, modifier: Modifier, onClick: () -> Unit) {
    TvItem(onClick = onClick, selected = on, compact = true, focus = focus, modifier = modifier) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(bandLabel(b), fontSize = 16.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                color = if (on) Accent else Color.Unspecified)
            if (b.rxTag) RxTag(10.sp)
        }
    }
}
