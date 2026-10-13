package com.openhamshack.kind.stationflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.core.tv.Amber
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.LockGreen
import com.openhamshack.core.tv.TvItem
import com.openhamshack.core.tv.TvSafeH
import com.openhamshack.core.tv.TvSafeV
import com.openhamshack.i18n.ShackFormat
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.api.Heard
import java.time.Instant
import java.time.ZoneOffset
import com.openhamshack.core.decode.DecodedStrip
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.radio.DvNow
import com.openhamshack.kind.stationflow.radio.DvRow
import com.openhamshack.kind.stationflow.radio.showsDv

/**
 * The decode window's tabs. CW and D-STAR are there only from a module
 * that reports them (state `cw`, `dv`/`dv_heard`): Brian (10 October 2026)
 * asked for the CW text to live here rather than along the bottom of the
 * face, and the D-STAR caller with it.
 */
internal enum class HeardTab(val title: Int) {
    Decodes(R.string.heard_tab_decodes), Stations(R.string.heard_tab_stations), Aprs(R.string.heard_tab_aprs),
    Cw(R.string.heard_tab_cw), Dv(R.string.heard_tab_dv),
}

/** The tabs this module's radio has. */
internal fun heardTabs(st: RadioState?): List<HeardTab> = HeardTab.entries.filter {
    when (it) {
        HeardTab.Cw -> st?.cw != null
        HeardTab.Dv -> st?.dv != null
        else -> true
    }
}

/** The tab the window opens on: what the radio is doing - CW in CW or CW-R, D-STAR in DV - else the decodes. */
internal fun openingTab(st: RadioState?): HeardTab = when {
    st?.cw?.active == true -> HeardTab.Cw
    showsDv(st) -> HeardTab.Dv
    else -> HeardTab.Decodes
}

/**
 * Everything heard, as big rows: the decoder's transcript, the stations it
 * makes of them, APRS, and - from a module that reads them - the CW text
 * and the D-STAR callers. OK on a decode, station or APRS row tunes to it:
 * for a decode, the dial that heard it in USB-D, which puts the station
 * back at the audio offset it was decoded at; for APRS, its channel in FM.
 * Up from the first row reaches the tabs.
 */
@Composable
internal fun HeardScreen(s: StationFlowSession, onClose: () -> Unit) {
    val decodes by s.decodes.collectAsState()
    val spots by s.spots.collectAsState()
    val aprs by s.aprs.collectAsState()
    val aprsChannel by s.aprsChannel.collectAsState()
    val digi by s.digi.collectAsState()
    val st by s.state.collectAsState()
    var tab by remember { mutableStateOf(openingTab(s.state.value)) }
    val tabs = heardTabs(st)
    // A tab the module stopped reporting (a restart into an older version) falls back to the decodes.
    if (tab !in tabs) tab = HeardTab.Decodes
    val firstTab = remember { FocusRequester() }
    val tuneKey = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(60); runCatching { firstTab.requestFocus() } }

    val list = when (tab) { HeardTab.Decodes -> decodes; HeardTab.Stations -> spots; HeardTab.Aprs -> aprs; else -> emptyList() }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = TvSafeH, vertical = TvSafeV)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.heard_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
            tabs.forEachIndexed { i, t ->
                val n = when (t) {
                    HeardTab.Decodes -> decodes.size; HeardTab.Stations -> spots.size; HeardTab.Aprs -> aprs.size
                    HeardTab.Dv -> st?.dv?.heard?.size; HeardTab.Cw -> null
                }
                // Down from the APRS tab is the channel key, not the nearer receiver key beside it: OK there
                // is then the tune the subtitle promises.
                val down = if (t == HeardTab.Aprs && tab == HeardTab.Aprs && aprsChannel != null) Modifier.focusProperties { this.down = tuneKey }
                    else Modifier
                // Focus starts on the tab the window opened on, so OK there is never a surprise.
                TvItem(onClick = { tab = t }, modifier = down, focus = if (t == tab || (i == 0 && tab !in tabs)) firstTab else null,
                    selected = t == tab) {
                    Text(if (n == null) stringResource(t.title) else stringResource(R.string.heard_tab_count, stringResource(t.title), n),
                        fontSize = 18.sp)
                }
            }
        }
        Text(subtitle(tab, digi.holder, st), color = Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        if ((tab == HeardTab.Decodes || tab == HeardTab.Stations) && digi.holder == null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("FT8", "FT4", "JS8").filter { digi.modes.isEmpty() || it in digi.modes }.forEach { m ->
                    TvItem(onClick = { s.startDecoder(m) }) { Text(stringResource(R.string.heard_start_decoder, m), fontSize = 16.sp) }
                }
            }
        }
        if (tab == HeardTab.Aprs) {
            // The channel key is there whether or not anything has been heard: with no stations there are
            // no rows, and "OK tunes to the channel" must still have something to press (see tuneToAprsChannel).
            val locale = shackLocale()
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                aprsChannel?.let { hz ->
                    TvItem(onClick = { s.tuneToAprsChannel() }, focus = tuneKey) {
                        Text(stringResource(R.string.heard_tune_aprs, ShackFormat.frequency(hz, locale)), fontSize = 16.sp)
                    }
                }
                if (aprs.isEmpty()) TvItem(onClick = { s.startAprs() }) { Text(stringResource(R.string.heard_start_aprs), fontSize = 16.sp) }
            }
        }
        when (tab) {
            HeardTab.Cw -> CwView(st, Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp))
            HeardTab.Dv -> {
                val dv = st?.dv
                Column(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp)) {
                    if (dv != null) DvNow(dv)
                    val now = System.currentTimeMillis()
                    LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dv?.heard.orEmpty()) { c -> DvRow(c, now) }
                    }
                }
            }
            else -> {
                if (list.isEmpty()) {
                    Text(stringResource(R.string.heard_nothing), color = Dim, fontSize = 18.sp, modifier = Modifier.padding(top = 20.dp))
                }
                LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.key }) { h -> HeardRow(h) { s.tuneToHeard(h) } }
                }
            }
        }
        // CW and D-STAR rows tune nothing: their hint says only how to leave.
        Text(stringResource(if (tab == HeardTab.Cw || tab == HeardTab.Dv) R.string.heard_hint_back else R.string.heard_hint),
            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp)
    }
}

