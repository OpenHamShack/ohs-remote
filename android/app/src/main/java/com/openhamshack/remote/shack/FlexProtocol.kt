package com.openhamshack.remote.shack

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/*
 * OpenHamShack Flex as its module serves it to a browser (dock360-apps, flex
 * line: app/main.py's routes and app/wire.py's frames), the receive side
 * only. There is no separate client API: Remote reads exactly what the web
 * page reads, through the controller with the device token, so nothing here
 * is a shape invented for Remote. Pure (no Android types), so the parsing is
 * tested on the JVM against frames captured from the real module.
 */

/** One receiver (a slice) as the module reports it. Frequencies in Hz here; the module speaks MHz. */
data class FlexSlice(
    val index: Int,
    val letter: String,
    val hz: Long,
    val mode: String,
    val filterLo: Int,
    val filterHi: Int,
    /** The panadapter it is drawn on, as the module writes ids ("0x40000000"). */
    val pan: String,
    /** The slice's own tuning step, Hz. */
    val step: Int,
    val active: Boolean,
    val muted: Boolean,
    /** The slice's signal level, dBm (the module's `level_dbm`), when it reports one. */
    val levelDbm: Double?,
    /** The radio's DSP on this slice, by the module's names (API.md 5): only what [dspOffered] lists is shown. */
    val dsp: Map<String, FlexDsp> = emptyMap(),
    /** What this radio has, in the module's order: the published five always, the newer ones once the radio mentions them. */
    val dspOffered: List<String> = emptyList(),
    /** off, slow, med, fast. */
    val agcMode: String = "",
    /** AGC-T, 0-100. */
    val agcThreshold: Int = 0,
    /** The receive antenna, as the radio names it ("ANT1", "RX_A"). */
    val rxAnt: String = "",
    /** The antennas this slice can receive on, the radio's own list. */
    val antList: List<String> = emptyList(),
    /** RF gain, dB: the preamp above 0, the attenuator below (FlexRfGain). Its panadapter's: the module copies it here. */
    val rfGain: Int = 0,
)

/** One DSP function on a slice: on or off, and its level 0-100 where it has one (RNN and ANFT have none). */
data class FlexDsp(val on: Boolean, val level: Int?)

data class FlexPan(
    val id: String,
    val lowHz: Double,
    val highHz: Double,
    val minDbm: Double,
    val maxDbm: Double,
    /**
     * The RF gain settings the radio said this panadapter takes, low to
     * high (the module's `rf_gain_steps`, API.md 4a); null when the radio
     * has not answered, its firmware will not, or the module is too old to ask.
     */
    val rfSteps: List<Int>? = null,
    /**
     * The module takes `pan.rf_gain` here. Told by the `rf_gain_steps` key
     * being there at all, null or not: the module started publishing it
     * with the action (flex 0.3.1-beta.2), and a null only means the radio
     * kept its ladder to itself.
     */
    val panRfGain: Boolean = false,
    /**
     * The scope settings the module can set (API.md 4b, POST api/scope;
     * contract 10 October 2026): the zoom ladder (`spans_hz`, Hz, a 1-2-5
     * ladder inside the radio's min_bw..max_bw), the span now, the top of
     * the scale and its depth (`ref_dbm`, `range_db`) and the waterfall's
     * line rate (1-100, higher is faster). Null from a module without
     * api/scope: the screens then offer no zoom, reference or speed control.
     */
    val spansHz: List<Long>? = null,
    val spanSetHz: Long? = null,
    val refDbm: Double? = null,
    val rangeDb: Double? = null,
    val lineRate: Int? = null,
    /**
     * Its waterfall's rendering (`waterfalls[...]` with this `panadapter_id`):
     * the radio's automatic black level, or the level set by hand, and its
     * colour gain, 0..100. SmartSDR has them; the module's page does not
     * offer them, so Remote does (contract note, 11 October 2026). Null
     * without a waterfall in the state.
     */
    val autoBlack: Boolean? = null,
    val blackLevel: Int? = null,
    val colorGain: Int? = null,
) {
    /** The module takes api/scope for this panadapter (it reports the settings it sets). */
    val hasScopeSettings: Boolean get() = refDbm != null || !spansHz.isNullOrEmpty()

    val centreHz: Double get() = (lowHz + highHz) / 2
    val spanHz: Double get() = highHz - lowHz

    companion object {
        /**
         * The module's `rf_gain_steps`: its `list` when it sent one, else
         * worked out from `low`, `high` and `step` as the module itself does.
         * Null for anything that is not a usable ladder, so the caller falls
         * back to its own table rather than offering steps made up from a
         * bad answer.
         */
        fun rfSteps(o: JSONObject?): List<Int>? {
            if (o == null) return null
            val list = o.optJSONArray("list")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? Number }.map { it.toInt() } }
            if (!list.isNullOrEmpty()) return list.distinct().sorted()
            if (!o.has("low") || !o.has("high") || !o.has("step")) return null
            val low = o.optInt("low"); val high = o.optInt("high"); val step = o.optInt("step")
            if (step <= 0 || high < low || (high - low) / step > 200) return null
            return (low..high step step).toList()
        }
    }
}

