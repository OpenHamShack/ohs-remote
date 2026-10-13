package com.openhamshack.kind.stationflow.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openhamshack.core.tv.Amber
import com.openhamshack.core.tv.Dim
import com.openhamshack.core.tv.TvFocus
import com.openhamshack.kind.stationflow.R
import com.openhamshack.kind.stationflow.Waterfall
import kotlin.math.roundToInt

/**
 * The band scope across the screen: the newest sweep as a trace over the
 * waterfall, the dial marked on both (amber while a retune is on its way),
 * and the edges and centre labelled in MHz. Not focusable: it is for looking
 * at, and the dial above it does the tuning. Give it a weight: the
 * waterfall fills the height it is given.
 */
@Composable
internal fun ScopeView(
    waterfall: Waterfall,
    dialHz: Long?,
    pending: Boolean,
    message: String?,
    modifier: Modifier = Modifier,
    traceHeight: Dp = 60.dp,
) {
    val version by waterfall.version.collectAsState()
    val pixels = remember { arrayOfNulls<IntArray>(1) }
    val bitmap = remember { arrayOfNulls<Bitmap>(1) }
    // Read here as well as in the Canvases: the edge labels below are
    // composed, not drawn, and without this read they kept the first sweep's
    // frequencies for ever (seen after a band change). Ten recompositions a
    // second of a few Texts is nothing.
    @Suppress("UNUSED_VARIABLE") val tick = version
    val s = waterfall.latest
    Column(modifier.background(Color(0xFF05070A))) {
        Canvas(Modifier.fillMaxWidth().height(traceHeight)) {
            version   // read: redraw on every sweep
            val sp = waterfall.latest ?: return@Canvas
            val w = size.width; val h = size.height
            // Grid: quarters of the span, and S-ish level lines.
            for (i in 1..3) drawLine(Color(0xFF1C2630), Offset(w * i / 4, 0f), Offset(w * i / 4, h))
            for (i in 1..3) drawLine(Color(0xFF1C2630), Offset(0f, h * i / 4), Offset(w, h * i / 4))
            if (sp.data.isNotEmpty()) {
                val path = Path()
                val n = sp.data.size
                for (i in 0 until n) {
                    val x = w * i / (n - 1).coerceAtLeast(1)
                    val y = h - h * (sp.data[i].toFloat() / sp.max).coerceIn(0f, 1f)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
                drawPath(fill, Color(0x3329B6F6))
                drawPath(path, Color(0xFF81D4FA), style = Stroke(width = 2f))
            }
            marker(dialHz, sp.startHz, sp.endHz, pending)
        }
        // The waterfall takes whatever height the screen has left, so the
        // layout fits a 1080p TV and a 720p one without clipping the strip
        // of decodes below it.
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            version
            val w = waterfall.width
            if (w == 0) return@Canvas
            val rows = waterfall.rows
            val px = pixels[0]?.takeIf { it.size == w * rows } ?: IntArray(w * rows).also { pixels[0] = it }
            val bmp = bitmap[0]?.takeIf { it.width == w && it.height == rows }
                ?: Bitmap.createBitmap(w, rows, Bitmap.Config.ARGB_8888).also { bitmap[0] = it }
            waterfall.copyInto(px)
            bmp.setPixels(px, 0, w, 0, 0, w, rows)
            drawImage(bmp.asImageBitmap(), IntOffset.Zero, IntSize(w, rows), IntOffset.Zero,
                IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
            val sp = waterfall.latest ?: return@Canvas
            marker(dialHz, sp.startHz, sp.endHz, pending, alpha = 0.6f)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
            val l = s?.let { mhzShort(it.startHz) } ?: ""
            val c = s?.let { mhzShort((it.startHz + it.endHz) / 2) } ?: ""
            val r = s?.let { mhzShort(it.endHz) } ?: ""
            Text(l, color = Dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
            // The centre, the scope's mode (data) and whether the dial is off the scope's edge.
            Text(listOfNotNull(c, s?.mode, if (s?.outOfRange == true) stringResource(R.string.scope_out_of_range) else null)
                .joinToString("  ·  "),
                color = Dim, fontSize = 13.sp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Text(r, color = Dim, fontSize = 13.sp) }
        }
        if (s == null) {
            Text(message ?: stringResource(R.string.scope_waiting), color = Dim, fontSize = 15.sp,
                modifier = Modifier.padding(8.dp))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.marker(
    hz: Long?, start: Long, end: Long, pending: Boolean, alpha: Float = 1f,
) {
    if (hz == null || end <= start || hz < start || hz > end) return
    val x = size.width * (hz - start).toFloat() / (end - start)
    drawLine((if (pending) Amber else TvFocus).copy(alpha = alpha), Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
}

private fun mhzShort(hz: Long) = "%.3f".format(hz / 1e6)
