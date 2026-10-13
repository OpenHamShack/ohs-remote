package com.openhamshack.remote.ui

import org.json.JSONObject
import com.openhamshack.core.tv.PanelHeading
import com.openhamshack.core.tv.SettingRow
import com.openhamshack.core.scope.Palette
import com.openhamshack.core.scope.ScopeLook
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openhamshack.i18n.ShackFormat
import kotlinx.coroutines.delay
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.DecoderPanel
import com.openhamshack.remote.shack.FlexAgc
import com.openhamshack.remote.shack.FlexCalling
import com.openhamshack.remote.shack.FlexDecoders
import com.openhamshack.remote.shack.FlexDspFunction
import com.openhamshack.remote.shack.FlexDspTable
import com.openhamshack.remote.shack.FlexRfGain
import com.openhamshack.remote.shack.FlexSession
import com.openhamshack.remote.shack.FlexSstvImage
import com.openhamshack.remote.shack.FlexUi
import com.openhamshack.remote.shack.PanelPrefs

// The Flex kind's receive controls on screen: the radio's noise reduction
// and blankers, AGC, RF gain (preamp/attenuator), receive antenna, and the
// module's decoders and SSTV. Brian found the NB screens "too cluttered", so
// on a TV none of this is on the screen until asked for: OK opens the row of
// extras, and each control there opens one page at the right (a side panel)
// with only its own choices. On a phone or tablet the same pages are one
// sheet. Receive only: nothing here keys the radio or chooses a transmit
// antenna, and the session refuses both whatever a screen asks.

/** The pages of the TV's side panel. */
internal enum class FlexPage { NOISE, AGC, RF, ANTENNA, SCOPE, DECODER, PICTURES }

/** A DSP function as a ham reads it: the radio's own short name ("NRL") and what it does. */
@Composable
internal fun dspName(f: FlexDspFunction): String = stringResource(when (f.name) {
    "nr" -> R.string.flex_dsp_nr
    "nb" -> R.string.flex_dsp_nb
    "wnb" -> R.string.flex_dsp_wnb
    "anf" -> R.string.flex_dsp_anf
    "apf" -> R.string.flex_dsp_apf
    "nrl" -> R.string.flex_dsp_nrl
    "nrs" -> R.string.flex_dsp_nrs
    "rnn" -> R.string.flex_dsp_rnn
    "nrf" -> R.string.flex_dsp_nrf
    "anfl" -> R.string.flex_dsp_anfl
    else -> R.string.flex_dsp_anft
})

@Composable
internal fun dspWhat(f: FlexDspFunction): String = stringResource(when (f.name) {
    "nr" -> R.string.flex_dsp_nr_what
    "nb" -> R.string.flex_dsp_nb_what
    "wnb" -> R.string.flex_dsp_wnb_what
    "anf" -> R.string.flex_dsp_anf_what
    "apf" -> R.string.flex_dsp_apf_what
    "nrl" -> R.string.flex_dsp_nrl_what
    "nrs" -> R.string.flex_dsp_nrs_what
    "rnn" -> R.string.flex_dsp_rnn_what
    "nrf" -> R.string.flex_dsp_nrf_what
    "anfl" -> R.string.flex_dsp_anfl_what
    else -> R.string.flex_dsp_anft_what
})

@Composable
internal fun agcName(mode: String): String = when (mode) {
    "off" -> stringResource(R.string.flex_agc_off)
    "slow" -> stringResource(R.string.flex_agc_slow)
    "med" -> stringResource(R.string.flex_agc_med)
    "fast" -> stringResource(R.string.flex_agc_fast)
    else -> mode
}

/** "ATT 8 dB", "0 dB (preamp off)", "Preamp +16 dB": what a ham calls each step. */
@Composable
internal fun rfName(db: Int): String = when (FlexRfGain.kind(db)) {
    FlexRfGain.Kind.ATT -> stringResource(R.string.flex_rf_att, -db)
    FlexRfGain.Kind.OFF -> stringResource(R.string.flex_rf_off)
    FlexRfGain.Kind.PREAMP -> stringResource(R.string.flex_rf_preamp, db)
}

/** A decoder mode as the module names it, in capitals ("FT8"); off in words. */
@Composable
internal fun decoderName(mode: String): String =
    if (mode == FlexDecoders.OFF || mode.isBlank()) stringResource(R.string.flex_decoder_off) else mode.uppercase()

