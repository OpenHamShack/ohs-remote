package com.openhamshack.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.DabList
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.EngineUi
import com.openhamshack.remote.shack.FmSort
import com.openhamshack.remote.shack.FmStation
import com.openhamshack.remote.shack.ListHeader
import com.openhamshack.remote.shack.Rds
import com.openhamshack.remote.shack.Stations

// Radio's station lists and RDS on every device (Brian approved TvPolishFm,
// 23/10/2026): the FM picker - genre chips, A-Z or by frequency, tiles with
// a badge in each programme's own colour, OK tuning to its best frequency in
// wide FM - from the list Radio's weekly scan made; the DAB picker from
// Radio's list, every service grouped by its ensemble; "Scan now" with
// Radio's own progress; and the station's RDS in the readout. Everything
// here is Radio's (openhamshack-nb SERVER-API "Station lists"); Remote's own
// block-by-block DAB scan is only the fallback (Stations.dabSource).

/** The list's header: when it was made and the next attempt, or the server's sentence when it is never, scanning, busy, aborted... */
@Composable
internal fun stationsHeader(h: ListHeader): String {
    val made = h.scannedAt
    return when {
        h.showsDates && made != null -> h.nextDue?.let { stringResource(R.string.shack_stations_from, scanWhen(made), scanWhen(it)) }
            ?: stringResource(R.string.shack_stations_from_only, scanWhen(made))
        else -> h.statusText
    }
}

/** A station's name as a tile shows it: its RDS name, else its frequency. */
@Composable
internal fun fmName(s: FmStation): String = s.name ?: stringResource(R.string.shack_fm_no_rds, channelText(s.best.hz))

/** A tile's second line: the frequency and the programme type, when there is one. */
@Composable
internal fun fmSub(s: FmStation): String = when {
    // Named by its frequency already: the second line says why, rather than the frequency again.
    s.name == null -> stringResource(R.string.shack_fm_no_name)
    s.ptyName != null -> stringResource(R.string.shack_fm_tile_sub, channelText(s.best.hz), s.ptyName)
    else -> stringResource(R.string.shack_fm_tile_freq, channelText(s.best.hz))
}

/**
 * "Scan now", or the scan under way in Radio's own words with how far it has
 * got and the warning that any change stops it (Radio puts everything back).
 * [band] is what this picker scans.
 */
@Composable
internal fun ScanLine(e: EngineSession, u: EngineUi, band: String, big: Boolean) {
    val scan = u.state.bandScan
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (scan != null) {
            Text(scan.text, fontSize = if (big) 16.sp else 14.sp, color = if (big) PolishText else Color.White, modifier = Modifier.testTag("bandScan"))
            LinearProgressIndicator(progress = { scan.percent / 100f }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = RadioTrack)
            Text(stringResource(R.string.shack_scan_warning), fontSize = if (big) 13.sp else 12.sp, color = Amber)
        } else if (!big) {
            FilledTonalButton(onClick = { e.scanNow(band) }, enabled = !u.viewOnly, modifier = Modifier.testTag("scanNow")) {
                Text(stringResource(R.string.shack_scan_now))
            }
        }
        if (u.stationsProblem.isNotEmpty()) Text(u.stationsProblem, fontSize = 13.sp, color = Amber)
    }
}

/** The genre chips and the order, on a phone. */
@Composable
private fun FmFilters(genres: List<String>, genre: String?, sort: FmSort, onGenre: (String?) -> Unit, onSort: (FmSort) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = genre == null, onClick = { onGenre(null) }, label = { Text(stringResource(R.string.shack_genre_all)) })
        genres.forEach { g -> FilterChip(selected = g == genre, onClick = { onGenre(g) }, label = { Text(g) }) }
        Spacer(Modifier.width(12.dp))
        FilterChip(selected = sort == FmSort.AZ, onClick = { onSort(FmSort.AZ) }, label = { Text(stringResource(R.string.shack_sort_az)) },
            modifier = Modifier.testTag("sortAz"))
        FilterChip(selected = sort == FmSort.MHZ, onClick = { onSort(FmSort.MHZ) }, label = { Text(stringResource(R.string.shack_sort_mhz)) },
            modifier = Modifier.testTag("sortMhz"))
    }
}