/**
 * The Flex module's own noise reduction on the receive audio (NR2, ohs-libs
 * shack_dsp; API.md 5a): separate from the radio's NR/NRL/NRS, and both may
 * be on. [available] false (with [why]) when the module cannot run it.
 */
data class FlexNr2(val available: Boolean, val why: String?, val on: Boolean, val level: Int) {
    companion object {
        fun parse(o: JSONObject?): FlexNr2? = o?.let {
            FlexNr2(it.optBoolean("available", true), if (it.isNull("why")) null else it.optString("why").ifBlank { null },
                it.optBoolean("on"), it.optInt("level", 50).coerceIn(0, 100))
        }
    }
}

data class FlexBand(val name: String, val label: String, val lowHz: Double, val highHz: Double) {
    fun contains(hz: Long) = hz >= lowHz && hz <= highHz
}

/**
 * One stretch of the band plan: [lowHz]..[highHz], what it is for ([kind]:
 * cw, data, phone, all, beacon, satellite, repeater, fm, image - the
 * module's words, which the strip prints from this app's catalog) and the
 * module's colour for it, ARGB.
 */
data class FlexSegment(val lowHz: Long, val highHz: Long, val kind: String, val colour: Int) {
    companion object {
        fun parse(o: JSONObject): FlexSegment? {
            val lo = o.optLong("low", -1)
            val hi = o.optLong("high", -1)
            if (lo < 0 || hi <= lo) return null
            return FlexSegment(lo, hi, o.optString("kind"), colour(o.optString("color")))
        }

        /** "#3060ff" to opaque ARGB; anything else a neutral grey rather than a crash. */
        fun colour(css: String): Int {
            val hex = css.removePrefix("#")
            val rgb = if (hex.length == 6) hex.toLongOrNull(16) else null
            return (0xFF000000 or (rgb ?: 0x808080L)).toInt()
        }
    }
}

data class FlexMode(val name: String, val label: String, val steps: List<Int>, val defaultStep: Int)

/**
 * What the radio receives, Hz: tuning stops at these edges rather than
 * asking the radio for a frequency it cannot have. Remote once stepped
 * straight past 0 Hz - the module and the radio took -100 Hz, and the dial
 * readout then threw on the negative number and crashed the app (Brian's
 * Fire TV, 10/10/2026).
 */
data class FlexCoverage(val lowHz: Long, val highHz: Long) {
    fun clamp(hz: Long): Long = hz.coerceIn(lowHz, highHz)

    companion object {
        /**
         * FlexRadio's published receiver coverage for the FLEX-6400(M),
         * 6600(M) and the FLEX-8000 series: 30 kHz to 54 MHz (each model's
         * specification sheet, "Receiver frequency range"). The module's own
         * GEN row says the same (app/bands.py RECEIVE_ONLY), so this is only
         * for a module too old to send that row. A FLEX-6700 also hears 2 m
         * (to 77 MHz, and 135-165 MHz), but the module offers nothing past
         * 54 MHz on any model yet, so neither does Remote.
         */
        val FLEX_6000 = FlexCoverage(30_000, 54_000_000)
    }
}

