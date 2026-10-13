package com.openhamshack.remote.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openhamshack.remote.R
import com.openhamshack.remote.api.Qo100
import com.openhamshack.remote.api.Qo100Signal
import com.openhamshack.remote.api.Qo100Waterfall

// The QO-100 spectrum and waterfall, drawn as the box console's QO-100
// page draws them (qo100.js drawSpectrum / drawWaterfall): the band plan's
// segments shaded, the spectrum filled, each detected signal boxed and
// labelled, the receiver's frequency as a green line, our own uplink's
// footprint in orange. Shared by the tablet page and the TV screen.

private val SpecFill = Color(0xFF2B4A7A)
private val SpecLine = Color(0xFF7FB2FF)
private val Segment = Color(0xFF1A2333)
private val TxOrange = Color(0xFFE67814)
private val Focus = Color(0xFF4FC3F7)

/**
 * The spectrum. [rxMHz] is the receiver's downlink frequency when it is on
 * QO-100 (null otherwise); [txDownMHz]/[txHalfMHz] where our own uplink
 * would come down; [focused] a signal to ring (the TV's list focus).
 * [onTap] gets the downlink frequency under a tap.
 */
@Composable
fun Qo100Spectrum(
    qo: Qo100?,
    rxMHz: Double?,
    modifier: Modifier = Modifier,
    txDownMHz: Double? = null,
    txHalfMHz: Double = 0.0,
    focused: Qo100Signal? = null,
    labelSize: Float = 12f,
    onTap: ((Double) -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer()
    // The words drawn on the canvas, read here: a DrawScope is not composable.
    val txWord = stringResource(R.string.wb_graph_tx)
    val beaconWord = stringResource(R.string.wb_graph_beacon)
    val overPowerWord = stringResource(R.string.wb_graph_over_power)
    val resources = LocalResources.current
    val lo = qo?.startMHz ?: 10490.5
    val hi = qo?.stopMHz ?: 10499.5
    val spec = qo?.spectrum.orEmpty()
    val sigs = qo?.signals.orEmpty()
    Canvas(
        modifier.background(Color(0xFF0B1020)).then(
            if (onTap == null) Modifier else Modifier.pointerInput(lo, hi) {
                detectTapGestures { p -> onTap(lo + p.x / size.width * (hi - lo)) }
            },
        ),
    ) {
        val lab = labelSize.sp.toPx()
        val top = lab * 3.4f                 // room for two rows of labels above the tallest peak
        val bottom = size.height - lab * 1.6f  // MHz scale under
        val x = { f: Double -> ((f - lo) / (hi - lo) * size.width).toFloat() }
        val maxV = maxOf(12f, (spec.maxOrNull() ?: 0f) + 1f)
        val minV = -2f
        val y = { v: Float -> top + (maxV - v) / (maxV - minV) * (bottom - top) }

        // The band plan: beacon only below 10492.5, narrow only above 10497.
        drawRect(Segment, Offset(x(lo), top), Size(x(QO100_BEACON_EDGE_MHZ) - x(lo), bottom - top))
        val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
        for (f in listOf(QO100_BEACON_EDGE_MHZ, QO100_NARROW_EDGE_MHZ)) {
            drawLine(Dim, Offset(x(f), top), Offset(x(f), bottom), 1.5f, pathEffect = dash)
        }
        val scaleStyle = TextStyle(color = Dim, fontSize = labelSize.sp)
        var f = kotlin.math.ceil(lo)
        while (f <= hi) {
            val t = measurer.measure(f.toInt().toString(), scaleStyle)
            drawText(t, topLeft = Offset((x(f) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), bottom + lab * 0.2f))
            f += 1.0
        }

        if (spec.isNotEmpty()) {
            val path = Path().apply {
                moveTo(x(lo), bottom)
                spec.forEachIndexed { i, v -> lineTo(x(lo + (i + 0.5) / spec.size * (hi - lo)), y(v)) }
                lineTo(x(hi), bottom)
                close()
            }
            drawPath(path, SpecFill)
            drawPath(path, SpecLine, style = Stroke(2f))
        }

        if (txDownMHz != null && txDownMHz in lo..hi) {
            val l = x(txDownMHz - txHalfMHz)
            val w = maxOf(3f, x(txDownMHz + txHalfMHz) - l)
            drawRect(TxOrange.copy(alpha = 0.15f), Offset(l, top), Size(w, bottom - top))
            drawRect(TxOrange, Offset(l, top), Size(w, bottom - top), style = Stroke(2f))
            val t = measurer.measure(txWord, TextStyle(color = TxOrange, fontSize = labelSize.sp, fontWeight = FontWeight.Bold))
            drawText(t, topLeft = Offset(x(txDownMHz) - t.size.width / 2f, bottom - t.size.height - 4f))
        }

        if (rxMHz != null && rxMHz in lo..hi) {
            drawLine(LockGreen, Offset(x(rxMHz), top - lab * 1.2f), Offset(x(rxMHz), bottom), 4f)
        }

        // Each label goes as low as it can over its signal without touching
        // one already drawn, stepping up a line at a time (three at most):
        // neighbouring stations' labels otherwise run into one line. By
        // rectangle, not by row - signals of different heights put row 0 of
        // one level with row 1 of another.
        val placed = mutableListOf<androidx.compose.ui.geometry.Rect>()
        sigs.forEach { s ->
            val x0 = x(s.lowMHz)
            val x1 = x(s.highMHz)
            val yt = y(s.levelDb.toFloat())
            val ink = if (s.overPower) OnAirRed else Color.White
            val ring = s == focused
            drawRect(
                if (ring) Focus.copy(alpha = 0.25f) else Color.Transparent,
                Offset(x0, yt), Size(maxOf(4f, x1 - x0), bottom - yt),
            )
            drawRect(
                if (ring) Focus else ink, Offset(x0, yt), Size(maxOf(4f, x1 - x0), bottom - yt),
                style = Stroke(if (ring) 4f else 2f, pathEffect = if (ring) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
            )
            val t = measurer.measure(qo100GraphLabel(s, beaconWord, overPowerWord) { sr, f -> resources.getString(R.string.wb_graph_rate_at, sr, f) }, TextStyle(color = if (ring) Focus else ink, fontSize = labelSize.sp, fontWeight = FontWeight.Bold))
            val half = t.size.width / 2f + 4f
            val cx = ((x0 + x1) / 2f).coerceIn(half, size.width - half)
            val h = t.size.height + 2f
            var ty = maxOf(0f, yt - lab * 0.3f - t.size.height)
            var box = androidx.compose.ui.geometry.Rect(cx - half, ty, cx + half, ty + h)
            var tries = 0
            while (tries < 3 && placed.any { it.overlaps(box) }) {
                ty = maxOf(0f, placed.filter { it.overlaps(box) }.minOf { it.top } - h)
                box = androidx.compose.ui.geometry.Rect(cx - half, ty, cx + half, ty + h)
                tries++
            }
            placed += box
            drawText(t, topLeft = Offset(cx - t.size.width / 2f, ty))
        }
    }
}

/**
 * The waterfall, newest second at the top, stretched to the spectrum's
 * width so a trace sits under its peak. [version] is only there to redraw
 * when the rows change (the pixels are updated in place).
 */
@Composable
fun Qo100WaterfallView(
    wf: Qo100Waterfall,
    version: Int,
    lo: Double,
    hi: Double,
    rxMHz: Double?,
    modifier: Modifier = Modifier,
    onTap: ((Double) -> Unit)? = null,
) {
    val bmp = remember(version) {
        if (wf.width == 0 || wf.height == 0) null
        else Bitmap.createBitmap(wf.pixels, wf.width, wf.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    BoxWithConstraints(
        modifier.background(Color(Qo100Waterfall.BACKGROUND)).then(
            if (onTap == null) Modifier else Modifier.pointerInput(lo, hi) {
                detectTapGestures { p -> onTap(lo + p.x / size.width * (hi - lo)) }
            },
        ),
    ) {
        if (bmp != null) {
            Image(bmp, stringResource(R.string.wb_waterfall_description), Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
        }
        if (rxMHz != null && rxMHz in lo..hi) {
            val at: Dp = maxWidth * ((rxMHz - lo) / (hi - lo)).toFloat()
            Box(Modifier.offset(x = at - 1.dp).width(2.dp).height(maxHeight).background(LockGreen))
        }
    }
}

/** The receiver's downlink frequency for the green line: only while it is on QO-100. */
fun qo100RxMarker(r: com.openhamshack.remote.api.Receiver?): Double? =
    r?.takeIf { onQo100(it) }?.frequencyMHz
