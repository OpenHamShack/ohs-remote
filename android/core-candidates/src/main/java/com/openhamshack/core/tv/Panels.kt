package com.openhamshack.core.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * Overlays the radio screens share (Icom's face, Flex's display), on a TV
 * by the D-pad and on a phone or tablet by touch: a grid of choices like a
 * radio's own MODE or FIL screen, and a side panel of settings rows.
 *
 * Both are drawn over a screen that the caller makes inert (LocalTvInert)
 * while they are up, so focus cannot leak to a control nobody can see
 * (GUIDE 5), and both close on Back by their own BackHandler: Compose on a
 * TV otherwise spends the first Back on clearing focus.
 */

/** One button in a [ChoiceOverlay]. [key] is the caller's; [label] what the button says. */
data class Choice(
    val key: String,
    val label: String,
    val selected: Boolean = false,
    /** False: shown, dimmed, and not pickable; [note] says why (the module's refusal). */
    val enabled: Boolean = true,
    val note: String? = null,
)

/**
 * A grid of [rows] of choices over a dimmed screen, as the radio's own MODE
 * screen is a grid of mode buttons: focus starts on the current choice, OK
 * (or a tap) picks and closes, Back closes. Rows keep their own lengths, so
 * a row of SSB/CW/RTTY/AM/FM/DV/DD sits over a row of DATA variants.
 */
@Composable
fun ChoiceOverlay(
    title: String,
    rows: List<List<Choice>>,
    onPick: (Choice) -> Unit,
    onClose: () -> Unit,
    hint: String? = null,
) {
    BackHandler { onClose() }
    val first = remember { FocusRequester() }
    val start = rows.flatten().let { all -> all.firstOrNull { it.selected && it.enabled } ?: all.firstOrNull { it.enabled } }
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    // A tap on the dimmed screen closes, as a phone user expects of an overlay.
    Box(Modifier.fillMaxSize().background(Color(0xB0000000)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 900.dp).padding(16.dp).background(Color(0xFF101418), RoundedCornerShape(16.dp))
                // Swallow taps on the card itself so they do not reach the close behind it.
                .clickable(enabled = false) {}
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            // No more to a row than the screen has room for: a phone held upright fitted seven modes
            // to a row with no room left for their names (seen on the emulator, 11 October 2026).
            val perRow = (LocalConfiguration.current.screenWidthDp / MIN_CHOICE_DP).coerceAtLeast(2)
            rows.flatMap { it.chunked(perRow) }.forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { c ->
                        TvItem(
                            onClick = { if (c.enabled) { onPick(c); onClose() } },
                            modifier = Modifier.weight(1f),
                            focus = if (c == start) first else null,
                            selected = c.selected,
                        ) {
                            // Shrinks to fit rather than cutting: on a phone on its side "RTTY-R" in a row of
                            // seven came out "RTTY..." at 18 sp.
                            BasicText(c.label, maxLines = 1, softWrap = false, modifier = Modifier.fillMaxWidth(),
                                style = TextStyle(fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                                    color = if (c.enabled) Color.White else Dim),
                                autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 18.sp, stepSize = 1.sp))
                        }
                    }
                }
            }
            rows.flatten().mapNotNull { it.note?.takeIf { _ -> !it.enabled } }.distinct().forEach {
                Text(it, color = Amber, fontSize = 14.sp)
            }
            hint?.let { Text(it, color = Dim, fontSize = 14.sp) }
        }
    }
}

/** The narrowest a choice button gets before a row is broken in two. */
private const val MIN_CHOICE_DP = 110

/**
 * A panel at the right, over the screen but leaving the scope in view, as
 * the Icom kind's Tune panel is: a title and rows, scrolled only as far as
 * focus needs. Back closes.
 */
@Composable
fun SidePanel(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    BackHandler { onClose() }
    Column(
        modifier.fillMaxHeight().width(560.dp).background(Color(0xF0101418))
            .verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        content()
    }
}

/**
 * A setting with a value that steps: on a TV one focus target, Left and
 * Right step it down and up and OK steps it up (wrapping where the caller
 * wraps); by touch, a tap on the row steps up and the − and + at its ends
 * step either way. [onMinus] null makes it a plain row that OK acts on
 * (a switch, or a row that opens its own choice).
 */
@Composable
fun SettingRow(
    label: String,
    value: String,
    onPlus: () -> Unit,
    onMinus: (() -> Unit)? = null,
    focus: FocusRequester? = null,
    note: String? = null,
) {
    val tv = isTelevision(LocalContext.current)
    TvItem(
        onClick = onPlus,
        focus = focus,
        modifier = Modifier.fillMaxWidth().onKeyEvent { e ->
            if (onMinus == null || e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { onMinus(); true }
                Key.DirectionRight -> { onPlus(); true }
                else -> false
            }
        },
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Dim, fontSize = 15.sp)
            note?.let { Text(it, color = Amber, fontSize = 13.sp) }
        }
        if (onMinus != null) {
            if (tv) Text("◀", color = Dim, fontSize = 16.sp)
            else Text("−", color = Color.White, fontSize = 24.sp,
                modifier = Modifier.clickable(onClick = onMinus).padding(horizontal = 14.dp))
        }
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp))
        if (onMinus != null) {
            if (tv) Text("▶", color = Dim, fontSize = 16.sp)
            else Text("+", color = Color.White, fontSize = 24.sp,
                modifier = Modifier.clickable(onClick = onPlus).padding(horizontal = 14.dp))
        }
    }
}

/** A heading between groups of rows in a [SidePanel]. */
@Composable
fun PanelHeading(text: String) {
    Spacer(Modifier.width(1.dp))
    Text(text, color = Dim, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}
