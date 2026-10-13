package com.openhamshack.kind.stationflow.api

import org.json.JSONObject

/*
 * The scope settings and NR2, as the Icom module reports them (TV-API.md
 * 7a and 8a; contract ~/build/nr2-zoom-scope/CONTRACT.md, 10 October 2026).
 * Every field is feature-detected: a module without them parses to null,
 * and the screens then show no zoom, reference-level or NR2 control at all
 * rather than one that can only be refused.
 */

/**
 * What this model's scope can be set to (`radios[<id>].model.scope`). Null
 * when the model has no settable scope or the module is older.
 */
data class ScopeCaps(
    /** Full widths, Hz, narrowest first: the radio's "±2.5 kHz" is 5000. The zoom ladder. */
    val spansHz: List<Long>,
    val modes: List<String>,
    val edges: Int,
    val refMin: Double,
    val refMax: Double,
    val refStep: Double,
    val speeds: List<String>,
    val vbw: List<String>,
    val averaging: List<Int>,
) {
    companion object {
        fun parse(o: JSONObject?): ScopeCaps? {
            o ?: return null
            val ref = o.optJSONObject("ref_db")
            return ScopeCaps(
                spansHz = o.optJSONArray("spans_hz").let { a ->
                    if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optLong(it, -1).takeIf { v -> v > 0 } }.sorted()
                },
                modes = o.optJSONArray("modes").strings(),
                edges = o.optInt("edges", 0),
                refMin = ref?.optDouble("min", -20.0) ?: -20.0,
                refMax = ref?.optDouble("max", 20.0) ?: 20.0,
                refStep = ref?.optDouble("step", 0.5)?.takeIf { it > 0 } ?: 0.5,
                speeds = o.optJSONArray("speeds").strings(),
                vbw = o.optJSONArray("vbw").strings(),
                averaging = o.optJSONArray("averaging").let { a ->
                    if (a == null) emptyList() else (0 until a.length()).map { a.optInt(it) }
                },
            ).takeIf { ref != null || it.spansHz.isNotEmpty() || it.speeds.isNotEmpty() }
        }
    }
}

/** The scope's current settings (`radios[<id>].scope_settings`); each null until the radio answers. */
data class ScopeSettings(
    val mode: String? = null,
    val spanHz: Long? = null,
    val edge: Int? = null,
    val refDb: Double? = null,
    val speed: String? = null,
    val vbw: String? = null,
    val averaging: Int? = null,
) {
    companion object {
        fun parse(o: JSONObject?): ScopeSettings? = o?.let {
            ScopeSettings(
                mode = it.optStringOrNull("mode"),
                spanHz = it.optLongOrNull("span_hz"),
                edge = it.optLongOrNull("edge")?.toInt(),
                refDb = it.optDoubleOrNull("ref_db"),
                speed = it.optStringOrNull("speed"),
                vbw = it.optStringOrNull("vbw"),
                averaging = it.optLongOrNull("averaging")?.toInt(),
            )
        }
    }
}

/**
 * The module's own noise reduction on the receive audio (NR2, ohs-libs
 * shack_dsp), not the radio's: [available] false (with [why]) when the
 * module cannot run it, and then no control is offered. [level] 0-100.
 */
data class Nr2(val available: Boolean, val why: String?, val on: Boolean, val level: Int, val latencyMs: Int?) {
    companion object {
        fun parse(o: JSONObject?): Nr2? = o?.let {
            Nr2(
                available = it.optBoolean("available", true),
                why = it.optStringOrNull("why"),
                on = it.optBoolean("on"),
                level = it.optInt("level", 50).coerceIn(0, 100),
                latencyMs = it.optLongOrNull("latency_ms")?.toInt(),
            )
        }
    }
}
