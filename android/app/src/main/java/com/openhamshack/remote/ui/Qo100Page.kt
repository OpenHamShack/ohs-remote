package com.openhamshack.remote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.R
import com.openhamshack.remote.api.Qo100Signal
import com.openhamshack.remote.api.Status

/** The transponder's wideband uplink, MHz: where our own signal would land on the spectrum. */
private val QO100_UPLINK = 2401.0..2410.0

/**
 * The QO-100 page of a tablet or phone: the wideband transponder live
 * (spectrum and waterfall, from the box's reading of BATC's monitor), the
 * signals found on it listed beneath, and a tap on either - or on the
 * graph - tuning the receiver to it as the box console's QO-100 page does
 * (ui/Qo100Tune.kt). Receive tuning on the Receive tab is terrestrial only;
 * everything through the LNB is here.
 *
 * Watching needs no PIN (/api/qo100 is open, like status); tuning does, and
 * without it a tap says why (BoxSession.act), as everywhere else.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Qo100Page(s: BoxSession, status: Status?, modifier: Modifier = Modifier) {
    val qo by s.qo100.collectAsStateWithLifecycle()
    val wfVersion by s.qo100WfVersion.collectAsStateWithLifecycle()
    // The receiver this page tunes: the heard one until another is chosen,
    // as the console's page follows the receiver last tuned from it.
    var qoRx by remember { mutableIntStateOf(0) }
    var offer by remember { mutableStateOf<Pair<Double, Int>?>(null) }
    val rx = status?.receivers?.firstOrNull { it.id == qoRx }
        ?: status?.receivers?.firstOrNull { it.heard } ?: status?.rx
    val qoLo = status?.config?.qo100LnbMHz ?: 9750.0
    val viewOnly = status?.auth?.viewOnly ?: true
    val look = if (viewOnly) Modifier.alpha(0.6f) else Modifier
    val signals = qo?.signals.orEmpty()
    val lo = qo?.startMHz ?: 10490.5
    val hi = qo?.stopMHz ?: 10499.5

    // Said from a tap, where stringResource cannot be called.
    val resources = LocalResources.current
    fun tune(f: Double, sr: Int) {
        val r = rx ?: return
        s.tune(qo100Tune(r, f, sr, qoLo))
        if (status?.auth?.viewOnly == false) s.say(resources.getString(R.string.wb_qo100_tuning, mhz(f), sr))
    }
    fun tune(sig: Qo100Signal) = tune(sig.freqMHz, sig.tuneSymbolRateKs)

    val txF = status?.config?.frequencyMHz
    val txSr = status?.config?.symbolRateKs ?: 0.0
    val txDown = txF?.takeIf { it in QO100_UPLINK }?.let { it + (qo?.uplinkOffsetMHz ?: 8089.5) }

    Column(modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val err = qo != null && qo!!.error.isNotBlank() && !qo!!.live
        Text(
            qo100StateText(
                qo?.let { q -> qo100State(q.live, q.error, q.age, signals.size, q.outOfBand, true) }
                    ?: qo100State(false, "", null, 0, false, false),
                tapHint = true,
            ),
            color = if (err) Amber else Dim, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("qoState"),
        )
        Qo100Spectrum(
            qo, qo100RxMarker(rx), Modifier.fillMaxWidth().height(200.dp).testTag("qoSpectrum"),
            txDownMHz = txDown, txHalfMHz = txSr * 1.35 / 2000,
            onTap = { f -> qo100SignalAt(signals, f)?.let { tune(it) } },
        )
        Qo100WaterfallView(
            s.qo100Waterfall, wfVersion, lo, hi, qo100RxMarker(rx), Modifier.fillMaxWidth().height(110.dp),
            // Nothing found where the trace was: offer that frequency rather
            // than tune to it unasked, as the console does.
            onTap = { f -> qo100SignalAt(signals, f)?.let { tune(it) } ?: rx?.let { offer = qo100Offer(f, it) } },
        )
        // The credit BATC's data is due, and where it is from.
        Text(
            qo100Credit(qo),
            color = Dim, fontSize = 12.sp,
        )

        if ((status?.receivers?.size ?: 1) > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.wb_qo100_tune_receiver), color = Dim, modifier = Modifier.align(Alignment.CenterVertically))
                status!!.receivers.filter { it.enabled }.forEach { x ->
                    FilterChip(selected = x.id == rx?.id, onClick = { qoRx = x.id },
                        label = { Text("${x.id} · ${x.tuner}" + if (x.heard) " ♪" else "") })
                }
            }
        }
        rx?.let { r ->
            Text(
                if (onQo100(r)) stringResource(R.string.wb_qo100_through_lnb, ks(r.offsetMHz),
                    mhz((r.frequencyMHz ?: 0.0) - (r.offsetMHz ?: 0.0)), lockText(r))
                else stringResource(R.string.wb_qo100_terrestrial),
                color = if (r.locked == true && onQo100(r)) LockGreen else Dim, style = MaterialTheme.typography.bodySmall,
            )
        }

        Text(stringResource(R.string.wb_qo100_on_transponder), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
        if (signals.isEmpty()) {
            Text(stringResource(if (qo?.live == true) R.string.wb_qo100_nothing_found else R.string.wb_qo100_no_signals_yet),
                color = Dim, style = MaterialTheme.typography.bodySmall)
        }
        Column(look, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            signals.forEach { sig ->
                val on = qo100TunedOn(sig, status?.receivers.orEmpty())
                SignalRow(sig, on, multi = (status?.receivers?.size ?: 1) > 1) { tune(sig) }
            }
        }
        // The beacon is always worth a button, found or not: it is how an
        // LNB's drift is checked, and the one fixed signal.
        if (signals.none { it.beacon }) {
            TextButton(onClick = { tune(QO100_BEACON_MHZ, QO100_BEACON_KS) }, modifier = look) {
                Text(stringResource(R.string.wb_qo100_receive_beacon, mhz(QO100_BEACON_MHZ), QO100_BEACON_KS))
            }
        }
    }

    offer?.let { (f, sr) ->
        AlertDialog(
            onDismissRequest = { offer = null },
            title = { Text(stringResource(R.string.wb_qo100_offer_title)) },
            text = { Text(stringResource(R.string.wb_qo100_offer_text, mhz(f))) },
            confirmButton = { Button(onClick = { offer = null; tune(f, sr) }) { Text(stringResource(R.string.wb_qo100_offer_receive, mhz(f), sr)) } },
            dismissButton = { TextButton(onClick = { offer = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun SignalRow(sig: Qo100Signal, tunedOn: List<Int>, multi: Boolean, onClick: () -> Unit) {
    val on = tunedOn.isNotEmpty()
    val shape = RoundedCornerShape(8.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (on) Color(0xFF16301C) else MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(2.dp, if (on) LockGreen else if (sig.overPower) OnAirRed else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("qoSignal"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                downlinkText(sig.freqMHz, localeTag()).let { f ->
                    if (sig.beacon) stringResource(R.string.qo100_beacon_at, f) else f
                },
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                listOfNotNull(
                    if (sig.symbolRateKs != sig.tuneSymbolRateKs && !sig.beacon)
                        stringResource(R.string.wb_qo100_rate_measured, sig.tuneSymbolRateKs, sig.symbolRateKs)
                    else stringResource(R.string.wb_qo100_rate, sig.tuneSymbolRateKs),
                    stringResource(R.string.wb_qo100_above_floor, db(sig.levelDb)),
                    if (sig.overPower) stringResource(R.string.wb_qo100_over_power) else null,
                ).joinToString(" · "),
                color = if (sig.overPower) OnAirRed else Dim, style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            when {
                // The receivers' numbers are data: listed as numbers, not words.
                on && multi -> stringResource(R.string.wb_qo100_receiving_on, tunedOn.joinToString(", "))
                on -> stringResource(R.string.wb_qo100_receiving)
                else -> stringResource(R.string.wb_tab_receive)
            },
            color = if (on) LockGreen else MaterialTheme.colorScheme.primary,
        )
    }
}

/** The picture beside the QO-100 page, small, so a tune is seen to lock. */
@Composable
fun Qo100Side(s: BoxSession, status: Status?, link: com.openhamshack.remote.Link) {
    Card(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(200.dp).background(Color.Black)) {
            VideoSurface(s)
            val player by s.player.state.collectAsStateWithLifecycle()
            PictureOverlay(status, player, compact = true)
        }
    }
    TransmitPanel(s, status, link)
    TxSettingsPanel(s, status)
}
