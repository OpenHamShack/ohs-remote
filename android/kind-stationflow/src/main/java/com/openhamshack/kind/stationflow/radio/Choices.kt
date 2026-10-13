package com.openhamshack.kind.stationflow.radio

import com.openhamshack.core.tv.Choice
import com.openhamshack.kind.stationflow.api.RadioState

/*
 * What the face's overlays offer, worked out from the radio's state and
 * kept free of Compose so it is tested on the JVM (ChoicesTest). Each list
 * comes from what the module says this radio has - its `modes`,
 * `data_modes`, `preamp_modes`, `model.attenuators` - never from a table of
 * what an IC-9700 ought to have.
 */

/**
 * The radio's MODE screen as rows of buttons. The IC-9700's own (Basic
 * manual p. 3-2) is one row - SSB, CW, RTTY, AM, FM, DV, DD - where a second
 * touch of SSB swaps USB/LSB and of CW swaps CW/CW-R, and DATA is a key of
 * its own. A remote across the room is better served by every variant as a
 * button of its own, in the radio's order: LSB USB, CW CW-R, RTTY RTTY-R,
 * AM, FM, DV, DD, then whatever else the module lists; and under them the
 * DATA variants the radio allows (USB-D ...). Each button's key is what
 * POST api/mode takes ("CWR", "USB-D"); its label is what the radio prints.
 */
fun modeRows(st: RadioState): List<List<Choice>> {
    val have = st.modes.map { it.uppercase() }
    val order = listOf("LSB", "USB", "CW", "CWR", "RTTY", "RTTYR", "AM", "FM", "WFM", "DV", "DD")
    val main = order.filter { it in have } + have.filter { it !in order }
    val dataOrder = listOf("LSB", "USB", "AM", "FM")
    val data = st.dataModes.map { it.uppercase() }.let { d -> dataOrder.filter { it in d } + d.filter { it !in dataOrder } }
    fun choice(mode: String, isData: Boolean) = Choice(
        key = if (isData) "$mode-D" else mode,
        label = modeLabel(mode) + if (isData) "-D" else "",
        selected = st.mode?.uppercase() == mode && st.dataMode == isData,
    )
    // Rows of at most seven, the radio's own row length, so a long list does not shrink every button.
    return (main.map { choice(it, false) }.chunked(7) + data.map { choice(it, true) }.chunked(7)).filter { it.isNotEmpty() }
}

/** The radio's printed name for a CI-V mode name: CWR is "CW-R" on its screen, RTTYR "RTTY-R". */
fun modeLabel(mode: String): String = when (mode.uppercase()) {
    "CWR" -> "CW-R"
    "RTTYR" -> "RTTY-R"
    else -> mode.uppercase()
}

/** FIL1-FIL3, the three filters every Icom has; the current one selected. */
fun filterChoices(st: RadioState?): List<Choice> =
    listOf("FIL1", "FIL2", "FIL3").map { Choice(it, it, selected = it == st?.filter) }

/**
 * The preamp as whole settings (OFF, P.AMP, EXT, P.AMP+EXT on a 9700), each
 * the module refuses now shown dimmed with its reason; an older module's
 * levels (OFF + model.preamps) otherwise. Keys are "mode:<i>" or "level:<n>",
 * what StationFlowSession.setPreampChoice turns back into the POST. Empty: no preamp control.
 */
fun preampChoices(st: RadioState?, refusedWhy: (Int) -> String?): List<Choice> {
    st ?: return emptyList()
    if (st.hasPreampModes) return st.preampModes.mapIndexed { i, name ->
        Choice("mode:$i", name, selected = st.preampMode == i, enabled = i !in st.preampModesRefused,
            note = refusedWhy(i).takeIf { i in st.preampModesRefused })
    }
    if (st.preamps.isEmpty()) return emptyList()
    return (listOf("OFF") + st.preamps).mapIndexed { i, name -> Choice("level:$i", name, selected = st.preamp == i) }
}

/** OFF and each attenuator step the model lists, in dB; empty with none. Keys are the dB. */
fun attChoices(st: RadioState?): List<Choice> {
    if (st == null || st.attenuators.isEmpty()) return emptyList()
    return (listOf(0) + st.attenuators).map { db -> Choice(db.toString(), if (db == 0) "OFF" else "${db}dB", selected = st.att == db) }
}
