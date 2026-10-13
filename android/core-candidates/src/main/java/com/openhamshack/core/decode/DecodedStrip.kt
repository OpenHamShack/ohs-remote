package com.openhamshack.core.decode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import com.openhamshack.core.R
import com.openhamshack.core.tv.Dim

/**
 * The character decoder's line, large on a TV: "CW · 18.0 WPM" and the
 * newest text at the right, cut from the left when it is longer than a
 * line. One component for every kind with a CW decoder (NB, Radio, Icom;
 * Flex next), so the strip reads the same whichever radio is behind it.
 *
 * With no text yet it shows [waiting] if given ("Listening for CW…": an
 * empty strip looks broken), else nothing at all, so a quiet mode costs no
 * space.
 */
@Composable
fun DecodedStrip(line: DecodedLine, big: Boolean, modifier: Modifier = Modifier, waiting: String? = null) {
    if (line.isEmpty) {
        if (waiting != null) {
            Text(waiting, color = Dim, fontSize = if (big) 18.sp else 13.sp, modifier = modifier.padding(8.dp))
        }
        return
    }
    val head = line.wpm?.let { stringResource(R.string.core_decoded_wpm, line.mode.uppercase(), wpmText(it)) }
        ?: line.mode.uppercase()
    Row(modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(head, color = Dim, fontSize = if (big) 16.sp else 12.sp, modifier = Modifier.align(Alignment.CenterVertically))
        NewestInView(Modifier.weight(1f).align(Alignment.CenterVertically)) {
            Text(line.text.takeLast(if (big) 70 else 48), color = Color.White, fontFamily = FontFamily.Monospace,
                // One line that never wraps: a wrapped second line, clipped,
                // left fragments of its glyphs under the first on the TV.
                fontSize = if (big) 26.sp else 16.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
        }
    }
}

/**
 * One line as wide as it likes, placed so its end is in view: at the start
 * while it fits, pushed left and clipped there once it does not. Clipping
 * at the end (Text's own overflow) hid the newest letters - the ones being
 * read - whenever the line was wider than the space beside the face, which
 * on the Icom TV face it was at 70 characters (emulator, 10/10/2026).
 */
@Composable
private fun NewestInView(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.clipToBounds()) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val w = constraints.maxWidth
        layout(w, p.height) { p.place(minOf(0, w - p.width), 0) }
    }
}

/** The speed as the NB strip has always shown it: one decimal ("18.0"). */
fun wpmText(wpm: Double): String = String.format(Locale.ROOT, "%.1f", wpm)