/** The band and mode tables the module sends a page on connecting (`tables`): the band plan by the station's licence. */
data class FlexTables(
    val bands: List<FlexBand> = emptyList(),
    val modes: List<FlexMode> = emptyList(),
    val coverage: FlexCoverage = FlexCoverage.FLEX_6000,
    /** Single frequencies worth marking: the plan's centres, WSJT-X's dials (FlexCalling). */
    val spots: List<FlexSpot> = emptyList(),
    /**
     * The band plan in force for the station's licence (the module's
     * `segments`, from shack_bandplan via flex/app/bandplan.py): what each
     * part of a band is for, drawn as a strip under the spectrum as the
     * module's page does. Empty from a module too old to send it.
     */
    val segments: List<FlexSegment> = emptyList(),
) {
    fun band(hz: Long?): FlexBand? = hz?.let { f -> bands.firstOrNull { it.contains(f) } }
    fun mode(name: String?): FlexMode? = modes.firstOrNull { it.name == name }

    companion object {
        fun parse(o: JSONObject?): FlexTables {
            if (o == null) return FlexTables()
            val rows = o.optJSONArray("bands")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
            val bands = rows
                // Not every row is somewhere to listen by band: GEN and WWV
                // are in the table for the page's band menu, with edges that
                // span everything. The amateur bands are the ones to step through.
                .filter { it.optBoolean("amateur", true) }
                .map { FlexBand(it.optString("name"), it.optString("label", it.optString("name")), it.optDouble("low"), it.optDouble("high")) }
            val modes = o.optJSONArray("modes")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { m ->
                    val steps = m.optJSONArray("steps")?.let { s -> (0 until s.length()).map { s.optInt(it) } }.orEmpty().filter { it > 0 }
                    FlexMode(m.optString("name"), m.optString("label", m.optString("name")), steps, m.optInt("default_step", steps.firstOrNull() ?: 100))
                }
            }.orEmpty()
            val spots = o.optJSONArray("spots")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { FlexSpot(it.optLong("hz"), it.optString("label")) }
            }.orEmpty().filter { it.hz > 0 && it.label.isNotBlank() }
            val segments = o.optJSONArray("segments")?.let { a ->
                (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { FlexSegment.parse(it) }
            }.orEmpty()
            return FlexTables(bands, modes, coverage(rows), spots, segments)
        }

        /**
         * The receiver's range from the module's GEN row ("everything else
         * the receiver covers"), when it sends one that makes sense; else
         * the documented FLEX-6000/8000 range. The module knows which radio
         * it has, so its row wins over the constant whenever it is usable.
         */
        private fun coverage(rows: List<JSONObject>): FlexCoverage {
            val gen = rows.firstOrNull { it.optString("name") == "GEN" } ?: return FlexCoverage.FLEX_6000
            val low = gen.optDouble("low", Double.NaN)
            val high = gen.optDouble("high", Double.NaN)
            if (low.isNaN() || high.isNaN() || low < 0 || high <= low) return FlexCoverage.FLEX_6000
            return FlexCoverage(low.roundToLong(), high.roundToLong())
        }
    }
}

