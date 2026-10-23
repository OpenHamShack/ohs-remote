package com.openhamshack.remote.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.core.tv.TvTextDialog
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.Beacons
import com.openhamshack.remote.shack.Compass
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.EngineUi
import com.openhamshack.remote.shack.LocalEntry
import com.openhamshack.remote.shack.LocalFreqs
import com.openhamshack.remote.shack.LocalList

// Local frequencies (Brian approved TvPolishLocal, 23/10/2026): what is on
// the air near the station, nearest first, OK (or a tap) tuning with the
// mode and the step; the NCDXF beacon on each HF band now; and the
// operator's own frequencies, added, changed and removed at Control level.
// A phone or tablet opens it from the Bands sheet as a sheet of its own; a
// TV from the band picker as a screen over the radio. Built against
// fakeshack (RADIO-LISTEN-CONTRACT §5): Radio's API for it is not out.

@Composable
internal fun localCategory(c: String?): String = when (c) {
    null -> stringResource(R.string.shack_local_all)
    LocalFreqs.AIRPORT -> stringResource(R.string.shack_local_cat_airport)
    LocalFreqs.AREA -> stringResource(R.string.shack_local_cat_area)
    LocalFreqs.MARINE -> stringResource(R.string.shack_local_cat_marine)
    LocalFreqs.PMR -> stringResource(R.string.shack_local_cat_pmr)
    LocalFreqs.BEACON -> stringResource(R.string.shack_local_cat_beacon)
    LocalFreqs.MINE -> stringResource(R.string.shack_local_cat_mine)
    else -> c.replaceFirstChar { it.uppercase() }
}

@Composable
private fun localBadge(c: String): String = when (c) {
    LocalFreqs.AIRPORT -> stringResource(R.string.shack_local_badge_airport)
    LocalFreqs.AREA -> stringResource(R.string.shack_local_badge_area)
    LocalFreqs.MARINE -> stringResource(R.string.shack_local_badge_marine)
    LocalFreqs.PMR -> stringResource(R.string.shack_local_badge_pmr)
    LocalFreqs.BEACON -> stringResource(R.string.shack_local_badge_beacon)
    LocalFreqs.MINE -> stringResource(R.string.shack_local_badge_mine)
    else -> stringResource(R.string.shack_local_badge_other)
}

/** Each category's badge colour: deep tones that read apart on the dark cards. */
private fun badgeColour(c: String): Color = when (c) {
    LocalFreqs.AIRPORT -> Color(0xFF2A6F8F)
    LocalFreqs.AREA -> Color(0xFF5B3F8F)
    LocalFreqs.MARINE -> Color(0xFF2F7F7A)
    LocalFreqs.PMR -> Color(0xFF7A5A1E)
    LocalFreqs.BEACON -> Color(0xFF8F4F2A)
    LocalFreqs.MINE -> Color(0xFF3F7F4A)
    else -> Color(0xFF4A5A7A)
}

@Composable
private fun compassWord(c: Compass): String = stringResource(when (c) {
    Compass.N -> R.string.shack_compass_n
    Compass.NE -> R.string.shack_compass_ne
    Compass.E -> R.string.shack_compass_e
    Compass.SE -> R.string.shack_compass_se
    Compass.S -> R.string.shack_compass_s
    Compass.SW -> R.string.shack_compass_sw
    Compass.W -> R.string.shack_compass_w
    Compass.NW -> R.string.shack_compass_nw
})

/** "12 km W", "4.2 km N"; empty without a distance. */
@Composable
private fun distanceText(e: LocalEntry): String {
    val km = e.distanceKm ?: return ""
    val n = com.openhamshack.i18n.ShackFormat.number(km, localeTag(), if (km < 10) 1 else 0)
    return LocalFreqs.compass(e.bearing)?.let { stringResource(R.string.shack_local_km_bearing, n, compassWord(it)) }
        ?: stringResource(R.string.shack_local_km, n)
}

/** The heading's second line: "Within 100 km of IO85 · nearest first", or as much of it as the server said. */
@Composable
private fun withinText(l: LocalList): String {
    val r = l.radiusKm
    return if (r != null && l.locator.isNotEmpty()) stringResource(R.string.shack_local_within,
        stringResource(R.string.shack_local_km, com.openhamshack.i18n.ShackFormat.number(r, localeTag(), 0)), l.locator)
    else stringResource(R.string.shack_local_nearest)
}

