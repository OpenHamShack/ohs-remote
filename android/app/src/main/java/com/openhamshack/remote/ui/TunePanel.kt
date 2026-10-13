package com.openhamshack.remote.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.R
import com.openhamshack.remote.api.Status
import kotlin.math.abs

/**
 * Receive tuning: the console's receiver window (receivers.js) reduced to
 * what is needed from a phone - which receiver, a band preset or a typed
 * frequency, and the band's symbol rates. Every change is POST
 * /api/rx/tune, as the console does it.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TunePanel(s: BoxSession, status: Status?) {
    if (status == null) return
    var tuneId by remember { mutableIntStateOf(1) }
    val r = status.receivers.firstOrNull { it.id == tuneId } ?: status.rx
    var freqText by remember { mutableStateOf("") }
    var srText by remember { mutableStateOf("") }
    // Dimmed, not disabled: a tap still says why nothing happens (the
    // console's view-only rule), which a greyed-out control never does.
    val look = if (status.auth.viewOnly) Modifier.alpha(0.5f) else Modifier
    val qoLo = status.config.qo100LnbMHz
    // Said from onClick, where stringResource cannot be called.
    val typeFrequency = stringResource(R.string.wb_rx_type_frequency)
    val typeRate = stringResource(R.string.wb_type_rate)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).then(look), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.wb_tab_receive), style = MaterialTheme.typography.titleMedium)
            if (status.receivers.size > 1) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    status.receivers.forEach { x ->
                        FilterChip(
                            selected = x.id == tuneId, onClick = { tuneId = x.id },
                            label = { Text("${x.id} · ${x.tuner}" + if (x.heard) " ♪" else "") },
                            enabled = x.enabled,
                        )
                    }
                }
                if (!r.heard) TextButton(onClick = { s.hear(r.id) }) { Text(stringResource(R.string.wb_hear_receiver, r.id)) }
            }
            Text(tuningText(r), style = MaterialTheme.typography.bodyLarge)
            Text(lockText(r), color = if (r.locked == true) LockGreen else Amber, style = MaterialTheme.typography.bodySmall)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RX_PRESETS.forEach { p ->
                    FilterChip(
                        selected = r.frequencyMHz?.let { abs(it - p.mhz) < 0.001 } == true,
                        onClick = {
                            s.tune(r.id, frequencyMHz = p.mhz, offsetMHz = 0.0)
                        },
                        label = { Text(p.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    freqText, { freqText = it }, label = { Text(stringResource(R.string.wb_frequency_mhz)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).testTag("freq"),
                )
                Button(onClick = {
                    val n = freqText.replace(',', '.').toDoubleOrNull()
                    if (n == null || n <= 0) s.say(typeFrequency)
                    else {
                        val off = rxOffsetFor(n, r.offsetMHz, qoLo)
                        s.tune(r.id, frequencyMHz = n, offsetMHz = off,
                            symbolRateKs = rateForFrequency(n, off, r.symbolRateKs)?.toDouble())
                        freqText = ""
                    }
                }, modifier = Modifier.padding(start = 8.dp).testTag("tune")) { Text(stringResource(R.string.wb_tune)) }
            }

            val rates = srRatesFor(r.offsetMHz)
            Text(stringResource(if ((r.offsetMHz ?: 0.0) > 0) R.string.wb_rates_qo100 else R.string.wb_rates_terrestrial),
                color = Dim, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val current = r.symbolRateKs?.toInt()
                (if (current != null && current !in rates) (rates + current).sorted() else rates).forEach { sr ->
                    FilterChip(selected = sr == current, onClick = { s.tune(r.id, symbolRateKs = sr.toDouble()) },
                        label = { Text("$sr") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    srText, { srText = it }, label = { Text(stringResource(R.string.wb_other_rate)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = {
                    val n = srText.toDoubleOrNull()
                    if (n == null || n <= 0) s.say(typeRate)
                    else { s.tune(r.id, symbolRateKs = n); srText = "" }
                }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.wb_set)) }
            }
        }
    }
}