/** The module's `state` (api/state, and the websocket's hello and state messages). */
data class FlexState(
    /** The module has the radio (its control channel is up). */
    val connected: Boolean,
    /** Why not, in the module's words. */
    val error: String?,
    val retryIn: Double,
    val slices: List<FlexSlice>,
    val pans: Map<String, FlexPan>,
    val activeSlice: Int?,
    val panId: String?,
    /** This module has the radio keyed under its lease (somebody's PTT). */
    val onAir: Boolean,
    /** The radio's interlock says it is transmitting, whoever keyed it (SmartSDR, a Maestro). */
    val transmitting: Boolean,
    /** The page's "follow the receiver" setting: the display recentres when the dial leaves it. */
    val followReceiver: Boolean,
    /** The radio's model as it names itself ("FLEX-6400"), for what only differs by model. */
    val radioModel: String = "",
    /** The station's callsign from its profile, for the decodes that name it. */
    val callsign: String = "",
    /** The module's decoder (API.md 6); null from a module before 0.3.1. */
    val decoders: FlexDecoders? = null,
    /** The module's SSTV receiver (API.md 7); null from a module before 0.3.1. */
    val sstv: FlexSstvState? = null,
    /** The module's NR2 on the receive audio (API.md 5a); null from a module without it. */
    val nr2: FlexNr2? = null,
) {
    /**
     * The receiver being worked: the radio's active slice when it is one of
     * this module's, else the lowest-numbered. The module never sets the
     * radio's active flag (PROTOCOL.md 7), so the flag may name a slice
     * SmartSDR owns, which is not in [slices] at all.
     */
    val worked: FlexSlice?
        get() = slices.firstOrNull { it.index == activeSlice } ?: slices.minByOrNull { it.index }

    /** The panadapter [worked] is on, else the module's first. */
    val pan: FlexPan?
        get() = worked?.let { pans[it.pan] } ?: panId?.let { pans[it] } ?: pans.values.firstOrNull()

    companion object {
        fun parse(o: JSONObject): FlexState {
            val sl = o.optJSONObject("slices")
            val slices = sl?.keys()?.asSequence()?.mapNotNull { k -> sl.optJSONObject(k) }?.filter { it.optBoolean("in_use", true) }?.map { s ->
                FlexSlice(
                    index = s.optInt("index"),
                    letter = s.optString("letter"),
                    hz = (s.optDouble("frequency", 0.0) * 1e6).roundToLong(),
                    mode = s.optString("mode"),
                    filterLo = s.optInt("filter_lo"),
                    filterHi = s.optInt("filter_hi"),
                    pan = panKey(s.optLong("pan")),
                    step = s.optInt("step", 100),
                    active = s.optBoolean("active"),
                    muted = s.optBoolean("audio_mute"),
                    levelDbm = if (s.has("level_dbm") && !s.isNull("level_dbm")) s.optDouble("level_dbm") else null,
                    dsp = FlexDspTable.ALL.filter { s.has(it.name) }.associate { f ->
                        f.name to FlexDsp(s.optBoolean(f.name), if (f.hasLevel && s.has(f.name + "_level")) s.optInt(f.name + "_level") else null)
                    },
                    dspOffered = s.optJSONArray("dsp_offered")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                        // A module before 0.3.1 sent no list: the published five are on every FLEX.
                        ?: FlexDspTable.ALL.filter { !it.extended && s.has(it.name) }.map { it.name },
                    agcMode = s.optString("agc_mode").lowercase(),
                    agcThreshold = s.optInt("agc_threshold"),
                    rxAnt = s.optString("rx_ant"),
                    rfGain = s.optInt("rf_gain"),
                    antList = s.optJSONArray("ant_list")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty(),
                )
            }?.sortedBy { it.index }?.toList().orEmpty()
            val pp = o.optJSONObject("panadapters")
            // Each waterfall names its panadapter, as a number or already in the module's "0x%08X".
            val wf = o.optJSONObject("waterfalls")
            val falls = wf?.keys()?.asSequence()?.mapNotNull { wf.optJSONObject(it) }?.associateBy { w ->
                when (val id = w.opt("panadapter_id")) {
                    is Number -> panKey(id.toLong())
                    is String -> id.toLongOrNull()?.let { panKey(it) } ?: id
                    else -> ""
                }
            }.orEmpty()
            val pans = pp?.keys()?.asSequence()?.associateWith { k ->
                val p = pp.getJSONObject(k)
                val w = falls[k]
                FlexPan(
                    k, p.optDouble("low_hz"), p.optDouble("high_hz"), p.optDouble("min_dbm", -130.0), p.optDouble("max_dbm", -40.0),
                    rfSteps = FlexPan.rfSteps(p.optJSONObject("rf_gain_steps")),
                    panRfGain = p.has("rf_gain_steps"),
                    spansHz = p.optJSONArray("spans_hz")?.let { a -> (0 until a.length()).map { a.optLong(it) }.filter { it > 0 }.sorted() },
                    spanSetHz = if (p.has("span_hz") && !p.isNull("span_hz")) p.optLong("span_hz") else null,
                    refDbm = if (p.has("ref_dbm") && !p.isNull("ref_dbm")) p.optDouble("ref_dbm") else null,
                    rangeDb = if (p.has("range_db") && !p.isNull("range_db")) p.optDouble("range_db") else null,
                    lineRate = if (p.has("line_rate") && !p.isNull("line_rate")) p.optInt("line_rate") else null,
                    autoBlack = w?.takeIf { it.has("auto_black") }?.optBoolean("auto_black"),
                    blackLevel = w?.takeIf { it.has("black_level") }?.optInt("black_level"),
                    colorGain = w?.takeIf { it.has("color_gain") }?.optInt("color_gain"),
                )
            }.orEmpty()
            val interlock = o.optJSONObject("interlock")?.optString("state").orEmpty()
            return FlexState(
                connected = o.optBoolean("connected"),
                error = if (o.isNull("error")) null else o.optString("error").ifBlank { null },
                retryIn = o.optDouble("retry_in", 0.0),
                slices = slices,
                pans = pans,
                activeSlice = if (o.isNull("active_slice") || !o.has("active_slice")) null else o.optInt("active_slice"),
                panId = if (o.isNull("pan_id")) null else o.optString("pan_id").ifBlank { null },
                onAir = o.optBoolean("onAir"),
                transmitting = interlock.equals("TRANSMITTING", ignoreCase = true),
                followReceiver = o.optJSONObject("settings")?.optBoolean("follow_receiver", true) ?: true,
                radioModel = o.optJSONObject("radio")?.optString("model").orEmpty(),
                callsign = o.optJSONObject("station_profile")?.optString("callsign").orEmpty(),
                decoders = FlexDecoders.parse(o.optJSONObject("decoders")),
                sstv = FlexSstvState.parse(o.optJSONObject("sstv")),
                nr2 = FlexNr2.parse(o.optJSONObject("nr2")),
            )
        }

        /** The module's spelling of a stream id: the map keys and `pan_id` are "0x%08X". */
        fun panKey(id: Long): String = "0x%08X".format(id and 0xFFFFFFFFL)
    }
}