/**
 * The FM picker on a phone or tablet: a sheet over the radio - the list's
 * header, Scan now (or its progress), the genres and the order, and a tile
 * per station; a tap tunes to its best frequency in wide FM and closes it.
 */
@Composable
internal fun FmPickerSheet(e: EngineSession, u: EngineUi, onDone: () -> Unit) {
    LaunchedEffect(Unit) { e.loadFm() }
    var genre by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(FmSort.AZ) }
    val l = u.fmList
    val on = Stations.playing(l, u.dialHz, u.state.rds?.pi)
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_fm_title), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        l?.let { Text(stationsHeader(it.header), fontSize = 13.sp, color = Dim) }
        ScanLine(e, u, Stations.FM, big = false)
        if (l != null) {
            FmFilters(Stations.genres(l), genre, sort, { genre = it }, { sort = it })
            val tiles = Stations.tiles(l, genre, sort)
            if (tiles.isEmpty() && l.header.scannedAt != null) Text(stringResource(R.string.shack_stations_empty), color = Dim)
            tiles.forEach { s ->
                val lit = s == on
                val shape = RoundedCornerShape(14.dp)
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(if (lit) Color(0xFF13303D) else RadioCard)
                    .border(1.dp, if (lit) Accent else Color.Transparent, shape)
                    .clickable(enabled = !u.viewOnly) { e.tuneFm(s); onDone() }.padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("fm-${s.badgeKey}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StationBadgeBox(s.name ?: channelText(s.best.hz), s.badgeKey, 40.dp, 11.dp, 15.sp, text = s.badgeText)
                    Column(Modifier.weight(1f)) {
                        Text(fmName(s), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmSub(s), fontSize = 12.sp, color = Dim, maxLines = 1)
                        s.rt?.let { Text(it, fontSize = 12.sp, color = Color(0xFFA7B3C0), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    if (s.best.stereo) StereoBadge(small = true)
                }
            }
        }
    }
}

/** The small Stereo badge: the accent filled, as TvPolishFm's. */
@Composable
internal fun StereoBadge(small: Boolean) {
    Text(stringResource(R.string.shack_stereo), fontSize = if (small) 11.sp else 13.sp, fontWeight = FontWeight.Medium, color = OnAccent, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Accent).padding(horizontal = if (small) 7.dp else 10.dp, vertical = 2.dp))
}

/** RDS's traffic marker (Rds.traffic): "TA" while this station's traffic news is on, "TP" for a station that carries it. */
@Composable
internal fun TrafficMarker(rds: Rds) {
    val t = rds.traffic ?: return
    val on = t == com.openhamshack.remote.shack.Traffic.TA
    Text(stringResource(if (on) R.string.shack_ta else R.string.shack_tp), fontSize = 11.sp, fontWeight = FontWeight.Bold,
        color = if (on) Amber else Dim, maxLines = 1,
        modifier = Modifier.border(1.dp, if (on) Amber else RadioLine, RoundedCornerShape(5.dp)).padding(horizontal = 5.dp))
}

/**
 * The readout's RDS lines on a phone or tablet, under the station's name
 * (which takes the frequency's place): the frequency, the RadioText as the
 * Now playing line, and the programme type, Stereo and the traffic marker.
 */
@Composable
internal fun RdsLines(u: EngineUi, rds: Rds) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.shack_fm_tile_freq, channelText(u.dialHz ?: 0.0)), fontSize = 15.sp, color = Dim, maxLines = 1)
            rds.ptyName?.let { Text(it, fontSize = 14.sp, color = Dim, maxLines = 1) }
            if (u.stereo) StereoBadge(small = true)
            TrafficMarker(rds)
        }
        rds.rt?.let { Text(it, fontSize = 14.sp, color = Color(0xFFA7B3C0), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("rdsRt")) }
    }
}

/**
 * The DAB picker from Radio's list (Stations.dabSource SERVER): every
 * service, grouped under its ensemble and block; a press starts the session
 * there with that service (or chooses it live on the block already
 * playing). The playing one is lit.
 */