@Composable
private fun mhz(hz: Long): String = ShackFormat.frequency(hz, localeTag(), "MHz", 3)

/** "FT8 is on 7.074 MHz DIGU": where the running decoder's signals are. */
@Composable
internal fun callingText(c: FlexCalling): String = stringResource(R.string.flex_calling, c.label, mhz(c.hz), c.mode)

// --- TV --------------------------------------------------------------------

/**
 * One control in the extras row: a short summary of where it stands ("AGC
 * Med 65", "Antenna ANT1"), opening its page.
 */
@Composable
internal fun FlexExtrasControls(f: FlexSession, u: FlexUi, items: Map<FlexPage, FocusRequester>, onPage: (FlexPage) -> Unit) {
    // A plain Row in a horizontal scroll rather than a LazyRow: ten items at
    // most, and every one must be reachable by Right without the row
    // composing it first.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TvItem(onClick = { f.toggleMute() }, compact = true, selected = u.muted) {
            Text(stringResource(if (u.muted) R.string.flex_unmute else R.string.flex_mute), fontSize = 18.sp)
        }
        TvItem(onClick = { f.toggleNr() }, compact = true, selected = u.nrOn) {
            Text(stringResource(if (u.nrOn) R.string.flex_nr_on else R.string.flex_nr_off), fontSize = 18.sp)
        }
        // The module's own NR2 on the receive audio, beside the radio's NR: only from a module that runs it.
        u.state?.nr2?.takeIf { it.available }?.let { n ->
            TvItem(onClick = { f.setNr2(on = !n.on) }, compact = true, selected = n.on) {
                Text(stringResource(if (n.on) R.string.flex_nr2_on else R.string.flex_nr2_off), fontSize = 18.sp)
            }
        }
        TvItem(onClick = { onPage(FlexPage.NOISE) }, compact = true, focus = items[FlexPage.NOISE]) { Text(stringResource(R.string.flex_noise), fontSize = 18.sp) }
        val s = u.slice
        if (s != null && s.agcMode.isNotBlank()) {
            TvItem(onClick = { onPage(FlexPage.AGC) }, compact = true, focus = items[FlexPage.AGC]) {
                Text(stringResource(R.string.flex_agc_summary, agcName(s.agcMode), s.agcThreshold), fontSize = 18.sp)
            }
        }
        TvItem(onClick = { onPage(FlexPage.RF) }, compact = true, focus = items[FlexPage.RF]) { Text(rfName(u.rfGain), fontSize = 18.sp) }
        if (s != null && s.antList.isNotEmpty()) {
            TvItem(onClick = { onPage(FlexPage.ANTENNA) }, compact = true, focus = items[FlexPage.ANTENNA]) {
                Text(stringResource(R.string.flex_antenna_summary, u.rxAnt), fontSize = 18.sp)
            }
        }
        TvItem(onClick = { onPage(FlexPage.SCOPE) }, compact = true, focus = items[FlexPage.SCOPE]) {
            Text(stringResource(R.string.flex_scope), fontSize = 18.sp)
        }
        u.decoders?.let { d ->
            TvItem(onClick = { onPage(FlexPage.DECODER) }, compact = true, focus = items[FlexPage.DECODER], selected = d.on) {
                Text(stringResource(R.string.flex_decoder_summary, decoderName(d.mode)), fontSize = 18.sp)
            }
        }
        if (u.state?.sstv?.available == true) {
            TvItem(onClick = { f.setSstv(!u.sstvOn) }, compact = true, selected = u.sstvOn) {
                Text(stringResource(if (u.sstvOn) R.string.flex_sstv_on else R.string.flex_sstv_off), fontSize = 18.sp)
            }
        }
        if (u.state?.sstv != null) {
            TvItem(onClick = { onPage(FlexPage.PICTURES) }, compact = true, focus = items[FlexPage.PICTURES]) { Text(stringResource(R.string.flex_pictures), fontSize = 18.sp) }
        }
    }
}

/**
 * The side panel at the right: one control's choices, opaque, the screen
 * beneath unfocusable while it shows (the caller holds that; GUIDE 5).
 * Up/Down between rows, OK chooses or switches, Left/Right move a level,
 * BACK closes it.
 */