/** The beacons on now, refreshed at each 10 s slot change (LocalFreqs.beaconsNow works it out from the clock). */
@Composable
private fun rememberUtcNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(LocalFreqs.msToNextSlot(System.currentTimeMillis()) + 50)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** The category's little square badge (TvPolishLocal's left column). */
@Composable
private fun CategoryBadge(c: String, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 4)).background(badgeColour(c)), contentAlignment = Alignment.Center) {
        Text(localBadge(c), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PolishText, maxLines = 1)
    }
}

// --- phone and tablet ---------------------------------------------------------

/**
 * The phone's and tablet's sheet: the heading, the category chips, the
 * entries (a tap tunes and closes; a long press on one of the operator's
 * own changes or removes it), the beacon card under HF beacons, Add under
 * My frequencies, and the sources' attribution at the foot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocalSheet(e: EngineSession, u: EngineUi, onDone: () -> Unit) {
    val local = u.local ?: return
    var cat by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LocalEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    val l = local.list
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.shack_local_title), fontSize = 20.sp, fontWeight = FontWeight.Medium)
        l?.let { Text(withinText(it), fontSize = 13.sp, color = Dim) }
        if (local.loading && l == null) Text(stringResource(R.string.shack_local_loading), color = Dim)
        if (local.missing) Text(stringResource(R.string.shack_local_missing), color = Dim)
        else if (local.problem.isNotEmpty()) Text(local.problem, color = Amber)
        if (l != null) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<String?>(null) + LocalFreqs.categories(l)).forEach { c ->
                    FilterChip(selected = c == cat, onClick = { cat = c }, label = { Text(localCategory(c)) },
                        modifier = Modifier.testTag("localCat-${c ?: "all"}"))
                }
            }
            // The beacon card heads HF beacons; under All it follows the list, which is what most come for.
            if (cat == LocalFreqs.BEACON) l.beacons?.let { BeaconCardPhone(e, u, it, onDone) }
            if (cat == LocalFreqs.MINE) FilledTonalButton(onClick = { adding = true }, enabled = !u.viewOnly && u.dialHz != null,
                modifier = Modifier.testTag("localAdd")) { Text(stringResource(R.string.shack_local_add)) }
            val list = LocalFreqs.filter(l, cat)
            if (list.isEmpty() && cat != LocalFreqs.BEACON) Text(stringResource(R.string.shack_local_empty), color = Dim)
            list.forEach { x ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(RadioCard)
                        .combinedClickable(onClick = { e.tuneLocal(LocalFreqs.target(x, u.offered)); onDone() },
                            onLongClick = if (x.mine && x.id != null && !u.viewOnly) ({ editing = x }) else null)
                        .padding(horizontal = 12.dp, vertical = 8.dp).testTag("local-${x.id ?: x.name}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CategoryBadge(x.category, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(x.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(x.sub.ifEmpty { null }, distanceText(x).ifEmpty { null }).joinToString(" · ")
                        if (sub.isNotEmpty()) Text(sub, fontSize = 12.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(channelText(x.freqHz), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        x.mode?.let { Text(modeWord(it), fontSize = 12.sp, color = Dim) }
                    }
                }
            }
            if (cat == null) l.beacons?.let { BeaconCardPhone(e, u, it, onDone) }
            if (u.viewOnly && cat == LocalFreqs.MINE) Text(stringResource(R.string.shack_local_view_only), color = Amber, fontSize = 13.sp)
            if (l.attributions.isNotEmpty()) Text(stringResource(R.string.shack_local_about, l.attributions.joinToString(" · ")), fontSize = 12.sp,
                color = Dim, lineHeight = 17.sp)
        }
    }
    if (adding) MineDialog(null, u, onDismiss = { adding = false }) { name, hz -> adding = false
        e.saveMine(null, name, hz, u.state.mode, u.stepHz) }
    editing?.let { x ->
        MineDialog(x, u, onDismiss = { editing = null }, onDelete = { editing = null; x.id?.let { e.deleteMine(it) } }) { name, hz ->
            editing = null
            e.saveMine(x.id, name, hz, x.mode ?: u.state.mode, x.stepHz)
        }
    }
}

/** Add (prefilled with the dial) or change one of the operator's own: a name and a frequency in MHz; Remove for an existing one. */
@Composable
private fun MineDialog(x: LocalEntry?, u: EngineUi, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (String, Double) -> Unit) {
    val tag = localeTag()
    var name by remember { mutableStateOf(x?.name.orEmpty()) }
    var freq by remember { mutableStateOf(channelText(x?.freqHz ?: u.dialHz ?: 0.0, tag)) }
    val hz = runCatching { com.openhamshack.i18n.ShackFormat.parseFrequency(freq, tag, "MHz").toDouble() }.getOrNull()?.takeIf { it > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shack_local_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(60).replace("\n", " ") }, singleLine = true, label = { Text(stringResource(R.string.shack_local_name)) },
                    modifier = Modifier.testTag("mineName"))
                OutlinedTextField(freq, { freq = it }, singleLine = true, label = { Text(stringResource(R.string.shack_local_freq)) },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = hz == null, modifier = Modifier.testTag("mineFreq"))
            }
        },
        confirmButton = {
            TextButton(onClick = { hz?.let { onSave(name, it) } }, enabled = name.isNotBlank() && hz != null) { Text(stringResource(R.string.shack_local_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.shack_local_remove), color = OnAirRed) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.shack_local_cancel)) }
            }
        },
    )
}

