package com.openhamshack.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import com.openhamshack.remote.shack.DabBlocks
import com.openhamshack.remote.shack.EngineUi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.DabService
import com.openhamshack.remote.shack.DabUi
import com.openhamshack.remote.shack.EngineSession

// DAB's station list (RADIO-LISTEN-CONTRACT §3). Since the overhaul
// (Brian's Dab mockup, 23/10/2026) it takes the scope's place on every
// device rather than a sheet or side panel covering half the waterfall, and
// the readout names the station playing. The list is the decoder session's
// (Dab.kt); a tap or OK on a service the
// server can play chooses it, and one it cannot (a dab+ service without
// faad2) is shown dimmed with the server's why and is not a button at all.

/** The line under the title: the server's refusal, still acquiring, the multiplex's name, or that it has no services. */
@Composable
private fun dabStatus(d: DabUi): Pair<String, Color> = when {
    d.problem.isNotEmpty() -> d.problem to Amber
    d.session == null -> stringResource(R.string.shack_dab_starting) to Dim
    d.acquiring -> stringResource(R.string.shack_dab_acquiring) to Dim
    d.services.isEmpty() -> stringResource(R.string.shack_dab_none) to Dim
    else -> stringResource(R.string.shack_dab_ensemble, d.ensemble.ifEmpty { d.block.label }) to Dim
}

@Composable
private fun dabDetail(s: DabService): String =
    s.bitrate?.let { stringResource(R.string.shack_dab_service_rate, s.kind.uppercase(), it) } ?: s.kind.uppercase()

@Composable
private fun dabTitle(d: DabUi): String = stringResource(R.string.shack_dab_title, d.block.label, dialText(d.block.centreHz))

/** The playing service, if the list has it. */
private fun playing(d: DabUi): DabService? = d.services.firstOrNull { it.sid == d.playing }

/**
 * DAB's readout (Dab): "Now playing" and the station's name big where the
 * mode and dial would be, the DAB chip and the block's (▾ opens the blocks),
 * RX, and the multiplex and bitrate under them.
 */
