package com.openhamshack.remote.shack

import org.json.JSONObject
import kotlin.math.abs

// OpenHamShack Flex's decoders and SSTV (its API.md 6 and 7, module 0.3.1):
// the module decodes the radio's receive audio itself with ohsdigi and the
// family's SSTV decoder, one choice shared by every client, receive only.
// Remote chooses the mode and shows what comes back on the module's one
// websocket, in the same shapes NB and Radio send, so the same list and
// the same picture painter draw them. Pure, so tested on the JVM against
// what the real module sent (resources/shack/flex/).

/** The decoder's status (`GET api/decoders`, `state.decoders`). */
data class FlexDecoders(
    val available: Boolean,
    /** Why there is no decoder, in the module's words. */
    val why: String?,
    /** The modes this copy of the module decodes, in the order offered: only these may be chosen. */
    val modes: List<String>,
    /** The mode decoding now, "off" when none. */
    val mode: String,
    val period: Double?,
    val busy: Boolean,
    val error: String?,
) {
    val on: Boolean get() = available && mode != OFF && mode.isNotBlank()

    companion object {
        const val OFF = "off"
        /** The order the modes are offered in, whatever order the module's map has them. */
        private val ORDER = listOf("ft8", "ft4", "js8", "q65")

        fun parse(o: JSONObject?): FlexDecoders? {
            if (o == null) return null
            val names = o.optJSONObject("modes")?.keys()?.asSequence()?.toList().orEmpty()
            return FlexDecoders(
                available = o.optBoolean("available"),
                why = o.text("why"),
                modes = names.sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) ORDER.size else i } },
                mode = o.optString("mode", OFF).ifBlank { OFF },
                period = if (o.isNull("period") || !o.has("period")) null else o.optDouble("period"),
                busy = o.optBoolean("busy"),
                error = o.text("error"),
            )
        }
    }
}

/** The SSTV receiver's status (`GET api/sstv`, `state.sstv`). */
data class FlexSstvState(
    val available: Boolean,
    val why: String?,
    val on: Boolean,
    /** The picture arriving now, by name; null between pictures. */
    val receiving: String?,
    val error: String?,
) {
    companion object {
        fun parse(o: JSONObject?): FlexSstvState? = o?.let {
            FlexSstvState(it.optBoolean("available"), it.text("why"), it.optBoolean("on"), it.text("receiving"), it.text("error"))
        }
    }
}

/**
 * One period's decodes from the websocket (`{"type": "decodes", ...}`), as
 * the list NB and Radio show: `message` is the text, the period's start the
 * slot. An empty period is an empty list - the decoder is alive, and
 * nothing was heard.
 */
object FlexDecodes {
    fun parse(o: JSONObject): List<SlotDecode> {
        val a = o.optJSONArray("decodes") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { d ->
            val slot = d.num("time") ?: o.num("time") ?: return@mapNotNull null
            SlotDecode(d.optString("mode", o.optString("mode")), slot, d.optInt("snr"), d.optDouble("dt", 0.0), d.optInt("df"),
                d.optString("message").trim())
        }
    }

    /** `GET api/decodes`: the module's last 500, oldest first, for a client that joins or comes back. */
    fun history(o: JSONObject): List<SlotDecode> = parse(o)
}

/** One finished picture the module kept (`GET api/sstv/images`). [url] is relative to the app's base. */
data class FlexSstvImage(
    val name: String, val fmt: String, val time: Long, val partial: Boolean, val width: Int, val height: Int, val url: String,
) {
    val aspect: Float get() = if (height > 0) width.toFloat() / height else 4f / 3f

    companion object {
        fun list(o: JSONObject): List<FlexSstvImage> {
            val a = o.optJSONArray("images") ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { i ->
                val url = i.optString("url")
                // Only a path under the app's own base: never an absolute
                // URL (a module can be served under any prefix) or one that
                // climbs out of it.
                if (url.isBlank() || url.contains("://") || url.startsWith("/") || url.contains("..")) return@mapNotNull null
                FlexSstvImage(i.optString("name"), i.optString("fmt"), i.optLong("time"), i.optBoolean("partial"),
                    i.optInt("width"), i.optInt("height"), url)
            }
        }
    }
}

/** A single frequency the module's tables mark (`spots`): the plan's centres of activity, then WSJT-X's dials. */
data class FlexSpot(val hz: Long, val label: String)

/**
 * Where a decoder's signals are: "FT8 is on 7.074 MHz, DIGU". From the
 * module's own spots (WSJT-X's default dials for FT8 and FT4, the plan's
 * SSTV centres) - never a table of this app's - so it follows the station's
 * band plan. Null when there is nothing to say: already there, or no spot
 * for that mode (the module has none for JS8 or Q65).
 */
data class FlexCalling(val label: String, val hz: Long, val mode: String) {
    companion object {
        /** How near the dial counts as "there": a little off is how people sit on FT8 anyway. */
        const val NEAR_HZ = 3_000L

        /**
         * [decoder] is a decoder mode ("ft8") or "sstv". The spot is the
         * mode's in the dial's band, else the nearest anywhere. The sideband
         * is the one that mode is worked in: DIGU for the digital modes (as
         * WSJT-X uses upper sideband on every band), and for SSTV the voice
         * convention, LSB below 10 MHz and USB above.
         */
        fun hint(decoder: String, dialHz: Long?, sliceMode: String?, tables: FlexTables): FlexCalling? {
            val want = decoder.uppercase()
            val spots = tables.spots.filter { it.label.equals(want, ignoreCase = true) }
            if (spots.isEmpty() || dialHz == null) return null
            val band = tables.band(dialHz)
            val pick = (band?.let { b -> spots.filter { b.contains(it.hz) } }?.takeIf { it.isNotEmpty() } ?: spots)
                .minByOrNull { abs(it.hz - dialHz) } ?: return null
            val mode = if (want == "SSTV") (if (pick.hz < 10_000_000) "LSB" else "USB") else "DIGU"
            val modeOk = sliceMode == mode || (mode == "DIGU" && sliceMode == "USB")
            if (abs(pick.hz - dialHz) <= NEAR_HZ && modeOk) return null
            return FlexCalling(want, pick.hz, mode)
        }
    }
}

internal fun JSONObject.text(key: String): String? = if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }
