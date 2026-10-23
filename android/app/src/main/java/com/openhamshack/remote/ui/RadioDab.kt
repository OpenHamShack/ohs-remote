package com.openhamshack.remote.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedButton
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
import com.openhamshack.core.tv.SettingRow
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.DabService
import com.openhamshack.remote.shack.DabUi
import com.openhamshack.remote.shack.EngineSession

// DAB's station list (RADIO-LISTEN-CONTRACT §3), drawn small in today's
// Radio screens: a bottom sheet on a phone or tablet, a side panel on a TV.
// The list is the decoder session's (Dab.kt); a tap or OK on a service the
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

/** The phone and tablet sheet: the block either side, then the services. */
@Composable
internal fun RadioDabSheet(e: EngineSession, d: DabUi, viewOnly: Boolean) {
    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(dabTitle(d), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { e.dabBlock(-1) }, enabled = !viewOnly, modifier = Modifier.testTag("dabDown")) {
                Text(stringResource(R.string.shack_dab_block_down))
            }
            OutlinedButton(onClick = { e.dabBlock(1) }, enabled = !viewOnly, modifier = Modifier.testTag("dabUp")) {
                Text(stringResource(R.string.shack_dab_block_up))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { e.closeDab() }) { Text(stringResource(R.string.shack_dab_close)) }
        }
        val (line, colour) = dabStatus(d)
        Text(line, color = colour)
        d.services.forEach { s ->
            val on = s.sid == d.playing
            Row(
                Modifier.fillMaxWidth().then(if (s.playable && !viewOnly) Modifier.clickable { e.dabPlay(s.sid) } else Modifier)
                    .padding(vertical = 6.dp).testTag("dab-${s.sid}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(s.label, fontSize = 17.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        color = if (!s.playable) Dim else if (on) Accent else Color.White)
                    Text(if (s.playable) dabDetail(s) else s.why, fontSize = 13.sp, color = Dim)
                }
                if (on) Text(stringResource(R.string.shack_dab_playing), color = Accent, fontSize = 13.sp)
            }
        }
        Text(stringResource(R.string.shack_dab_rx_only), color = Dim, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The TV panel: the block as a stepping row (Left and Right change it, as
 * the settings' Step row), then one focusable row per service, Up and Down
 * between them, OK plays. BACK closes it and stops the session (SidePanel's
 * BACK, the caller's onClose).
 */
@Composable
internal fun TvDabRows(e: EngineSession, d: DabUi, viewOnly: Boolean, first: FocusRequester, refused: String) {
    SettingRow(stringResource(R.string.shack_dab_block, d.block.label), dialText(d.block.centreHz),
        onPlus = { if (viewOnly) e.note(refused) else e.dabBlock(1) }, onMinus = { if (viewOnly) e.note(refused) else e.dabBlock(-1) },
        focus = first)
    val (line, colour) = dabStatus(d)
    Text(line, fontSize = 16.sp, color = colour)
    d.services.forEach { s ->
        val on = s.sid == d.playing
        // A service the server cannot play stays focusable, so its why can be read in turn, but OK does nothing.
        TvItem(onClick = { if (viewOnly) e.note(refused) else e.dabPlay(s.sid) }, selected = on, compact = true, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(s.label, fontSize = 18.sp, color = if (s.playable) Color.Unspecified else Dim)
                Text(if (s.playable) dabDetail(s) else s.why, fontSize = 13.sp, color = Dim)
            }
            if (on) Text(stringResource(R.string.shack_dab_playing), color = Accent, fontSize = 14.sp)
        }
    }
    Text(stringResource(R.string.shack_dab_rx_only), fontSize = 14.sp, color = Dim)
}
