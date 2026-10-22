package com.openhamshack.remote.shack

import org.json.JSONObject

// Radio's SDR antenna input (openhamshack-nb SERVER-API, "What it is not
// (yet)": soapyAntenna, soapyAntennaLabels, deviceStatus.antenna/antennas).
// An RSPdx has three sockets the driver calls "Antenna A", "Antenna B" and
// "Antenna C"; the operator gives each a name of their own in Settings, and
// every client shows the two together the way the server's Settings page
// does (ohscore/antennas.py display_name), so "Antenna C — Loop Antenna" on
// a tablet is the same words as on the controller.
//
// The rules are copied from ohscore/antennas.py rather than invented: a
// change there must be made here too.

/**
 * What a screen needs to offer the inputs: [inputs] as the open radio
 * reported them, [current] the one in use (the server keeps both while the
 * radio is closed), [chosen] the setting ("" is the driver's default), and
 * the operator's [labels].
 */
data class AntennaChoice(
    val inputs: List<String>,
    val current: String,
    val chosen: String,
    val labels: Map<String, String>,
) {
    companion object {
        /**
         * From the engine's state, or null when there is nothing to pick:
         * a radio that is not an SDR, a server older than the antenna keys,
         * or an SDR that reported no inputs. A server with `antennas` but no
         * `soapyAntenna` setting is taken as on [current], so the picker
         * still lights the right row.
         */
        fun of(s: EngineState): AntennaChoice? {
            val inputs = s.antennas
            if (inputs.isEmpty()) return null
            val current = s.antenna.orEmpty()
            return AntennaChoice(inputs, current, s.soapyAntenna ?: current, Antennas.parseLabels(s.antennaLabels))
        }
    }

    /** The picker's rows: the driver's default first (as ""), then each input the radio reported. */
    val rows: List<String> get() = listOf("") + inputs
}

object Antennas {
    /**
     * The labels as the server's parse_labels reads them: {} for an empty
     * value, corrupt JSON, or anything but an object; inside an object only
     * string-to-non-empty-string entries count. A damaged setting (hand
     * edited, or an older format) loses its labels rather than taking the
     * Radio screen down with it.
     */
    fun parseLabels(raw: String?): Map<String, String> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyMap()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().mapNotNull { k ->
            (o.opt(k) as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    }

    /**
     * "Antenna C — Loop Antenna" where a label is set, "Antenna C" where
     * not, and [driverDefault] (the catalog's words) for "", the setting
     * that leaves the input to the driver. An em dash, as the server's
     * display_name writes it.
     */
    fun display(antenna: String, labels: Map<String, String>, driverDefault: String): String {
        if (antenna.isEmpty()) return driverDefault
        val label = labels[antenna].orEmpty()
        return if (label.isEmpty()) antenna else "$antenna — $label"
    }
}