@Composable
internal fun DabReadout(d: DabUi, onBlocks: () -> Unit, modifier: Modifier = Modifier) {
    val now = playing(d)
    Column(modifier.clip(RoundedCornerShape(20.dp)).background(RadioCard).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_dab_now), fontSize = 12.sp, color = Dim)
        Text(now?.label ?: stringResource(R.string.shack_dab_pick), fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, color = if (now == null) Dim else Color.White, modifier = Modifier.testTag("dabNow"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            ReadoutChip(true, null, "dabMode") {
                Text(stringResource(R.string.shack_area_dab), color = OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            ReadoutChip(false, onBlocks, "dabBlock") {
                Text(stringResource(R.string.shack_dab_block_chip, d.block.label, edgeText(d.block.centreHz)), color = Color.White, fontSize = 14.sp,
                    maxLines = 1)
            }
            RxTag()
        }
        val (line, colour) = dabStatus(d)
        Text(listOfNotNull(d.ensemble.ifEmpty { null }, now?.let { dabDetail(it) }).joinToString(" · ").ifEmpty { line },
            fontSize = 13.sp, color = if (d.ensemble.isEmpty()) colour else Dim, maxLines = 2)
    }
}

/**
 * DAB's station list where the scope was (Dab): "Stations on 12B" and Scan
 * all blocks, then the services - the playing one lit, one the server
 * cannot play dimmed with its why and not a button - and, after a scan,
 * the blocks it found, a tap opening one. While a scan runs its progress
 * and Stop take the heading's place.
 */
@Composable
internal fun DabStations(e: EngineSession, u: EngineUi, d: DabUi, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val scan = u.dabScan
        if (scan != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.shack_dab_scanning, scan.block.label, scan.at + 1, scan.total), fontSize = 14.sp,
                    modifier = Modifier.weight(1f).testTag("dabScanning"))
                TextButton(onClick = { e.dabScanStop() }, modifier = Modifier.testTag("dabScanStop")) { Text(stringResource(R.string.shack_dab_scan_stop)) }
            }
            LinearProgressIndicator(progress = { scan.fraction }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = RadioTrack)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallHeading(stringResource(R.string.shack_dab_stations_on, d.block.label), Modifier.weight(1f))
                val shape = RoundedCornerShape(18.dp)
                Box(Modifier.height(36.dp).clip(shape).border(1.dp, RadioLine, shape).clickable(enabled = !u.viewOnly) { e.dabScan() }
                    .padding(horizontal = 14.dp).testTag("dabScan"), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.shack_dab_scan), color = if (u.viewOnly) Dim else Accent, fontSize = 14.sp)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (d.services.isEmpty()) item {
                val (line, colour) = dabStatus(d)
                Text(line, color = colour, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
            }
            items(d.services.size) { i -> DabRow(e, d, d.services[i], u.viewOnly) }
            // What the scan has found so far while it runs, then what it found.
            val found = scan?.found ?: u.dabFound
            if (found.isNotEmpty()) {
                item { SmallHeading(stringResource(R.string.shack_dab_found_title), Modifier.padding(top = 10.dp)) }
                items(found.size) { i ->
                    val f = found[i]
                    val on = f.block == d.block
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (on) Color(0xFF13303D) else RadioCard)
                        .clickable(enabled = !u.viewOnly && !on && scan == null) { e.openDab(f.block) }.padding(horizontal = 14.dp, vertical = 12.dp)
                        .testTag("dabFound-${f.block.label}")) {
                        Text(stringResource(R.string.shack_dab_found_row, f.block.label, f.ensemble, foundServices(f.services)),
                            fontSize = 15.sp, color = if (on) Accent else Color.White)
                    }
                }
            }
            if (u.viewOnly) item { Text(stringResource(R.string.shack_dab_view_only), color = Amber, fontSize = 13.sp) }
        }
    }
}

/** One service: name and kind/bitrate, lit while it plays, dimmed with the server's why when it cannot be played. */
@Composable
private fun DabRow(e: EngineSession, d: DabUi, s: DabService, viewOnly: Boolean) {
    val on = s.sid == d.playing
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(if (on) Color(0xFF13303D) else RadioCard)
            .border(1.dp, if (on) Accent else Color.Transparent, shape)
            .then(if (s.playable && !viewOnly) Modifier.clickable { e.dabPlay(s.sid) } else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp).testTag("dab-${s.sid}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (s.playable) Color.White else Dim)
            Text(if (s.playable) dabDetail(s) else s.why, fontSize = 12.sp, color = Dim)
        }
        if (on) LevelBars()
    }
}

/** The mockup's four bars beside the playing station. */
@Composable
private fun LevelBars() {
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val k = size.width / 24f
        for ((x, y0, y1) in listOf(Triple(4f, 10f, 14f), Triple(9f, 6f, 18f), Triple(14f, 8f, 16f), Triple(19f, 11f, 13f))) {
            drawLine(Accent, Offset(x * k, y0 * k), Offset(x * k, y1 * k), strokeWidth = 2 * k, cap = StrokeCap.Round)
        }
    }
}