@Composable
internal fun DabServerList(e: EngineSession, u: EngineUi, l: DabList, big: Boolean) {
    val d = u.dab
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stationsHeader(l.header), fontSize = if (big) 14.sp else 13.sp, color = if (big) PolishDim else Dim)
        if (l.ensembles.isEmpty() && l.header.scannedAt != null) Text(stringResource(R.string.shack_stations_empty), color = Dim)
        l.ensembles.forEach { en ->
            val heading = stringResource(R.string.shack_dab_found_row, en.block, en.label, foundServices(en.services.size))
            if (big) PolishLabel(heading) else SmallHeading(heading)
            // Two a row on a TV, as TvPolishDab's tiles; one a row on a phone.
            en.services.chunked(if (big) 2 else 1).forEach { line -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { line.forEach { s ->
                val lit = d?.block?.label == en.block && d.playing == s.sid
                // The live session on this block knows what cannot play here (a DAB+ station without faad2): dimmed with its words.
                val why = d?.takeIf { it.block.label == en.block }?.services?.firstOrNull { it.sid == s.sid }?.takeIf { !it.playable }?.why
                val detail = s.bitrate?.let { stringResource(R.string.shack_dab_service_rate, s.kind.uppercase(), it) } ?: s.kind.uppercase()
                if (big) TvItem(onClick = { e.playListed(en.block, s.sid) }, selected = lit, compact = true, modifier = Modifier.weight(1f).height(60.dp)) {
                    StationBadgeBox(s.label, s.sid, 36.dp, 10.dp, 14.sp)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(s.label, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (why != null) PolishDim else Color.Unspecified)
                        Text(why ?: detail, fontSize = 12.sp, color = PolishDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else Row(Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (lit) Color(0xFF13303D) else RadioCard).clickable(enabled = !u.viewOnly) { e.playListed(en.block, s.sid) }
                    .padding(horizontal = 12.dp, vertical = 6.dp).testTag("dabListed-${s.sid}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StationBadgeBox(s.label, s.sid, 34.dp, 10.dp, 13.sp)
                    Column(Modifier.weight(1f)) {
                        Text(s.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (lit) Accent else if (why != null) Dim else Color.White, maxLines = 1)
                        Text(why ?: detail, fontSize = 12.sp, color = Dim, maxLines = 2)
                    }
                }
            }
            if (big && line.size == 1) Spacer(Modifier.weight(1f)) } }
        }
    }
}

// --- TV ---------------------------------------------------------------------

/**
 * The TV's FM picker (TvPolishFm), over the radio: the heading line with the
 * list's header; at the left the Now playing card - the station's badge,
 * its RDS name, RadioText, and its frequency, programme type, Stereo and S
 * reading as chips; at the right the genres and the order, then the tiles,
 * two a row. OK on a tile tunes to it and returns to the radio. The row at
 * the foot: Bands (back), Tune by frequency, Scan now, Mute. BACK closes it.
 */
