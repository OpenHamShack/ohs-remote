package com.openhamshack.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.json.JSONObject
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.R
import com.openhamshack.remote.api.Status
import java.util.Locale
import kotlin.math.abs

/**
 * Transmit settings: frequency, symbol rate, mode, modulation, FEC and
 * power, through POST /api/tx/tune under the box's (the console's) rules.
 * The choices are the box's own (GET /api/tx/options), so a modulation
 * never offers a FEC it lacks. Every change goes to the box at once, as a
 * console chip does; a refusal saves nothing and its sentence is shown here.
 *
 * View only: the same panel, dimmed, every tap saying why.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TxSettingsPanel(s: BoxSession, status: Status?) {
    if (status == null) return
    val options by s.txOptions.collectAsStateWithLifecycle()
    val note by s.txNote.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(true) }
    var freqText by remember { mutableStateOf("") }
    var srText by remember { mutableStateOf("") }
    LaunchedEffect(open) { if (open) s.loadTxOptions() }
    val c = status.config
    // Said from onClick, where stringResource cannot be called.
    val typeFrequency = stringResource(R.string.wb_tx_type_frequency)
    val typeRate = stringResource(R.string.wb_type_rate)
    // On a controller these need the Transmit level (the app's own rule),
    // not just Control; on a Classic box the PIN.
    val viewOnly = status.auth.txLocked
    // Every control goes through here: refused with the reason while locked.
    fun tune(patch: JSONObject) {
        if (!viewOnly) s.txTune(patch)
        else s.say(if (status.auth.platform) s.txLevelReason(status) else status.auth.viewOnlyReason)
    }

    Card(Modifier.fillMaxWidth().testTag("txSettings")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.wb_tx_settings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (open) "▾" else "▸ ${txText(c)}", color = Dim, style = MaterialTheme.typography.bodySmall)
            }
            if (!open) return@Column
            if (viewOnly && status.auth.platform) {
                Text(stringResource(R.string.wb_platform_tx_settings_locked), color = Amber, style = MaterialTheme.typography.bodySmall)
            } else if (viewOnly) Text(stringResource(R.string.wb_tx_view_only), color = Amber, style = MaterialTheme.typography.bodySmall)

            // What happened to the last change: a refusal, or what waits for the key-up.
            note?.let {
                Text(it.text, color = if (it.refused) Color.White else Color.Black, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().background(if (it.refused) OnAirRed else Amber).padding(8.dp)
                        .testTag("txNote"))
            }
            if (status.txBlocked.isNotBlank()) TxBlockedBanner(status.txBlocked)

            val o = options
            if (o == null) {
                Text(stringResource(R.string.wb_tx_reading), color = Dim)
                return@Column
            }
            Column(if (viewOnly) Modifier.alpha(0.5f) else Modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Frequency.
                Text(stringResource(R.string.wb_tx_frequency, mhz(c.frequencyMHz)), style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.presets.forEach { p ->
                        FilterChip(selected = c.frequencyMHz?.let { abs(it - p.frequencyMHz) < 0.0005 } == true,
                            onClick = { tune(JSONObject().put("frequencyMHz", p.frequencyMHz)) },
                            label = { Text("${p.label} ${mhz(p.frequencyMHz)}") })
                    }
                }
                // On QO-100's uplink: the band plan's channels for this rate.
                val sr = c.symbolRateKs?.toInt()
                val channels = if (o.isQo100Uplink(c.frequencyMHz) && sr != null) o.qo100Channels[sr].orEmpty() else emptyList()
                if (channels.isNotEmpty()) {
                    Text(stringResource(R.string.wb_tx_qo100_channels, sr ?: 0), color = Dim, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        channels.forEach { ch ->
                            FilterChip(selected = c.frequencyMHz?.let { abs(it - ch.uplinkMHz) < 0.0005 } == true,
                                onClick = { tune(JSONObject().put("frequencyMHz", ch.uplinkMHz)) },
                                label = { Text(mhz(ch.uplinkMHz) + if (ch.preferred) " ★" else "") })
                        }
                    }
                }
                val typed = freqText.replace(',', '.').toDoubleOrNull()
                val hint = typed?.takeIf { it > 0 && o.outsideLicence(it, c.symbolRateKs ?: 0.0) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(freqText, { freqText = it }, label = { Text(stringResource(R.string.wb_frequency_mhz)) }, singleLine = true,
                        isError = hint != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("txFreq"))
                    Button(onClick = {
                        // Any number goes to the box, which judges it and says why not.
                        if (typed == null) s.say(typeFrequency)
                        else { tune(JSONObject().put("frequencyMHz", typed)); freqText = "" }
                    }, modifier = Modifier.padding(start = 8.dp).testTag("txSetFreq")) { Text(stringResource(R.string.wb_set)) }
                }
                // A hint as the number is typed; the box's txBlocked is the verdict.
                if (hint != null) {
                    Text(stringResource(R.string.wb_tx_outside_licence, mhz(hint), o.licenceRegion), color = OnAirRed, style = MaterialTheme.typography.bodySmall)
                }

                // Symbol rate: the band's rates, plus the one in use, plus any typed.
                val rates = o.symbolRatesFor(c.frequencyMHz)
                Text(stringResource(if (o.isQo100Uplink(c.frequencyMHz)) R.string.wb_rates_qo100 else R.string.wb_rates_terrestrial),
                    color = Dim, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (if (sr != null && sr !in rates) (rates + sr).sorted() else rates).forEach { r ->
                        FilterChip(selected = r == sr, onClick = { tune(JSONObject().put("symbolRateKs", r)) },
                            label = { Text("$r") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(srText, { srText = it }, label = { Text(stringResource(R.string.wb_other_rate)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("txSr"))
                    Button(onClick = {
                        val n = srText.replace(',', '.').toDoubleOrNull()
                        if (n == null) s.say(typeRate)
                        else { tune(JSONObject().put("symbolRateKs", n)); srText = "" }
                    }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.wb_set)) }
                }

                // Mode, modulation, FEC: only what the box says each has.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.modes.forEach { m ->
                        FilterChip(selected = c.mode == m, onClick = { tune(JSONObject().put("mode", m)) },
                            label = { Text(if (m == "dvbs") "DVB-S" else "DVB-S2") })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.constellations[c.mode].orEmpty().forEach { k ->
                        FilterChip(selected = c.constellation == k, onClick = { tune(JSONObject().put("constellation", k)) },
                            label = { Text(k.uppercase(Locale.ROOT)) })
                    }
                }
                Text("FEC", color = Dim, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    o.fecsFor(c.mode, c.constellation).forEach { f ->
                        FilterChip(selected = c.fec == f, onClick = { tune(JSONObject().put("fec", f)) },
                            label = { Text(f) }, modifier = Modifier.testTag("fec-$f"))
                    }
                }

                // Power: steps, as the console's stepper. Applies at once, on air too.
                val g = c.gainDb?.toInt()
                Text(stringResource(R.string.wb_tx_power, g?.toString() ?: "—", o.gainMin, o.gainMax), style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-5, -1, 1, 5).forEach { step ->
                        OutlinedButton(onClick = {
                            val now = g ?: return@OutlinedButton
                            val next = (now + step).coerceIn(o.gainMin, o.gainMax)
                            if (next != now) tune(JSONObject().put("gainDb", next))
                        }) { Text((if (step > 0) "+" else "") + step) }
                    }
                }
                if (status.onAir) {
                    Text(stringResource(R.string.wb_tx_on_air_note), color = Dim,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Why the box will refuse to key, in its own words: shown where TRANSMIT is and where the frequency is set. */
@Composable
fun TxBlockedBanner(why: String) {
    Column(Modifier.fillMaxWidth().background(OnAirRed).padding(8.dp).testTag("txBlocked")) {
        Text(stringResource(R.string.wb_cannot_transmit), color = Color.White, fontWeight = FontWeight.Bold)
        Text(why, color = Color.White)
    }
}