@Composable
internal fun TvFlexSide(f: FlexSession, u: FlexUi, page: FlexPage, modifier: Modifier, onClose: () -> Unit, onPicture: (FlexSstvImage) -> Unit,
                        look: ScopeLook = ScopeLook(), setLook: (ScopeLook) -> Unit = {}) {
    val first = remember(page) { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(page) { delay(80); runCatching { first.requestFocus() } }
    Column(
        modifier.width(600.dp).fillMaxHeight().background(Color(0xFF101418))
            .padding(start = 28.dp, end = TvSafeH, top = TvSafeV, bottom = TvSafeV)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(when (page) {
            FlexPage.NOISE -> R.string.flex_noise_title
            FlexPage.AGC -> R.string.flex_agc
            FlexPage.RF -> R.string.flex_rf
            FlexPage.ANTENNA -> R.string.flex_antenna
            FlexPage.SCOPE -> R.string.flex_scope_title
            FlexPage.DECODER -> R.string.flex_decoder
            FlexPage.PICTURES -> R.string.flex_pictures_title
        }), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        when (page) {
            FlexPage.NOISE -> NoisePage(f, u, first)
            FlexPage.AGC -> AgcPage(f, u, first)
            FlexPage.RF -> RfPage(f, u, first)
            FlexPage.ANTENNA -> AntennaPage(f, u, first)
            FlexPage.SCOPE -> FlexScopeRows(f, u, look, setLook, first)
            FlexPage.DECODER -> DecoderPage(f, u, first)
            FlexPage.PICTURES -> PicturesPage(f, u, first, onPicture)
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(if (page == FlexPage.NOISE || page == FlexPage.AGC) R.string.flex_side_hint_levels else R.string.flex_side_hint),
            color = Dim, fontSize = 14.sp)
    }
}

/** A row whose Left/Right move a level: [onStep] gets -1 or +1. */
private fun Modifier.levelKeys(onStep: ((Int) -> Unit)?): Modifier = if (onStep == null) this else onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        Key.DirectionLeft -> { onStep(-1); true }
        Key.DirectionRight -> { onStep(+1); true }
        else -> false
    }
}

@Composable
private fun NoisePage(f: FlexSession, u: FlexUi, first: FocusRequester) {
    val s = u.slice ?: return
    FlexDspTable.offered(s).forEachIndexed { i, fn ->
        val d = s.dsp[fn.name]
        val on = d?.on == true
        // Compact: eleven rows on a radio with the newer DSP, and as many as fit should show.
        TvItem(onClick = { f.setDsp(fn.name, enabled = !on) }, selected = on, focus = if (i == 0) first else null, compact = true,
            modifier = Modifier.fillMaxWidth().levelKeys(if (fn.hasLevel) { by -> f.stepDsp(fn.name, by) } else null)) {
            Column(Modifier.weight(1f)) {
                Text(dspName(fn), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(dspWhat(fn), fontSize = 14.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(if (on) R.string.flex_on else R.string.flex_off), fontSize = 18.sp,
                color = if (on) LockGreen else Dim, modifier = Modifier.align(Alignment.CenterVertically).padding(horizontal = 12.dp))
            if (fn.hasLevel && d?.level != null) {
                Text(stringResource(R.string.flex_level_arrows, d.level), fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}

@Composable
private fun AgcPage(f: FlexSession, u: FlexUi, first: FocusRequester) {
    val s = u.slice ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FlexAgc.MODES.forEach { m ->
            TvItem(onClick = { f.setAgcMode(m) }, compact = true, selected = s.agcMode == m, focus = if (s.agcMode == m || (m == "off" && s.agcMode !in FlexAgc.MODES)) first else null) {
                Text(agcName(m), fontSize = 20.sp)
            }
        }
    }
    TvItem(onClick = {}, modifier = Modifier.fillMaxWidth().levelKeys { by -> f.stepAgcThreshold(by) }) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.flex_agc_t), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.flex_agc_t_what), fontSize = 14.sp, color = Dim)
        }
        Text(stringResource(R.string.flex_level_arrows, s.agcThreshold), fontSize = 20.sp, modifier = Modifier.align(Alignment.CenterVertically))
    }
}

