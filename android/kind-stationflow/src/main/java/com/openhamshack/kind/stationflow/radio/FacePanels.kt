package com.openhamshack.kind.stationflow.radio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.openhamshack.core.scope.Palette
import com.openhamshack.core.scope.ScopeLook
import com.openhamshack.core.tv.ChoiceOverlay
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.PanelHeading
import com.openhamshack.core.tv.SettingRow
import com.openhamshack.core.tv.SidePanel
import com.openhamshack.core.tv.TvTextDialog
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.TUNE_STEPS
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.formatStep
import com.openhamshack.kind.stationflow.parseTypedFrequency

/**
 * Whichever of the face's own overlays is open, over the face (the screen
 * makes the face inert beneath it). Everything here is receive-side: the
 * session's client cannot send anything else (StationFlowClient.CONTROLS).
 * [look] is this device's scope look for the radio, [setLook] keeps a change.
 */
@Composable
fun FacePanels(
    panel: FacePanel?, s: StationFlowSession, st: RadioState?, muted: Boolean, step: Long,
    look: ScopeLook, setLook: (ScopeLook) -> Unit,
    onOpen: (FacePanel) -> Unit, onClose: () -> Unit,
) {
    when (panel) {
        null -> {}
        FacePanel.Mode -> if (st != null) ChoiceOverlay(
            stringResource(R.string.face_mode_title), modeRows(st), onPick = { s.setMode(it.key) }, onClose = onClose,
            hint = stringResource(R.string.face_overlay_hint),
        ) else onClose()
        FacePanel.Filter -> ChoiceOverlay(
            stringResource(R.string.face_filter_title), listOf(filterChoices(st)), onPick = { s.setFilter(it.key) },
            onClose = onClose, hint = stringResource(R.string.face_overlay_hint),
        )
        FacePanel.FrontEnd -> FrontEnd(s, st, onClose)
        FacePanel.Entry -> {
            val bad = stringResource(R.string.tune_type_frequency_error)
            TvTextDialog(
                stringResource(R.string.tune_frequency), stringResource(R.string.tune_frequency_help),
                KeyboardType.Decimal, onDismiss = onClose,
            ) { text, fail ->
                val hz = parseTypedFrequency(text)
                if (hz == null) fail(bad) else { s.tuneTo(hz); onClose() }
            }
        }
        FacePanel.Settings -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Settings(s, st, muted, step, onOpen, onClose)
        }
        FacePanel.Scope -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            ScopeSettingsPanel(s, st, look, setLook, onClose)
        }
    }
}

/** P.AMP and ATT as the radio's settings, one row each; EXT shown dimmed with the module's reason when refused. */
@Composable
private fun FrontEnd(s: StationFlowSession, st: RadioState?, onClose: () -> Unit) {
    val menuOff = stringResource(R.string.preamp_ext_menu_off)
    val unread = stringResource(R.string.preamp_ext_menu_unread)
    val pre = preampChoices(st) { _ ->
        when (st?.extPreampNote) {
            com.openhamshack.kind.stationflow.api.ExtPreampNote.MenuOff -> menuOff
            com.openhamshack.kind.stationflow.api.ExtPreampNote.MenuUnread -> unread
            else -> null
        }
    }
    val att = attChoices(st).map { it.copy(key = "att:" + it.key) }
    ChoiceOverlay(
        stringResource(R.string.face_front_end_title), listOf(pre, att).filter { it.isNotEmpty() },
        onPick = { c -> if (c.key.startsWith("att:")) s.setAtt(c.key.removePrefix("att:").toInt()) else s.setPreampChoice(c.key) },
        onClose = onClose, hint = stringResource(R.string.face_overlay_hint),
    )
}

/**
 * The SET key's sheet: the radio's less-used receive settings, moved off
 * the key bar. Only what the module reports is offered - no row for the
 * radio's AGC, NB or NR until the module can set them (it cannot yet), no
 * NR2 row from a module without NR2, no P.AMP row for a radio with none.
 */
