package com.openhamshack.kind.stationflow.radio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openhamshack.core.tv.Amber
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.TvItem
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.api.DvCall
import com.openhamshack.kind.stationflow.api.DvState
import com.openhamshack.kind.stationflow.api.RadioState

/*
 * D-STAR in the decode window (HeardScreen's DV tab): the radio's RX CALL
 * SIGN display - caller and suffix large, the repeater it came through, the
 * RX message, how long ago - and the last overs under it. Brian (10 October
 * 2026) asked for the caller on the Remote, and then for decoded text to
 * live in the decode window rather than along the bottom of the face, so the
 * face keeps its height; CW went there too.
 */

/** Whether the radio is in DV and the module reports D-STAR at all: the decode window opens on DV then. */
fun showsDv(st: RadioState?): Boolean = st?.dv != null && st.mode?.uppercase() == "DV"

/**
 * How long since [heardAt] (Unix seconds) at [now] (milliseconds), in
 * seconds; null for a call with no time.
 */
fun agoSeconds(heardAt: Double?, now: Long): Long? = heardAt?.let { ((now / 1000.0) - it).toLong().coerceAtLeast(0) }

@Composable
internal fun ago(s: Long): String = when {
    s < 60 -> stringResource(R.string.dv_seconds_ago, s)
    s < 3600 -> stringResource(R.string.dv_minutes_ago, s / 60)
    else -> stringResource(R.string.dv_hours_ago, s / 3600)
}

/**
 * The latest caller, as the radio's RX CALL SIGN screen shows it: live
 * (white) while the call is coming in and dimmed after it, as the contract
 * asks; "Listening for D-STAR" until anybody has called.
 */
@Composable
internal fun DvNow(dv: DvState) {
    val last = dv.last
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF0A0F14), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (last == null) {
            Text(stringResource(R.string.dv_listening), color = Dim, fontSize = 22.sp)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Text(last.callLine, color = if (last.receiving) Color.White else Color.White.copy(alpha = 0.65f),
                fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            last.via?.let { Text(stringResource(R.string.dv_via, it), color = Amber, fontSize = 22.sp) }
            agoSeconds(last.heardAt, System.currentTimeMillis())?.let { Text(ago(it), color = Dim, fontSize = 18.sp) }
        }
        last.message?.let { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** One earlier over, a row a TV can scroll to. OK does nothing: DV is on its channel already. */
@Composable
internal fun DvRow(c: DvCall, now: Long) {
    TvItem(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        Text(c.callLine, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.35f))
        Column(Modifier.weight(0.5f)) {
            c.via?.let { Text(stringResource(R.string.dv_via, it), color = Amber, fontSize = 16.sp) }
            c.message?.let { Text(it, fontSize = 18.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        agoSeconds(c.heardAt, now)?.let { Text(ago(it), color = Dim, fontSize = 16.sp) }
    }
}
