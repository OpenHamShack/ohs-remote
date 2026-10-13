package com.openhamshack.remote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.EngineSession
import com.openhamshack.remote.shack.PanelPrefs
import com.openhamshack.remote.shack.SstvPicture

// SSTV pictures on the NB/Radio kinds: painted line by line as the engine
// decodes them, and the session's last few kept on the device (the server
// has no route to fetch a finished picture). Receive only.

/** The engine's format id in words ("martinm1" is "Martin 1"); an unknown id as it came. */
@Composable
internal fun sstvFormat(fmt: String): String = when (fmt) {
    "martinm1" -> stringResource(R.string.sstv_fmt_martinm1)
    "scottie_s1" -> stringResource(R.string.sstv_fmt_scottie_s1)
    "robot36" -> stringResource(R.string.sstv_fmt_robot36)
    "pd120" -> stringResource(R.string.sstv_fmt_pd120)
    else -> fmt
}

/** "Line 120 of 256", or that it is complete. */
@Composable
internal fun sstvProgress(p: SstvPicture): String =
    if (p.done) stringResource(R.string.sstv_done) else stringResource(R.string.sstv_progress, p.lines, p.height)

/**
 * One picture, at its own shape, redrawn whenever [version] changes: the
 * canvas takes whatever [modifier] gives it and the picture is fitted
 * inside it, letterboxed, never stretched ([SstvPicture.fit]). The bitmap
 * is made once per picture, full size from the start, and its pixels
 * copied in under the session's lock, since lines are painted on the
 * socket's thread.
 */
@Composable
internal fun SstvImage(e: EngineSession, p: SstvPicture, version: Int, modifier: Modifier = Modifier) = SstvImage(e.sstv, p, version, modifier)

/** The same for any session: [lock] is what its socket thread paints the picture under (its SstvReceiver). */
@Composable
internal fun SstvImage(lock: Any, p: SstvPicture, version: Int, modifier: Modifier = Modifier) {
    val bitmap = remember(p) { android.graphics.Bitmap.createBitmap(p.width, p.height, android.graphics.Bitmap.Config.ARGB_8888) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") version
        synchronized(lock) { bitmap.setPixels(p.pixels, 0, p.width, 0, 0, p.width, p.height) }
        val (l, t, w, h) = p.fit(size.width, size.height)
        drawImage(image, srcSize = IntSize(p.width, p.height), dstOffset = IntOffset(l.toInt(), t.toInt()),
            dstSize = IntSize(w.toInt(), h.toInt()), filterQuality = FilterQuality.Low)
    }
}

/** A small picture (gallery, phone) of height [h] and the picture's own width for it. */
internal fun Modifier.sstvThumb(p: SstvPicture, h: androidx.compose.ui.unit.Dp): Modifier = height(h).width(h * p.aspect)

/**
 * The picture panel on a TV: the picture large on the left, its format and
 * progress, the session's last pictures to look back at, and the
 * open-by-itself switch. BACK closes it. The screen beneath is
 * unfocusable while it shows (the caller holds the scope's canFocus off),
 * so the D-pad cannot tune a radio nobody can see (GUIDE 5).
 */
@Composable
internal fun TvSstvPanel(e: EngineSession, onClose: () -> Unit) {
    val u by e.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var shown by remember { mutableStateOf<SstvPicture?>(null) }
    val first = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
    // A new picture starting takes over from one being looked back at.
    LaunchedEffect(u.sstvStarts) { shown = null }
    val (current, gallery) = synchronized(e.sstv) { e.sstv.current to e.sstv.gallery }
    val p = shown ?: current
    Row(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = TvSafeH, vertical = TvSafeV),
        horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (p != null) SstvImage(e, p, u.sstvVersion, Modifier.fillMaxSize())
            else Text(stringResource(R.string.sstv_none), color = Dim, fontSize = 20.sp)
        }
        Column(Modifier.width(420.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sstv_title), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (p != null) {
                Text(sstvFormat(p.fmt), fontSize = 24.sp, color = TvFocus)
                Text(sstvProgress(p), fontSize = 20.sp, color = if (p.done) LockGreen else Amber)
            }
            if (gallery.isNotEmpty()) {
                TvSection(stringResource(R.string.sstv_gallery))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    gallery.forEach { g ->
                        TvItem(onClick = { shown = g }, selected = g == p) { SstvImage(e, g, u.sstvVersion, Modifier.sstvThumb(g, 72.dp)) }
                    }
                }
            }
            // The one setting for every decoder's panel (PanelPrefs), here too
            // because a picture jumping up is where someone first wants it off.
            TvItem(onClick = { val on = !u.autoOpen; PanelPrefs.setAutoOpen(context, on); e.setAutoOpen(on) }, selected = u.autoOpen) {
                Text(stringResource(if (u.autoOpen) R.string.shack_panel_auto_on else R.string.shack_panel_auto_off), fontSize = 16.sp)
            }
            TvItem(onClick = onClose, focus = first) { Text(stringResource(R.string.shack_done), fontSize = 16.sp) }
        }
    }
}

/** On a phone: the picture under the controls while one is coming or kept, with the session's last few to tap through. */
@Composable
internal fun PhoneSstv(e: EngineSession) {
    val u by e.ui.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<SstvPicture?>(null) }
    LaunchedEffect(u.sstvStarts) { shown = null }
    val (current, gallery) = synchronized(e.sstv) { e.sstv.current to e.sstv.gallery }
    val p = shown ?: current ?: return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(listOf(sstvFormat(p.fmt), sstvProgress(p)).joinToString(" · "), color = Dim, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SstvImage(e, p, u.sstvVersion, Modifier.sstvThumb(p, 180.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                gallery.forEach { g ->
                    SstvImage(e, g, u.sstvVersion, Modifier.sstvThumb(g, 40.dp).clickable { shown = g })
                }
            }
        }
    }
}