/** One binary websocket frame (app/wire.py). Little-endian throughout. */
sealed interface FlexFrame {
    /** A panadapter trace: dBm per bin, from [lowHz] to [highHz]. */
    class Sweep(val pan: String, val lowHz: Double, val highHz: Double, val dbm: FloatArray) : FlexFrame

    /**
     * A waterfall row in the radio's own intensity scale (raw / 128, no
     * defined zero: PROTOCOL.md 3), [binHz] apart from [lowHz]. Named by
     * its panadapter's id, not the waterfall stream's.
     */
    class WaterfallRow(val pan: String, val lowHz: Double, val binHz: Double, val intensity: FloatArray) : FlexFrame

    /** Interleaved s16le PCM as the module decoded it from the radio's Opus. */
    class Audio(val channels: Int, val rate: Int, val pcm: ByteArray) : FlexFrame {
        /** One channel for the speaker: the two averaged (the module sends the radio's stereo mix). */
        fun mono(): ShortArray {
            val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
            val ch = channels.coerceAtLeast(1)
            val n = pcm.size / 2 / ch
            return ShortArray(n) { i ->
                var sum = 0
                for (c in 0 until ch) sum += b.getShort((i * ch + c) * 2).toInt()
                (sum / ch).toShort()
            }
        }
    }

    /** Each slice's S-meter, dBm, by slice index. */
    class Levels(val dbm: Map<Int, Float>) : FlexFrame

    companion object {
        const val SWEEP = 1
        const val WATERFALL = 2
        const val AUDIO = 3
        const val LEVELS = 4

        /** Null for a frame this build does not know or one too short for its own header (never a guess). */
        fun parse(bytes: ByteArray): FlexFrame? {
            if (bytes.isEmpty()) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return try {
                when (bytes[0].toInt() and 0xFF) {
                    SWEEP -> {
                        val count = b.getShort(2).toInt() and 0xFFFF
                        if (bytes.size < 28 + count * 2) return null
                        val pan = FlexState.panKey(b.getInt(4).toLong())
                        val low = b.getDouble(12)
                        val high = b.getDouble(20)
                        Sweep(pan, low, high, FloatArray(count) { b.getShort(28 + it * 2) / 10f })
                    }
                    WATERFALL -> {
                        val count = b.getShort(2).toInt() and 0xFFFF
                        if (bytes.size < 36 + count * 2) return null
                        val pan = FlexState.panKey(b.getInt(4).toLong())
                        val low = b.getDouble(12)
                        val bin = b.getDouble(20)
                        WaterfallRow(pan, low, bin, FloatArray(count) { b.getShort(36 + it * 2) / 128f })
                    }
                    AUDIO -> {
                        if (bytes.size < 8) return null
                        Audio(bytes[1].toInt() and 0xFF, b.getInt(4), bytes.copyOfRange(8, bytes.size))
                    }
                    LEVELS -> {
                        val count = bytes[1].toInt() and 0xFF
                        if (bytes.size < 4 + count * 4) return null
                        Levels((0 until count).associate { i ->
                            (bytes[4 + i * 4].toInt() and 0xFF) to b.getShort(4 + i * 4 + 2) / 10f
                        })
                    }
                    else -> null
                }
            } catch (_: IndexOutOfBoundsException) {
                null
            }
        }
    }
}