@Composable
private fun Settings(s: StationFlowSession, st: RadioState?, muted: Boolean, step: Long, onOpen: (FacePanel) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    SidePanel(stringResource(R.string.face_settings_title), onClose) {
        PanelHeading(stringResource(R.string.face_settings_receive))
        if (hasFrontEnd(st)) {
            SettingRow(stringResource(R.string.face_settings_front_end),
                listOfNotNull(st?.preampName, st?.attName?.let { "ATT $it" }).joinToString("  ").ifEmpty { "—" },
                onPlus = { onOpen(FacePanel.FrontEnd) }, focus = first)
        }
        val nr2 = st?.nr2
        if (nr2 != null && nr2.available) {
            SettingRow(stringResource(R.string.face_settings_nr2),
                stringResource(if (nr2.on) R.string.face_on else R.string.face_off),
                onPlus = { s.setNr2(on = !nr2.on) }, focus = if (!hasFrontEnd(st)) first else null,
                note = stringResource(R.string.face_settings_nr2_note))
            SettingRow(stringResource(R.string.face_settings_nr2_level), nr2.level.toString(),
                onPlus = { s.setNr2(level = (nr2.level + NR2_STEP).coerceAtMost(100)) },
                onMinus = { s.setNr2(level = (nr2.level - NR2_STEP).coerceAtLeast(0)) })
        }
        st?.cwPitch?.let { pitch ->
            // Read only: the module reads the radio's pitch (CI-V 14 09) and has no way to set it.
            SettingRow(stringResource(R.string.face_settings_cw_pitch), stringResource(R.string.face_hz, pitch), onPlus = {})
        }
        SettingRow(stringResource(R.string.face_settings_mute),
            stringResource(if (muted) R.string.face_on else R.string.face_off), onPlus = { s.toggleMute() },
            focus = if (!hasFrontEnd(st) && (nr2 == null || !nr2.available)) first else null)
        SettingRow(stringResource(R.string.face_settings_step), formatStep(step),
            onPlus = { s.setStep(stepAlong(step, +1)) }, onMinus = { s.setStep(stepAlong(step, -1)) })
        PanelHeading(stringResource(R.string.face_settings_display))
        SettingRow(stringResource(R.string.face_settings_scope), "›", onPlus = { onOpen(FacePanel.Scope) })
        Text(stringResource(R.string.face_panel_back), color = Dim, fontSize = 14.sp)
    }
}

/** NR2's level moves in tens: twenty presses from gentlest to strongest would be too many on a remote. */
private const val NR2_STEP = 10

/** The tuning step one along the ladder, stopping at its ends. */
private fun stepAlong(step: Long, by: Int): Long {
    val i = TUNE_STEPS.indexOf(step).takeIf { it >= 0 } ?: TUNE_STEPS.indexOfFirst { it >= step }.coerceAtLeast(0)
    return TUNE_STEPS[(i + by).coerceIn(0, TUNE_STEPS.size - 1)]
}

/**
 * The scope's settings: first what the radio's own scope can be set to
 * (span, reference level, sweep speed, centre/fixed, VBW, averaging - each
 * only when the model reports it can), then how this screen draws it
 * (colours, contrast, brightness, fill, averaging), kept for this radio on
 * this device.
 */