@Composable
private fun RfPage(f: FlexSession, u: FlexUi, first: FocusRequester) {
    Text(stringResource(R.string.flex_rf_what), fontSize = 15.sp, color = Dim)
    u.rfSteps.forEach { db ->
        TvItem(onClick = { f.setRfGain(db) }, selected = db == u.rfGain, focus = if (db == u.rfGain) first else null, modifier = Modifier.fillMaxWidth()) {
            Text(rfName(db), fontSize = 20.sp)
        }
    }
}

@Composable
private fun AntennaPage(f: FlexSession, u: FlexUi, first: FocusRequester) {
    val s = u.slice ?: return
    if (s.antList.isEmpty()) Text(stringResource(R.string.flex_antenna_none), color = Dim, fontSize = 16.sp)
    s.antList.forEachIndexed { i, a ->
        val on = a == u.rxAnt
        TvItem(onClick = { f.setAntenna(a) }, selected = on, focus = if (on || (i == 0 && u.rxAnt !in s.antList)) first else null, modifier = Modifier.fillMaxWidth()) {
            Text(a, fontSize = 20.sp)
        }
    }
    Text(stringResource(R.string.flex_antenna_rx_only), fontSize = 14.sp, color = Dim)
}

@Composable
private fun DecoderPage(f: FlexSession, u: FlexUi, first: FocusRequester) {
    val context = LocalContext.current
    val d = u.decoders ?: return
    if (!d.available) {
        Text(stringResource(R.string.flex_decoder_none, d.why.orEmpty()), color = Amber, fontSize = 16.sp)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (listOf(FlexDecoders.OFF) + d.modes).forEach { m ->
            TvItem(onClick = { f.setDecoder(m) }, compact = true, selected = d.mode == m, focus = if (d.mode == m) first else null) {
                Text(decoderName(m), fontSize = 20.sp)
            }
        }
    }
    u.calling?.let { c ->
        Text(callingText(c), fontSize = 16.sp, color = Amber)
        TvItem(onClick = { f.goToCalling() }) { Text(stringResource(R.string.flex_calling_go, mhz(c.hz), c.mode), fontSize = 18.sp) }
    }
    d.error?.let { Text(it, color = Amber, fontSize = 15.sp) }
    TvItem(onClick = { val on = !u.autoOpen; PanelPrefs.setAutoOpen(context, on); f.setAutoOpen(on) }, selected = u.autoOpen) {
        Text(stringResource(if (u.autoOpen) R.string.shack_panel_auto_on else R.string.shack_panel_auto_off), fontSize = 16.sp)
    }
}

@Composable
private fun PicturesPage(f: FlexSession, u: FlexUi, first: FocusRequester, onPicture: (FlexSstvImage) -> Unit) {
    LaunchedEffect(Unit) { f.loadImages() }
    val (current, _) = synchronized(f.sstv) { f.sstv.current to f.sstv.gallery }
    if (current != null && !current.done) {
        Text(stringResource(R.string.flex_picture_live, sstvFormat(current.fmt), sstvProgress(current)), fontSize = 16.sp, color = Amber)
        SstvImage(f.sstv, current, u.sstvVersion, Modifier.sstvThumb(current, 150.dp))
    }
    if (u.images.isEmpty()) Text(stringResource(R.string.flex_pictures_none), color = Dim, fontSize = 16.sp)
    u.images.forEachIndexed { i, img ->
        TvItem(onClick = { onPicture(img) }, focus = if (i == 0) first else null, modifier = Modifier.fillMaxWidth()) {
            FlexThumb(f, img, 72.dp)
            Column(Modifier.padding(start = 14.dp).align(Alignment.CenterVertically)) {
                Text(sstvFormat(img.fmt), fontSize = 18.sp)
                Text(pictureTime(img), fontSize = 14.sp, color = Dim)
                if (img.partial) Text(stringResource(R.string.flex_picture_partial), fontSize = 13.sp, color = Dim)
            }
        }
    }
}

/** A kept picture's UTC time, as the decodes' column shows it. */
@Composable
internal fun pictureTime(img: FlexSstvImage): String = slotTime(img.time.toDouble())

/** A kept picture from the module, fetched once (the session caches it) and drawn at its own shape. */
@Composable
internal fun FlexThumb(f: FlexSession, img: FlexSstvImage, h: Dp, modifier: Modifier = Modifier) {
    val bitmap = flexPicture(f, img)
    Box(modifier.height(h).width(h * img.aspect).background(Color(0xFF1C1C1C))) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
    }
}

