package com.openhamshack.kind.stationflow.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.openhamshack.core.tv.Amber
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.LocalTvInert
import androidx.compose.runtime.CompositionLocalProvider
import com.openhamshack.core.tv.TvItem
import com.openhamshack.core.tv.TvSafeH
import com.openhamshack.core.tv.TvSafeV
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.StationFlowSession
import com.openhamshack.kind.stationflow.api.SstvPicture
import com.openhamshack.kind.stationflow.formatHz
import java.time.Instant
import java.time.ZoneOffset

/**
 * The SSTV pictures the module has kept, newest first, with the one coming
 * in (if any) as the first tile, growing as it arrives. OK opens a picture
 * full screen. Whether a new picture takes over the screen by itself is the
 * one setting here.
 */
@Composable
internal fun SstvScreen(s: StationFlowSession, autoShow: Boolean, setAutoShow: (Boolean) -> Unit, onClose: () -> Unit) {
    val sstv by s.sstv.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    BackHandler(enabled = open == null) { onClose() }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }

    CompositionLocalProvider(LocalTvInert provides (open != null)) {
    Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = TvSafeH, vertical = TvSafeV)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("SSTV", fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TvItem(onClick = { setAutoShow(!autoShow) }, focus = first, selected = autoShow) {
                Text(stringResource(if (autoShow) R.string.sstv_auto_on else R.string.sstv_auto_off), fontSize = 16.sp)
            }
            if (sstv.decoderRunning == false) TvItem(onClick = { s.startSstv() }) { Text(stringResource(R.string.sstv_start), fontSize = 16.sp) }
        }
        Text(
            when {
                sstv.receiving -> sstv.progress?.let { stringResource(R.string.sstv_receiving_progress, (it * 100).toInt()) }
                    ?: stringResource(R.string.sstv_receiving)
                sstv.pictures.isEmpty() -> stringResource(R.string.sstv_none)
                else -> pluralStringResource(R.plurals.sstv_count, sstv.pictures.size, sstv.pictures.size)
            },
            color = if (sstv.receiving) Amber else Dim, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            sstv.live?.let { live ->
                item(key = "live") {
                    TvItem(onClick = {}) {
                        Column {
                            Image(live.asImageBitmap(), stringResource(R.string.sstv_live_description), Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                                contentScale = ContentScale.Fit)
                            Text(stringResource(R.string.sstv_coming_in), color = Amber, fontSize = 14.sp)
                        }
                    }
                }
            }
            items(sstv.pictures, key = { it.id }) { p ->
                TvItem(onClick = { open = p.id }) {
                    Column {
                        Thumb(s, p, Modifier.fillMaxWidth().aspectRatio(4f / 3f))
                        Text(caption(p), color = Dim, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
    }
    open?.let { SstvViewer(s, sstv.pictures, it, onClose = { open = null }) }
}

@Composable
private fun Thumb(s: StationFlowSession, p: SstvPicture, modifier: Modifier) {
    val bmp by produceState<Bitmap?>(null, p.id) { value = s.picture(p) }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), p.mode, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: Text(p.mode, color = Dim)
    }
}

/**
 * One picture as large as the screen allows. Left/right steps through the
 * pictures; Back returns to wherever it was opened from.
 */
@Composable
internal fun SstvViewer(s: StationFlowSession, pictures: List<SstvPicture>, startId: String, onClose: () -> Unit) {
    var index by remember(startId) { mutableStateOf(pictures.indexOfFirst { it.id == startId }.coerceAtLeast(0)) }
    val focus = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(60); runCatching { focus.requestFocus() } }
    val p = pictures.getOrNull(index)
    val bmp by produceState<Bitmap?>(null, p?.id) { value = p?.let { s.picture(it) } }
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { index = (index - 1).coerceAtLeast(0); true }
                    Key.DirectionRight -> { index = (index + 1).coerceAtMost(pictures.size - 1); true }
                    Key.DirectionCenter, Key.Enter, Key.DirectionUp, Key.DirectionDown -> true
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let { Image(it.asImageBitmap(), p?.mode, Modifier.fillMaxSize().padding(vertical = TvSafeV), contentScale = ContentScale.Fit) }
            ?: Text(stringResource(if (p == null) R.string.sstv_no_picture else R.string.sstv_loading), color = Dim, fontSize = 20.sp)
        if (p != null) {
            Text(
                stringResource(R.string.sstv_viewer_caption, caption(p), index + 1, pictures.size),
                color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
                    .background(Color(0xB0000000), RoundedCornerShape(8.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun caption(p: SstvPicture): String {
    val t = p.atS?.let { Instant.ofEpochMilli((it * 1000).toLong()).atZone(ZoneOffset.UTC) }
    return listOfNotNull(
        p.mode.ifBlank { null },
        t?.let { stringResource(R.string.sstv_time, it.hour, it.minute, it.dayOfMonth, it.monthValue) },
        p.freqHz?.let { formatHz(it) },
        if (!p.complete) stringResource(R.string.sstv_incomplete) else null,
    ).joinToString(" · ")
}
