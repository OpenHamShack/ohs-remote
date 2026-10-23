package com.openhamshack.remote.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openhamshack.remote.R
import com.openhamshack.remote.shack.EngineUi
import com.openhamshack.remote.shack.SMeterScale
import com.openhamshack.remote.shack.StationBadge
import androidx.compose.ui.res.stringResource

// The TV polish pass on Radio's screen (Brian approved TvPolish,
// TvPolishBands and TvPolishDab on 23/10/2026): the same layout as the Tv
// mockup, made to look finished from a sofa - a light, tabular frequency
// with its last digits set back, glass panels with soft shadows and hairline
// edges on a faint vignette, a filled spectrum over a navy-to-amber
// waterfall with a glowing passband and a dial flag, a segmented S-meter
// that eases, and focus that lifts with a cyan glow. Roboto only, which the
// Fire TV already has: nothing is bundled or fetched. NB's TV screen and the
// rest of the app keep the plain look ([LocalTvPolish] is false there).

/** True inside Radio's TV screen: [TvItem] lifts and glows as the polish has it. */
internal val LocalTvPolish = compositionLocalOf { false }

/** A glass card's fill and its hairline edge (the mockups' rgba(20,27,36,0.85) and 6% white). */
internal val PolishCard = Color(0xE6182029)
internal val PolishHairline = Color(0x0FFFFFFF)
private val PolishPanel = Color(0xD9141B24)
/** The light text and the quiet greys of the polish. */
internal val PolishText = Color(0xFFEAF0F6)
internal val PolishDim = Color(0xFF8D99A6)
internal val PolishQuiet = Color(0xFF7D8996)
private val SegmentOff = Color(0xFF1C2530)
private val SegmentOver = Color(0xFF8FDCFA)
internal val PolishAmber = Color(0xFFF4C063)

/** Tabular figures, so the frequency does not shuffle sideways as it tunes. */
internal const val TABULAR = "tnum"

/** The faint vignette behind the whole screen: lighter navy at the top left, near black at the corners. */
internal fun Modifier.polishVignette(): Modifier = drawBehind {
    drawRect(Color(0xFF05070A))
    drawRect(Brush.radialGradient(listOf(Color(0xFF101A26), Color(0xFF080B10), Color(0x00080B10)),
        center = Offset(size.width * 0.3f, 0f), radius = size.width * 0.9f))
}

/** A glass panel: soft shadow below, hairline edge, the card's fill. */
internal fun Modifier.glass(shape: Shape, elevation: Dp = 16.dp, fill: Color = PolishPanel): Modifier =
    shadow(elevation, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape).background(fill).border(1.dp, PolishHairline, shape)

/** A small heading in the polish's spaced capitals ("SIGNAL", "NOW PLAYING"). */
@Composable
internal fun PolishLabel(text: String, colour: Color = PolishQuiet, modifier: Modifier = Modifier) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = colour, maxLines = 1, modifier = modifier)
}

/**
 * The Signal card (TvPolish): the calibrated reading big ("S9" and "+20"
 * in the accent), and fifteen segments S1 to S9 then +10 to +60, lit as far
 * as the reading (or, uncalibrated, the bar's fraction), the last lit one
 * glowing. The count eases over 300 ms rather than jumping on each meter
 * message.
 */
@Composable
internal fun SignalCard(u: EngineUi, modifier: Modifier) {
    val reading = u.sMeter.reading
    val target = SMeterScale.litFor(reading) ?: SMeterScale.litFor(u.rxDb?.let { ((it + 120.0) / 100.0).toFloat() } ?: 0f)
    val lit by animateFloatAsState(target.toFloat(), tween(300), label = "meter")
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.glass(shape).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            PolishLabel(stringResource(R.string.shack_signal_heading), modifier = Modifier.weight(1f).padding(bottom = 4.dp))
            if (reading != null) {
                val plus = reading.indexOf('+')
                Text(if (plus < 0) reading else reading.substring(0, plus), fontSize = 26.sp, fontWeight = FontWeight.Medium, color = PolishText)
                if (plus >= 0) Text(reading.substring(plus), fontSize = 17.sp, color = Accent, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        Row(Modifier.fillMaxWidth().height(14.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val top = lit.toInt() - 1
            for (i in 0 until SMeterScale.SEGMENTS) {
                val on = i < Math.round(lit)
                val seg = RoundedCornerShape(3.dp)
                Box(Modifier.weight(1f).height(14.dp)
                    .then(if (on && i == top) Modifier.shadow(6.dp, seg, ambientColor = SegmentOver, spotColor = SegmentOver) else Modifier)
                    .clip(seg).background(if (!on) SegmentOff else if (SMeterScale.over(i)) SegmentOver else Accent))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            // Each label centred on its segment and free to run over its neighbours, which are blank: "+20" is wider than a segment.
            for (i in 0 until SMeterScale.SEGMENTS) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(SMeterScale.label(i), fontSize = 10.sp, color = Color(0xFF6F7C8A), maxLines = 1, softWrap = false,
                    textAlign = TextAlign.Center, modifier = Modifier.wrapContentWidth(unbounded = true))
            }
        }
    }
}

/** The polish's badge colours for a station: deep tones, each a gradient from lighter to darker. */
private val BADGES = listOf(
    Color(0xFF2A6F8F) to Color(0xFF164257), Color(0xFF8F2F3B) to Color(0xFF5A1C25), Color(0xFF3F7F4A) to Color(0xFF214A2B),
    Color(0xFF7A5A1E) to Color(0xFF4A3510), Color(0xFF5B3F8F) to Color(0xFF34235A), Color(0xFF2F7F7A) to Color(0xFF184A47),
    Color(0xFF8F4F2A) to Color(0xFF5A2F16), Color(0xFF4A5A7A) to Color(0xFF2A3347),
)

/** A station's initials badge in its own stable colour (StationBadge: by the service's id). */
@Composable
internal fun StationBadgeBox(name: String, key: String, size: Dp, corner: Dp, textSize: androidx.compose.ui.unit.TextUnit,
                             /** What the badge says instead of the name's initials (a station without RDS: its whole MHz, "103"). */
                             text: String? = null) {
    val (a, b) = BADGES[StationBadge.colourIndex(key, BADGES.size)]
    Box(Modifier.size(size).clip(RoundedCornerShape(corner)).background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        Text(text ?: StationBadge.initials(name), fontSize = textSize, fontWeight = FontWeight.Light, color = PolishText, maxLines = 1)
    }
}

/** An outlined chip of the Now playing card ("DAB", "128 kbit/s"). */
@Composable
internal fun PolishChip(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Text(text, fontSize = 13.sp, color = Color(0xFFD6DEE6), maxLines = 1,
        modifier = Modifier.border(1.dp, Color(0xFF2E3945), shape).padding(horizontal = 10.dp, vertical = 4.dp))
}

/** Space the polish leaves between its parts. */
@Composable
internal fun PolishGap(w: Dp) = Spacer(Modifier.width(w))

/** The dial flag's text style: the frequency on a light tab at the top of the dial line. */
internal val FlagStyle = TextStyle(color = Color(0xFF06202C), fontSize = 12.sp, fontWeight = FontWeight.Medium)