@Composable
private fun flexPicture(f: FlexSession, img: FlexSstvImage): ImageBitmap? {
    var bitmap by remember(img.name) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(img.name) {
        val bytes = f.picture(img) ?: return@LaunchedEffect
        bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }
    return bitmap
}

/** One kept picture, full screen, letterboxed; BACK (or OK) closes it. */
@Composable
internal fun FlexPictureFull(f: FlexSession, img: FlexSstvImage, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val bitmap = flexPicture(f, img)
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { delay(80); runCatching { focus.requestFocus() } }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onClose)) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize().padding(24.dp), contentScale = ContentScale.Fit) }
            TvItem(onClick = onClose, focus = focus, modifier = Modifier.align(Alignment.BottomEnd).padding(TvSafeH)) {
                Text(listOf(sstvFormat(img.fmt), pictureTime(img)).joinToString("  ·  "), fontSize = 16.sp)
            }
        }
    }
}

/**
 * The decoder's panel docked beside the display: the decodes as WSJT-X's
 * compact list, or the SSTV picture as it arrives. Nothing in it takes
 * focus: the D-pad goes on tuning. The full list is in the extras.
 */
@Composable
internal fun FlexDockedPanel(f: FlexSession, u: FlexUi, big: Boolean, modifier: Modifier = Modifier) {
    val shown = u.panel.shown
    Column(modifier.background(Color(0xFF0B0B0B), RoundedCornerShape(6.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        u.calling?.let { Text(callingText(it), color = Amber, fontSize = if (big) 15.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        when (shown) {
            DecoderPanel.SLOTS -> {
                val d = u.decoders
                Text(decoderName(d?.mode.orEmpty()), color = Dim, fontSize = if (big) 15.sp else 12.sp, fontWeight = FontWeight.Bold)
                if (u.decodes.items.isEmpty()) {
                    val period = d?.period?.let { p -> if (p % 1.0 == 0.0) "%d".format(p.toInt()) else db(p) }.orEmpty()
                    Text(stringResource(R.string.flex_decodes_waiting, period), color = Dim, fontSize = if (big) 16.sp else 13.sp)
                }
                DecodeList(u.decodes, u.state?.callsign.orEmpty(), big = big, height = if (big) 560.dp else 150.dp, compact = true)
            }
            DecoderPanel.SSTV -> {
                val current = synchronized(f.sstv) { f.sstv.current }
                if (current == null) Text(stringResource(R.string.sstv_none), color = Dim, fontSize = if (big) 16.sp else 13.sp)
                else {
                    Text(listOf(sstvFormat(current.fmt), sstvProgress(current)).joinToString("  ·  "), color = Dim, fontSize = if (big) 15.sp else 12.sp)
                    SstvImage(f.sstv, current, u.sstvVersion, if (big) Modifier.fillMaxWidth().weight(1f) else Modifier.sstvThumb(current, 180.dp))
                }
            }
            else -> Unit
        }
    }
}

// --- phone and tablet ---------------------------------------------------------

/**
 * Every control as one scrolling sheet on a phone or tablet: the NR switch
 * first, then each control as its own short section.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FlexControlsSheet(f: FlexSession, u: FlexUi, onPicture: (FlexSstvImage) -> Unit) {
    val context = LocalContext.current
    val s = u.slice
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (s == null) {
            Text(stringResource(R.string.flex_no_slice), color = Dim)
            return@Column
        }
        SheetHead(stringResource(R.string.flex_noise_title))
        FlexDspTable.offered(s).forEach { fn ->
            val d = s.dsp[fn.name]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dspName(fn), fontWeight = FontWeight.Bold)
                    Text(dspWhat(fn), color = Dim, fontSize = 12.sp)
                }
                Switch(d?.on == true, { f.setDsp(fn.name, enabled = it) })
            }
            if (fn.hasLevel && d?.level != null) {
                var level by remember(d.level) { mutableStateOf(d.level.toFloat()) }
                // Continuous, landing on fives when let go: nineteen tick marks
                // made every row of the sheet a dotted line.
                Slider(level, { level = it }, valueRange = 0f..100f,
                    onValueChangeFinished = { f.setDsp(fn.name, level = fives(level)) })
            }
        }
        if (s.agcMode.isNotBlank()) {
            SheetHead(stringResource(R.string.flex_agc))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlexAgc.MODES.forEach { m -> FilterChip(selected = s.agcMode == m, onClick = { f.setAgcMode(m) }, label = { Text(agcName(m)) }) }
            }
            Text(stringResource(R.string.flex_agc_t_value, s.agcThreshold))
            var t by remember(s.agcThreshold) { mutableStateOf(s.agcThreshold.toFloat()) }
            Slider(t, { t = it }, valueRange = 0f..100f, onValueChangeFinished = { f.setAgcThreshold(fives(t)) })
        }
        SheetHead(stringResource(R.string.flex_rf))
        Text(stringResource(R.string.flex_rf_what), color = Dim, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            u.rfSteps.forEach { db -> FilterChip(selected = db == u.rfGain, onClick = { f.setRfGain(db) }, label = { Text(rfName(db)) }) }
        }
        if (s.antList.isNotEmpty()) {
            SheetHead(stringResource(R.string.flex_antenna))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.antList.forEach { a -> FilterChip(selected = a == u.rxAnt, onClick = { f.setAntenna(a) }, label = { Text(a) }) }
            }
        }
        u.decoders?.let { d ->
            SheetHead(stringResource(R.string.flex_decoder))
            if (!d.available) Text(stringResource(R.string.flex_decoder_none, d.why.orEmpty()), color = Amber)
            else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(FlexDecoders.OFF) + d.modes).forEach { m ->
                        FilterChip(selected = d.mode == m, onClick = { f.setDecoder(m) }, label = { Text(decoderName(m)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.flex_auto_open), modifier = Modifier.weight(1f))
                    Switch(u.autoOpen, { PanelPrefs.setAutoOpen(context, it); f.setAutoOpen(it) })
                }
            }
        }
        u.calling?.let { c ->
            Text(callingText(c), color = Amber)
            TextButton(onClick = { f.goToCalling() }) { Text(stringResource(R.string.flex_calling_go, mhz(c.hz), c.mode)) }
        }
        u.state?.sstv?.let { st ->
            SheetHead(stringResource(R.string.flex_sstv))
            if (st.available) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.flex_sstv_receive), modifier = Modifier.weight(1f))
                Switch(st.on, { f.setSstv(it) })
            }
            LaunchedEffect(Unit) { f.loadImages() }
            if (u.images.isEmpty()) Text(stringResource(R.string.flex_pictures_none), color = Dim, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                u.images.forEach { img -> FlexThumb(f, img, 72.dp, Modifier.clickable { onPicture(img) }) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A slider's value on the radio's own steps of five. */
private fun fives(v: Float): Int = (Math.round(v / FlexDspTable.LEVEL_STEP) * FlexDspTable.LEVEL_STEP).coerceIn(0, 100)

@Composable
private fun SheetHead(text: String) {
    Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.padding(top = 6.dp))
}

/**
 * The panadapter's settings, then how this screen draws it. The first half
 * - span, reference level, range, waterfall speed - is the radio's own
 * panadapter (api/scope; the operator's SmartSDR follows it), offered only
 * when the module reports it can set them; the second is this device's
 * look (core ScopeLook), kept per radio. NR2's strength is here too, as the
 * one audio setting with a level the extras row cannot show.
 */
@Composable
internal fun FlexScopeRows(f: FlexSession, u: FlexUi, look: ScopeLook, setLook: (ScopeLook) -> Unit, first: FocusRequester?) {
    val pan = u.state?.pan?.takeIf { it.hasScopeSettings }
    var focusUsed = false
    fun focus(): FocusRequester? = if (focusUsed) null else { focusUsed = true; first }
    if (pan != null) {
        PanelHeading(stringResource(R.string.flex_scope_radio))
        if (!pan.spansHz.isNullOrEmpty()) {
            SettingRow(stringResource(R.string.flex_scope_span), flexSpanText(pan.spanSetHz ?: pan.spanHz.toLong()),
                onPlus = { f.zoomScope(zoomIn = true) }, onMinus = { f.zoomScope(zoomIn = false) }, focus = focus())
        }
        SettingRow(stringResource(R.string.flex_scope_ref), stringResource(R.string.flex_dbm, Math.round(pan.refDbm ?: pan.maxDbm).toInt()),
            onPlus = { f.stepScopeRef(up = true) }, onMinus = { f.stepScopeRef(up = false) }, focus = focus())
        pan.rangeDb?.let { range ->
            SettingRow(stringResource(R.string.flex_scope_range), stringResource(R.string.flex_db, Math.round(range).toInt()),
                onPlus = { f.setScope(JSONObject().put("range_db", (range + 10).coerceAtMost(200.0))) },
                onMinus = { f.setScope(JSONObject().put("range_db", (range - 10).coerceAtLeast(10.0))) })
        }
        // The radio's own waterfall rendering, which SmartSDR offers and the module's page does not.
        pan.autoBlack?.let { auto ->
            SettingRow(stringResource(R.string.flex_scope_auto_black),
                stringResource(if (auto) R.string.flex_on else R.string.flex_off), onPlus = { f.setScope(JSONObject().put("auto_black", !auto)) })
            pan.blackLevel?.takeIf { !auto }?.let { b ->
                SettingRow(stringResource(R.string.flex_scope_black_level), b.toString(),
                    onPlus = { f.setScope(JSONObject().put("black_level", (b + 5).coerceAtMost(100))) },
                    onMinus = { f.setScope(JSONObject().put("black_level", (b - 5).coerceAtLeast(0))) })
            }
        }
        pan.colorGain?.let { g ->
            SettingRow(stringResource(R.string.flex_scope_color_gain), g.toString(),
                onPlus = { f.setScope(JSONObject().put("color_gain", (g + 5).coerceAtMost(100))) },
                onMinus = { f.setScope(JSONObject().put("color_gain", (g - 5).coerceAtLeast(0))) })
        }
        pan.lineRate?.let { rate ->
            SettingRow(stringResource(R.string.flex_scope_speed), rate.toString(),
                onPlus = { f.setScope(JSONObject().put("line_rate", (rate + 10).coerceAtMost(100))) },
                onMinus = { f.setScope(JSONObject().put("line_rate", (rate - 10).coerceAtLeast(1))) })
        }
    }
    u.state?.nr2?.takeIf { it.available }?.let { n ->
        SettingRow(stringResource(R.string.flex_nr2_level), n.level.toString(),
            onPlus = { f.setNr2(level = (n.level + 10).coerceAtMost(100)) }, onMinus = { f.setNr2(level = (n.level - 10).coerceAtLeast(0)) },
            focus = focus())
    }
    PanelHeading(stringResource(R.string.flex_scope_look))
    SettingRow(stringResource(R.string.flex_look_palette), paletteWord(look.palette),
        onPlus = { setLook(look.nextPalette(+1)) }, onMinus = { setLook(look.nextPalette(-1)) }, focus = focus())
    SettingRow(stringResource(R.string.flex_look_contrast), "%.2f".format(java.util.Locale.ROOT, look.contrast),
        onPlus = { setLook(look.stepContrast(+1)) }, onMinus = { setLook(look.stepContrast(-1)) })
    SettingRow(stringResource(R.string.flex_look_brightness), "%+.1f".format(java.util.Locale.ROOT, look.brightness),
        onPlus = { setLook(look.stepBrightness(+1)) }, onMinus = { setLook(look.stepBrightness(-1)) })
    SettingRow(stringResource(R.string.flex_look_trace),
        stringResource(if (look.fill) R.string.flex_look_fill else R.string.flex_look_line), onPlus = { setLook(look.copy(fill = !look.fill)) })
    SettingRow(stringResource(R.string.flex_look_averaging),
        if (look.averaging == 0) stringResource(R.string.flex_off) else look.averaging.toString(),
        onPlus = { setLook(look.stepAveraging(+1)) }, onMinus = { setLook(look.stepAveraging(-1)) })
    SettingRow(stringResource(R.string.flex_look_reset), "", onPlus = { setLook(ScopeLook()) })
}

@Composable
private fun paletteWord(p: Palette): String = stringResource(when (p) {
    Palette.Radio -> R.string.flex_palette_radio
    Palette.Rainbow -> R.string.flex_palette_rainbow
    Palette.Grey -> R.string.flex_palette_grey
    Palette.Fire -> R.string.flex_palette_fire
    Palette.Ocean -> R.string.flex_palette_ocean
    Palette.Green -> R.string.flex_palette_green
})