@Composable
private fun ScopeSettingsPanel(s: StationFlowSession, st: RadioState?, look: ScopeLook, setLook: (ScopeLook) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    val caps = st?.scopeCaps
    val now = st?.scopeSettings
    val dash = "—"
    SidePanel(stringResource(R.string.face_scope_title), onClose) {
        if (caps != null) {
            PanelHeading(stringResource(R.string.face_scope_radio))
            if (caps.spansHz.isNotEmpty()) {
                SettingRow(stringResource(R.string.face_scope_span), now?.spanHz?.let { spanLabel(it) } ?: dash,
                    onPlus = { s.zoomScope(zoomIn = true) }, onMinus = { s.zoomScope(zoomIn = false) }, focus = first,
                    note = stringResource(R.string.face_scope_span_note).takeIf { now?.mode == "fixed" })
            }
            SettingRow(stringResource(R.string.face_scope_ref), now?.refDb?.let { refLabel(it) } ?: dash,
                onPlus = { s.stepScopeRef(up = true) }, onMinus = { s.stepScopeRef(up = false) },
                focus = if (caps.spansHz.isEmpty()) first else null)
            if (caps.speeds.isNotEmpty()) {
                SettingRow(stringResource(R.string.face_scope_speed), now?.speed?.uppercase() ?: dash,
                    onPlus = { nextIn(caps.speeds, now?.speed)?.let { s.setScope(JSONObject().put("speed", it)) } },
                    onMinus = { nextIn(caps.speeds, now?.speed, -1)?.let { s.setScope(JSONObject().put("speed", it)) } })
            }
            if (caps.modes.size > 1) {
                SettingRow(stringResource(R.string.face_scope_mode), now?.mode?.uppercase() ?: dash,
                    onPlus = { nextIn(caps.modes, now?.mode)?.let { s.setScope(JSONObject().put("mode", it)) } })
            }
            if (caps.edges > 1 && now?.mode == "fixed") {
                val edges = (1..caps.edges).toList()
                SettingRow(stringResource(R.string.face_scope_edge), now.edge?.toString() ?: dash,
                    onPlus = { nextIn(edges, now.edge)?.let { s.setScope(JSONObject().put("edge", it)) } },
                    onMinus = { nextIn(edges, now.edge, -1)?.let { s.setScope(JSONObject().put("edge", it)) } })
            }
            if (caps.vbw.size > 1) {
                SettingRow(stringResource(R.string.face_scope_vbw), now?.vbw?.uppercase() ?: dash,
                    onPlus = { nextIn(caps.vbw, now?.vbw)?.let { s.setScope(JSONObject().put("vbw", it)) } })
            }
            if (caps.averaging.size > 1) {
                SettingRow(stringResource(R.string.face_scope_radio_avg),
                    now?.averaging?.let { if (it == 0) stringResource(R.string.face_off) else it.toString() } ?: dash,
                    onPlus = { nextIn(caps.averaging, now?.averaging)?.let { s.setScope(JSONObject().put("averaging", it)) } },
                    onMinus = { nextIn(caps.averaging, now?.averaging, -1)?.let { s.setScope(JSONObject().put("averaging", it)) } })
            }
        }
        LookRows(look, setLook, focus = if (caps == null) first else null)
        Text(stringResource(R.string.face_panel_back), color = Dim, fontSize = 14.sp)
    }
}

/** How this screen draws the scope: shared by the Icom face and (through core) Flex. */
@Composable
private fun LookRows(look: ScopeLook, setLook: (ScopeLook) -> Unit, focus: FocusRequester?) {
    PanelHeading(stringResource(R.string.face_scope_look))
    SettingRow(stringResource(R.string.face_look_palette), paletteName(look.palette),
        onPlus = { setLook(look.nextPalette(+1)) }, onMinus = { setLook(look.nextPalette(-1)) }, focus = focus)
    SettingRow(stringResource(R.string.face_look_contrast), "%.2f".format(java.util.Locale.ROOT, look.contrast),
        onPlus = { setLook(look.stepContrast(+1)) }, onMinus = { setLook(look.stepContrast(-1)) })
    SettingRow(stringResource(R.string.face_look_brightness), "%+.1f".format(java.util.Locale.ROOT, look.brightness),
        onPlus = { setLook(look.stepBrightness(+1)) }, onMinus = { setLook(look.stepBrightness(-1)) })
    SettingRow(stringResource(R.string.face_look_trace),
        stringResource(if (look.fill) R.string.face_look_fill else R.string.face_look_line),
        onPlus = { setLook(look.copy(fill = !look.fill)) })
    SettingRow(stringResource(R.string.face_look_averaging),
        if (look.averaging == 0) stringResource(R.string.face_off) else look.averaging.toString(),
        onPlus = { setLook(look.stepAveraging(+1)) }, onMinus = { setLook(look.stepAveraging(-1)) })
    SettingRow(stringResource(R.string.face_look_reset), "", onPlus = { setLook(ScopeLook()) })
}

@Composable
private fun paletteName(p: Palette): String = stringResource(when (p) {
    Palette.Radio -> R.string.palette_radio
    Palette.Rainbow -> R.string.palette_rainbow
    Palette.Grey -> R.string.palette_grey
    Palette.Fire -> R.string.palette_fire
    Palette.Ocean -> R.string.palette_ocean
    Palette.Green -> R.string.palette_green
})