/** The blocks sheet, from the block chip: every Band III block, the open one lit, the ones a scan found in the accent. */
@Composable
internal fun DabBlocksSheet(e: EngineSession, u: EngineUi, d: DabUi, onDone: () -> Unit) {
    val found = u.dabFound.map { it.block }.toSet()
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_dab_blocks_title), fontWeight = FontWeight.Medium, fontSize = 20.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DabBlocks.ALL.forEach { b ->
                FilterChip(selected = b == d.block, onClick = { e.openDab(b); onDone() }, enabled = !u.viewOnly,
                    label = { Text(b.label, color = if (b in found && b != d.block) Accent else Color.Unspecified) },
                    modifier = Modifier.testTag("dabBlock-${b.label}"))
            }
        }
        Text(stringResource(R.string.shack_dab_rx_only), color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * DAB on a TV (TvPolishDab), in the scope's place: the Now playing card at
 * the left - the station's initials badge in its own colour, its name, the
 * kind and bitrate - and the stations on this block as a grid of tiles at
 * the right, each with its badge; the playing one lit, one the server
 * cannot play dimmed with its why (still focusable, so the why can be read,
 * but OK does nothing). While a scan runs its progress heads the grid;
 * after one, the blocks it found follow the stations, OK opening one. The
 * block itself and Scan are in the bottom row (TvRadioRow). Focus comes in
 * on the playing station, else the first.
 */
@Composable
internal fun TvDabScreen(e: EngineSession, u: EngineUi, d: DabUi, first: FocusRequester, refused: String, modifier: Modifier) {
    val viewOnly = u.viewOnly
    val now = d.services.firstOrNull { it.sid == d.playing }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.width(300.dp).fillMaxHeight().glass(RoundedCornerShape(22.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (now != null) StationBadgeBox(now.label, now.sid, 104.dp, 26.dp, 46.sp)
            PolishLabel(stringResource(R.string.shack_dab_now_heading))
            Text(now?.label ?: stringResource(R.string.shack_dab_pick), fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 32.sp,
                color = if (now == null) PolishDim else PolishText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val (line, colour) = dabStatus(d)
            Text(d.ensemble.ifEmpty { line }, fontSize = 15.sp, color = if (d.ensemble.isEmpty()) colour else Color(0xFFA7B3C0), maxLines = 2)
            Spacer(Modifier.weight(1f))
            if (now != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PolishChip(now.kind.uppercase())
                now.bitrate?.let { PolishChip(stringResource(R.string.shack_dab_bitrate, it)) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val scan = u.dabScan
            if (scan != null) {
                Text(stringResource(R.string.shack_dab_scanning, scan.block.label, scan.at + 1, scan.total), fontSize = 16.sp, color = PolishDim)
                LinearProgressIndicator(progress = { scan.fraction }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = RadioTrack)
            } else PolishLabel(stringResource(R.string.shack_dab_stations_on, d.block.label))
            if (d.services.isEmpty()) {
                val (line, colour) = dabStatus(d)
                Text(line, fontSize = 16.sp, color = colour)
            }
            val lead = d.services.indexOfFirst { it.sid == d.playing }.coerceAtLeast(0)
            d.services.withIndex().chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { (i, s) ->
                        val on = s.sid == d.playing
                        TvItem(onClick = { if (viewOnly) e.note(refused) else e.dabPlay(s.sid) }, selected = on, compact = true,
                            focus = if (i == lead) first else null, modifier = Modifier.weight(1f).height(60.dp)) {
                            Box(Modifier.alpha(if (s.playable) 1f else 0.55f)) { StationBadgeBox(s.label, s.sid, 40.dp, 11.dp, 16.sp) }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(s.label, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (s.playable) PolishText else PolishDim)
                                Text(if (s.playable) dabDetail(s) else s.why, fontSize = 12.sp, color = PolishDim, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            if (on) LevelBars()
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            val found = scan?.found ?: u.dabFound
            if (found.isNotEmpty()) {
                PolishLabel(stringResource(R.string.shack_dab_found_title), modifier = Modifier.padding(top = 6.dp))
                found.forEach { f ->
                    TvItem(onClick = { if (viewOnly) e.note(refused) else if (scan == null) e.openDab(f.block) }, selected = f.block == d.block,
                        compact = true, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.shack_dab_found_row, f.block.label, f.ensemble, foundServices(f.services)), fontSize = 16.sp)
                    }
                }
            }
            Text(stringResource(R.string.shack_dab_rx_only), fontSize = 13.sp, color = PolishQuiet)
        }
    }
}