/** The phone's beacon card: who is on 20 m now, where, and each band as a chip that tunes to its beacon. */
@Composable
private fun BeaconCardPhone(e: EngineSession, u: EngineUi, b: Beacons, onDone: () -> Unit) {
    val now = LocalFreqs.beaconsNow(b, rememberUtcNow())
    val first = now.firstOrNull() ?: return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RadioCard).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SmallHeading(stringResource(R.string.shack_local_beacons_heading))
        Text(first.call, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text(stringResource(R.string.shack_local_beacon_line, first.location, channelText(first.band.freqHz)), fontSize = 13.sp, color = Dim)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            now.forEach { n ->
                FilterChip(selected = false, onClick = { e.tuneLocal(LocalFreqs.target(n.band, u.offered)); onDone() },
                    label = { Text(stringResource(R.string.shack_band_segment, n.band.band, n.call)) })
            }
        }
        Text(stringResource(R.string.shack_local_beacon_note), fontSize = 12.sp, color = Dim)
    }
}

// --- TV -----------------------------------------------------------------------

/**
 * The TV's Local frequencies (TvPolishLocal), over the radio: the heading,
 * the category chips, the entries nearest first (OK tunes, with the mode and
 * the step, and returns to the radio), and at the right the beacon on air
 * now with a pill per band (OK on a pill tunes to that band's beacon) and
 * the sources' attribution. Under My frequencies: Add this frequency (a
 * name typed; the dial, mode and step in use) and Remove beside each.
 * BACK closes it (the caller's).
 */