@Composable
internal fun TvFmScreen(e: EngineSession, u: EngineUi, first: FocusRequester, onClose: () -> Unit, onType: () -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) { e.loadFm(); delay(150); runCatching { first.requestFocus() } }
    var genre by remember { mutableStateOf<String?>(null) }
    var sort by remember { mutableStateOf(FmSort.AZ) }
    val l = u.fmList
    val rds = u.state.rds
    val on = Stations.playing(l, u.dialHz, rds?.pi)
    Column(modifier.polishVignette().padding(horizontal = TvSafeH, vertical = TvSafeV), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.shack_fm_heading), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Accent)
            Box(Modifier.width(4.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A4654)))
            Text(l?.let { stationsHeader(it.header) }.orEmpty(), fontSize = 15.sp, color = Color(0xFFA7B3C0), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            NowPlayingFm(u, rds, on, Modifier.width(300.dp).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScanLine(e, u, Stations.FM, big = true)
                if (l != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvItem(onClick = { genre = null }, selected = genre == null, compact = true, focus = first) {
                            Text(stringResource(R.string.shack_genre_all), fontSize = 14.sp)
                        }
                        Stations.genres(l).take(3).forEach { g ->
                            TvItem(onClick = { genre = g }, selected = g == genre, compact = true) { Text(g, fontSize = 14.sp, maxLines = 1) }
                        }
                        Spacer(Modifier.weight(1f))
                        TvItem(onClick = { sort = if (sort == FmSort.AZ) FmSort.MHZ else FmSort.AZ }, compact = true) {
                            Text(stringResource(R.string.shack_sort_az), fontSize = 14.sp, color = if (sort == FmSort.AZ) Accent else PolishDim, maxLines = 1, softWrap = false)
                            Text(" · ", fontSize = 14.sp, color = PolishDim, maxLines = 1, softWrap = false)
                            Text(stringResource(R.string.shack_sort_mhz), fontSize = 14.sp, color = if (sort == FmSort.MHZ) Accent else PolishDim, maxLines = 1, softWrap = false)
                        }
                    }
                    val tiles = Stations.tiles(l, genre, sort)
                    if (tiles.isEmpty() && l.header.scannedAt != null) Text(stringResource(R.string.shack_stations_empty), fontSize = 16.sp, color = PolishDim)
                    tiles.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { s ->
                                TvItem(onClick = { e.tuneFm(s); onClose() }, selected = s == on, compact = true, modifier = Modifier.weight(1f).height(58.dp)) {
                                    StationBadgeBox(s.name ?: channelText(s.best.hz), s.badgeKey, 38.dp, 11.dp, 15.sp, text = s.badgeText)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(fmName(s), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(fmSub(s), fontSize = 12.sp, color = PolishDim, maxLines = 1)
                                    }
                                    if (s == on) LevelBarsTv()
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvItem(onClick = onClose) { Text(stringResource(R.string.shack_tv_bands), fontSize = 17.sp) }
            TvItem(onClick = onType) { Text(stringResource(R.string.shack_tune_by_freq), fontSize = 17.sp) }
            if (u.state.bandScan == null) TvItem(onClick = { e.scanNow(Stations.FM) }) { Text(stringResource(R.string.shack_scan_now), fontSize = 17.sp) }
            TvItem(onClick = { e.mute(!u.muted) }) {
                Text(stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute), fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.shack_local_keys_ok), fontSize = 12.sp, color = Color(0xFFD6DEE6),
                    modifier = Modifier.border(1.dp, Color(0xFF3A4654), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp))
                Text(stringResource(R.string.shack_fm_hint), fontSize = 13.sp, color = PolishDim)
            }
        }
    }
}

/** The FM picker's Now playing card: the station on the air by its RDS (or the list's entry for the dial). */
@Composable
private fun NowPlayingFm(u: EngineUi, rds: Rds?, listed: FmStation?, modifier: Modifier) {
    val name = rds?.name ?: listed?.name
    val key = rds?.pi ?: listed?.badgeKey ?: Math.round(u.dialHz ?: 0.0).toString()
    Column(modifier.glass(RoundedCornerShape(22.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StationBadgeBox(name ?: channelText(u.dialHz ?: 0.0), key, 96.dp, 24.dp, 40.sp,
            text = if (name == null) ((u.dialHz ?: 0.0) / 1e6).toInt().toString() else null)
        PolishLabel(stringResource(R.string.shack_rds_heading))
        Text(name ?: stringResource(R.string.shack_fm_no_rds, channelText(u.dialHz ?: 0.0)), fontSize = 30.sp, fontWeight = FontWeight.Medium,
            color = PolishText, maxLines = 2, overflow = TextOverflow.Ellipsis)
        (rds?.rt ?: listed?.rt)?.let { Text(it, fontSize = 15.sp, color = Color(0xFFA7B3C0), lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.weight(1f))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PolishChip(stringResource(R.string.shack_fm_tile_freq, channelText(u.dialHz ?: 0.0)))
            (rds?.ptyName ?: listed?.ptyName)?.let { PolishChip(it) }
            if (u.stereo) StereoBadge(small = false)
            u.sMeter.reading?.let { PolishChip(it) }
            rds?.let { TrafficMarker(it) }
        }
    }
}

/** The playing tile's bars. */
@Composable
private fun LevelBarsTv(size: Dp = 20.dp, colour: Color = Accent) {
    androidx.compose.foundation.Canvas(Modifier.width(size).height(size)) {
        val k = this.size.width / 24f
        for ((x, y0, y1) in listOf(Triple(6f, 10f, 18f), Triple(12f, 4f, 18f), Triple(18f, 8f, 18f))) {
            drawLine(colour, androidx.compose.ui.geometry.Offset(x * k, y0 * k), androidx.compose.ui.geometry.Offset(x * k, y1 * k),
                strokeWidth = 3 * k, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}
