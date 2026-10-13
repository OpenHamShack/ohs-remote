package com.openhamshack.remote.shack

import org.json.JSONObject
import kotlin.math.abs

// Bookmarks along the NB/Radio scope, as OpenWebRX+ draws its band plan's
// markers over the waterfall: one press tunes there and sets the mode, so
// the matching decoder starts. They are the engine's own memories (`GET
// api/memories`: the app's band-plan presets, then the operator's), never a
// table in this app - band plans are data, chosen by the station's country
// on the server (BETA-PLAN; ohsradio/plans, ohsnb/bandplan.py). Pure, so it is
// tested on the JVM against memories recorded from the simulators.

/** One memory: [hz] is the dial (`vfo`) the engine's recall sets, [mode] the mode it sets with it. */
data class Bookmark(val name: String, val hz: Double, val mode: String, val builtin: Boolean) {
    companion object {
        /**
         * `{"memories": [...]}`, in frequency order. The frequency key is the
         * app's own (NB `dl`, Radio `rf`, as the logbook's); a memory with
         * none, or no name to recall it by, is left out.
         */
        fun parseList(o: JSONObject): List<Bookmark> {
            val a = o.optJSONArray("memories") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i ->
                val m = a.optJSONObject(i) ?: return@mapNotNull null
                val hz = m.num("dl") ?: m.num("rf") ?: m.num("freq") ?: return@mapNotNull null
                val name = m.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Bookmark(name, hz, m.optString("mode"), m.optBoolean("builtin"))
            }.sortedBy { it.hz }
        }
    }
}

object Bookmarks {
    /**
     * The bookmarks the bookmark row offers: those on the scope ([lo]..[hi],
     * what the waterfall shows), else - the dial between bands, or the
     * scope not drawn yet - the [fallback] nearest the dial, so the row is
     * never empty on an engine that has any. Frequency order either way, so
     * Left/Right on the row walks along the band as the labels lie on it.
     */
    fun inView(all: List<Bookmark>, lo: Double?, hi: Double?, dial: Double?, fallback: Int = 6): List<Bookmark> {
        if (lo != null && hi != null && hi > lo) {
            val on = all.filter { it.hz in lo..hi }
            if (on.isNotEmpty()) return on
        }
        if (dial == null) return all.take(fallback)
        return all.sortedBy { abs(it.hz - dial) }.take(fallback).sortedBy { it.hz }
    }

    /** Which of [list] is nearest [hz]: where focus lands when Up leaves the scope. -1 for none. */
    fun nearest(list: List<Bookmark>, hz: Double?): Int {
        if (list.isEmpty()) return -1
        if (hz == null) return 0
        return list.indices.minBy { abs(list[it].hz - hz) }
    }

    /** The bookmark the radio is on now (dial within [tolHz] and the same mode), shown as chosen. */
    fun current(list: List<Bookmark>, hz: Double?, mode: String, tolHz: Double = 50.0): Bookmark? =
        if (hz == null) null else list.firstOrNull { abs(it.hz - hz) <= tolHz && it.mode == mode }

    /**
     * The next bookmark above the dial ([by] > 0) or below it, more than
     * [tolHz] away so the one the radio is on is passed over: NB's channel
     * keys, NB having one band where Radio steps bands. Null at either end.
     */
    fun step(all: List<Bookmark>, hz: Double?, by: Int, tolHz: Double = 50.0): Bookmark? {
        if (all.isEmpty()) return null
        if (hz == null) return if (by > 0) all.first() else all.last()
        val sorted = all.sortedBy { it.hz }
        return if (by > 0) sorted.firstOrNull { it.hz > hz + tolHz } else sorted.lastOrNull { it.hz < hz - tolHz }
    }
}