@Composable
internal fun TvLocalScreen(e: EngineSession, u: EngineUi, first: FocusRequester, onClose: () -> Unit, modifier: Modifier) {
    val local = u.local ?: return
    var cat by remember { mutableStateOf<String?>(null) }
    var naming by remember { mutableStateOf(false) }
    val refused = stringResource(R.string.shack_local_view_only)
    val l = local.list
    LaunchedEffect(l != null) { delay(120); runCatching { first.requestFocus() } }
    Column(modifier.polishVignette().padding(horizontal = TvSafeH, vertical = TvSafeV), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.shack_local_heading), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Accent)
            Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A4654)))
            Text(l?.let { withinText(it) } ?: if (local.loading) stringResource(R.string.shack_local_loading) else "", fontSize = 15.sp, color = Color(0xFFA7B3C0),
                maxLines = 1, modifier = Modifier.weight(1f))
        }
        if (local.missing) Text(stringResource(R.string.shack_local_missing), fontSize = 18.sp, color = PolishDim)
        else if (local.problem.isNotEmpty()) Text(local.problem, fontSize = 16.sp, color = Amber)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            (listOf<String?>(null) + (l?.let { LocalFreqs.categories(it) } ?: listOf(LocalFreqs.MINE))).forEachIndexed { i, c ->
                TvItem(onClick = { cat = c }, selected = c == cat, compact = true, focus = if (i == 0) first else null) {
                    Text(localCategory(c), fontSize = 15.sp, color = if (c == cat) Accent else Color.Unspecified)
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (cat == LocalFreqs.MINE) TvItem(onClick = { if (u.viewOnly) e.note(refused) else naming = true }, compact = true,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.shack_local_add), fontSize = 17.sp, color = Accent) }
                val list = l?.let { LocalFreqs.filter(it, cat) }.orEmpty()
                if (l != null && list.isEmpty()) Text(stringResource(R.string.shack_local_empty), fontSize = 16.sp, color = PolishDim)
                list.forEach { x ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TvItem(onClick = { e.tuneLocal(LocalFreqs.target(x, u.offered)); onClose() }, compact = true,
                            modifier = Modifier.weight(1f).height(54.dp)) {
                            CategoryBadge(x.category, 34.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(x.name, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (x.sub.isNotEmpty()) Text(x.sub, fontSize = 12.sp, color = PolishDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(channelText(x.freqHz), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, textAlign = TextAlign.End,
                                modifier = Modifier.widthIn(min = 96.dp))
                            Text(x.mode?.let { modeWord(it) }.orEmpty(), fontSize = 13.sp, color = PolishDim, maxLines = 1,
                                modifier = Modifier.width(44.dp).padding(start = 8.dp))
                            Text(distanceText(x), fontSize = 13.sp, color = Color(0xFFA7B3C0), maxLines = 1, textAlign = TextAlign.End,
                                modifier = Modifier.width(76.dp))
                        }
                        if (x.mine && x.id != null) TvItem(onClick = { if (u.viewOnly) e.note(refused) else e.deleteMine(x.id) }, compact = true) {
                            Text(stringResource(R.string.shack_local_remove), fontSize = 14.sp, color = OnAirRed)
                        }
                    }
                }
            }
            Column(Modifier.width(300.dp).fillMaxHeight().glass(RoundedCornerShape(20.dp)).padding(18.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                l?.beacons?.let { b ->
                    val now = LocalFreqs.beaconsNow(b, rememberUtcNow())
                    now.firstOrNull()?.let { n ->
                        PolishLabel(stringResource(R.string.shack_local_beacons_heading))
                        Text(n.call, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = PolishText)
                        Text(stringResource(R.string.shack_local_beacon_line, n.location, channelText(n.band.freqHz)), fontSize = 14.sp, color = Color(0xFFA7B3C0))
                    }
                    // A pill per band, wrapping: five side by side in the card's width cut "17" to "1".
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        now.forEach { x ->
                            TvItem(onClick = { e.tuneLocal(LocalFreqs.target(x.band, u.offered)); onClose() }, compact = true) {
                                Text(x.band.band, fontSize = 14.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                    Text(stringResource(R.string.shack_local_beacon_note), fontSize = 12.sp, color = PolishDim, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(6.dp))
                if (l != null && l.attributions.isNotEmpty()) Text(stringResource(R.string.shack_local_about, l.attributions.joinToString(" · ")),
                    fontSize = 11.sp, color = Color(0xFF6F7C8A), lineHeight = 15.sp)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvItem(onClick = onClose) { Text(stringResource(R.string.shack_tv_bands), fontSize = 17.sp) }
            TvItem(onClick = { cat = LocalFreqs.MINE }) { Text(stringResource(R.string.shack_local_cat_mine), fontSize = 17.sp) }
            TvItem(onClick = { e.mute(!u.muted) }) {
                Text(stringResource(if (u.muted) R.string.shack_unmute else R.string.shack_mute), fontSize = 17.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.shack_local_keys_ok), fontSize = 12.sp, color = Color(0xFFD6DEE6),
                    modifier = Modifier.border(1.dp, Color(0xFF3A4654), RoundedCornerShape(5.dp)).padding(horizontal = 6.dp))
                Text(stringResource(R.string.shack_local_hint), fontSize = 13.sp, color = PolishDim)
            }
        }
    }
    if (naming) {
        val where = stringResource(R.string.shack_local_add_help, channelText(u.dialHz ?: 0.0), modeWord(u.state.mode))
        TvTextDialog(stringResource(R.string.shack_local_add_title), where, KeyboardType.Text, onDismiss = { naming = false }) { text, _ ->
            naming = false
            val hz = u.dialHz
            if (text.isNotBlank() && hz != null) e.saveMine(null, text, hz, u.state.mode, u.stepHz)
        }
    }
}
