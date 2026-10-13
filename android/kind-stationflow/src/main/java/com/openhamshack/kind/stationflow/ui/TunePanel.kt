package com.openhamshack.kind.stationflow.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.core.net.AuthLevel
import com.openhamshack.core.tv.Amber
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.TvItem
import com.openhamshack.core.tv.TvSection
import com.openhamshack.core.tv.TvTextDialog
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.TUNE_STEPS
import com.openhamshack.kind.stationflow.allModes
import com.openhamshack.kind.stationflow.api.RadioState
import com.openhamshack.kind.stationflow.bandOf
import com.openhamshack.kind.stationflow.formatHz
import com.openhamshack.kind.stationflow.formatStep
import com.openhamshack.kind.stationflow.parseTypedFrequency

/**
 * Everything about tuning, as rows a remote can walk: a typed frequency, the
 * step, every mode the radio has (DATA variants included), filter, VFO,
 * split and band. A panel at the side so the scope stays in view while it
 * changes. Each chip acts on OK; the current value has a green edge.
 *
 * Receive only: there is no power, PTT or transmit setting here, and there
 * is no hidden one either - the client cannot send one (StationFlowClient).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TunePanel(s: StationFlowSession, st: RadioState?, onClose: () -> Unit, onSignIn: (() -> Unit)?, modifier: Modifier) {
    var typing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val chosen by s.step.collectAsState()
    val step = chosen ?: s.effectiveStep()
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    Column(
        modifier.fillMaxHeight().width(640.dp).background(Color(0xFF101418))
            .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.tune_title), fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("${formatHz(st?.freqHz)}  ${st?.shownMode ?: ""}", fontSize = 26.sp, fontFamily = FontFamily.Monospace)
        if (st?.auth?.effective == AuthLevel.View) {
            Text(stringResource(R.string.tune_view_only),
                color = Amber, fontSize = 16.sp)
            onSignIn?.let { TvItem(onClick = it) { Text(stringResource(R.string.tune_sign_in), fontSize = 18.sp) } }
        }

        TvSection(stringResource(R.string.tune_frequency))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvItem(onClick = { typing = true }, focus = first) { Text(stringResource(R.string.tune_type_frequency), fontSize = 18.sp) }
        }
        TvSection(stringResource(R.string.tune_step))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TUNE_STEPS.forEach { v ->
                TvItem(onClick = { s.setStep(v) }, selected = v == step) { Text(formatStep(v), fontSize = 16.sp) }
            }
        }
        if (st != null) {
            TvSection(stringResource(R.string.chip_mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                allModes(st.modes, st.dataModes).forEach { m ->
                    TvItem(onClick = { s.setMode(m) }, selected = m == st.shownMode) { Text(m, fontSize = 16.sp) }
                }
            }
            TvSection(stringResource(R.string.chip_filter))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FIL1", "FIL2", "FIL3").forEach { f ->
                    TvItem(onClick = { s.setFilter(f) }, selected = f == st.filter) { Text(f, fontSize = 16.sp) }
                }
            }
            TvSection(stringResource(R.string.tune_vfo_split))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvItem(onClick = { s.setVfo("VFOA") }, selected = st.vfo == "VFOA") { Text("VFO A", fontSize = 16.sp) }
                TvItem(onClick = { s.setVfo("VFOB") }, selected = st.vfo == "VFOB") { Text("VFO B", fontSize = 16.sp) }
                TvItem(onClick = { s.setSplit(!st.split) }, selected = st.split) {
                    Text(stringResource(if (st.split) R.string.tune_split_on else R.string.tune_split_off), fontSize = 16.sp)
                }
            }
            if (st.bands.isNotEmpty()) {
                TvSection(stringResource(R.string.chip_band))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val here = bandOf(st.bands, st.freqHz)
                    st.bands.forEach { b ->
                        TvItem(onClick = { s.tuneTo(b.hz) }, selected = b == here) { Text(b.name, fontSize = 16.sp) }
                    }
                }
            }
        }
        Text(stringResource(R.string.tune_back_closes), color = Dim, fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp))
        TvItem(onClick = onClose) { Text(stringResource(R.string.tune_close), fontSize = 18.sp) }
    }

    if (typing) {
        // Said from the dialog's callback, where stringResource cannot be called.
        val typeFrequency = stringResource(R.string.tune_type_frequency_error)
        TvTextDialog(
            stringResource(R.string.tune_frequency), stringResource(R.string.tune_frequency_help),
            KeyboardType.Decimal, onDismiss = { typing = false },
        ) { text, fail ->
            val hz = parseTypedFrequency(text)
            if (hz == null) fail(typeFrequency)
            else { s.tuneTo(hz); typing = false }
        }
    }
}