/**
 * The module's CW decoder (TV-API 9): the NB kind's line ("CW · 18.0 WPM"
 * and the newest letters) on top, and under it the whole text read so far,
 * large, newest at the end - room to read a QSO across the room, which the
 * strip under the face never had.
 */
@Composable
private fun CwView(st: RadioState?, modifier: Modifier) {
    val cw = st?.cw ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DecodedStrip(cw.line, big = true, waiting = stringResource(R.string.cw_listening), modifier = Modifier.fillMaxWidth())
        if (cw.text.isNotEmpty()) {
            Text(cw.text, color = Color.White, fontSize = 30.sp, fontFamily = FontFamily.Monospace, lineHeight = 40.sp,
                modifier = Modifier.fillMaxWidth().background(Color(0xFF0A0F14)).padding(16.dp))
        }
    }
}

@Composable
private fun subtitle(tab: HeardTab, holder: String?, st: RadioState?) = when (tab) {
    HeardTab.Decodes -> if (holder != null) stringResource(R.string.heard_sub_decoding, holder)
        else stringResource(R.string.heard_sub_no_decoder)
    HeardTab.Stations -> stringResource(R.string.heard_sub_stations)
    HeardTab.Aprs -> stringResource(R.string.heard_sub_aprs)
    HeardTab.Cw -> stringResource(if (st?.cw?.active == true) R.string.heard_sub_cw else R.string.heard_sub_cw_off)
    HeardTab.Dv -> stringResource(R.string.heard_sub_dv)
}

/**
 * The right-hand column of a heard row: where it was heard, then the audio
 * offset it was decoded at (or, for APRS, the mode a tune uses).
 *
 * The frequency is in the contract's format (I18N.md §4.1: kHz with one
 * decimal below 30 MHz, MHz with three from there up, the locale's decimal
 * separator, a no-break space before the unit) and not in the dotted
 * MHz.kHz.Hz groups of the main dial. The dial and the radio faces copy the
 * radio's own display, where the dots are what an Icom shows; this list is
 * ours, and here the dots would read as digit grouping in half the world.
 * Three MHz decimals round off the Hz a decode's audio offset adds; the
 * offset is printed beneath, exactly, and OK tunes to the exact Hz.
 */
internal fun heardTuning(h: Heard, locale: String): Pair<String, String> =
    (h.rfHz?.let { ShackFormat.frequency(it, locale) } ?: "") to
        (h.audioHz?.let { ShackFormat.offset(it, "Hz", locale) } ?: h.tuneMode ?: "")

@Composable
private fun HeardRow(h: Heard, onTune: () -> Unit) {
    val locale = shackLocale()
    TvItem(onClick = onTune, modifier = Modifier.fillMaxWidth()) {
        Text(clock(h.whenS), color = Dim, fontSize = 18.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(110.dp))
        Text(h.snr?.let { "%+d".format(it) } ?: "", color = Dim, fontSize = 20.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(h.call ?: "?", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    color = if (h.forMe) Amber else if (h.cq) LockGreen else Color.White)
                listOfNotNull(h.mode, h.grid, h.country, h.distanceKm?.let { stringResource(R.string.heard_distance_km, ShackFormat.number(it, locale)) }).forEach {
                    Text(it, color = Dim, fontSize = 16.sp)
                }
            }
            Text(h.text, fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            val (freq, under) = heardTuning(h, locale)
            Text(freq, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
            Text(under, color = Dim, fontSize = 14.sp)
        }
    }
}

private fun clock(s: Double?): String {
    if (s == null) return ""
    val t = Instant.ofEpochMilli((s * 1000).toLong()).atZone(ZoneOffset.UTC)
    return "%02d:%02d:%02d".format(t.hour, t.minute, t.second)
}
