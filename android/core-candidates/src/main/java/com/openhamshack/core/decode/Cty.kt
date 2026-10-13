package com.openhamshack.core.decode

import android.content.Context

// Which DXCC entity (country) a callsign belongs to, from AD1C's cty.dat:
// the Country column of a decode list. A port of openhamshack-nb's
// ohscore/dxcc.py, line for line where it matters, so a Remote names the
// same country as the desktop's FT8 panel for every call.
//
// The NB and Radio servers name the country on each decode themselves
// (api/events "country", openhamshack-nb ohscore/engine.py _with_country), and a server's answer always
// wins: it reads the operator's own cty.dat first. This copy is the
// fallback for a box older than that, and for Flex and Icom decodes, whose
// servers send none. The file is openhamshack-nb's pinned Big CTY, copied
// unmodified into assets with its MIT notice (cty.dat-copyright.txt,
// NOTICES.md); refresh both from openhamshack-nb ohscore/data together.

/**
 * A cty.dat parsed: [lookup] a call to its entity. [parse] is pure, so the
 * JVM tests run it on the real file; [load] reads the bundled copy once
 * per process, off the main thread (a 340 kB file).
 */
class Cty private constructor(
    private val exact: Map<String, Entity>,
    private val prefixes: Map<String, Entity>,
) {
    /** One DXCC entity: cty.dat's name for it, its continent and primary prefix (a leading * dropped). */
    data class Entity(val name: String, val continent: String, val prefix: String)

    private val longest = prefixes.keys.maxOfOrNull { it.length } ?: 0

    val isEmpty: Boolean get() = prefixes.isEmpty()

    /**
     * The entity [call] is operating from, or null: an exact entry first
     * (DXpeditions, special-event calls), then the location part
     * ([locationPart]) exact and by its longest known prefix. A hashed
     * call ("<...>") and maritime or aeronautical mobile give null.
     */
    fun lookup(call: String): Entity? {
        val c = call.trim().uppercase().trim('<', '>')
        if (c.isEmpty() || c.startsWith("...")) return null
        exact[c]?.let { return it }
        val where = locationPart(c)
        if (where.isNullOrEmpty()) return null
        exact[where]?.let { return it }
        for (n in minOf(where.length, longest) downTo 1) prefixes[where.substring(0, n)]?.let { return it }
        return null
    }

    companion object {
        // Suffixes that say how the station is operating, not where: dropped
        // before the lookup. /MM and /AM are at sea or in the air, in no
        // country at all.
        private val NOT_A_PLACE = setOf("P", "M", "QRP", "QRPP", "A", "LH", "LGT")
        private val NOWHERE = setOf("MM", "AM")
        // The overrides cty.dat hangs on a prefix - (CQ zone) [ITU zone]
        // <lat/lon> {continent} ~UTC offset~ - which a country name does not need.
        // Every bracket escaped, closing ones too: the JVM (and so the unit
        // tests) accepts a bare "}" or "]", but Android's ICU regex refuses
        // it, and the first build threw in this class's initialiser on the
        // emulator with every JVM test green.
        private val OVERRIDES = Regex("""\(.*?\)|\[.*?\]|<.*?>|\{.*?\}|~.*?~""")
        private val AREA = Regex("""^([A-Z0-9]*?[A-Z])(\d)""")

        /** The text of a cty.dat (https://www.country-files.com/cty-dat-format/) parsed. */
        fun parse(text: String): Cty {
            val exact = HashMap<String, Entity>()
            val prefixes = HashMap<String, Entity>()
            for (raw in text.split(';')) {
                val record = raw.trim()
                if (record.isEmpty()) continue
                val nl = record.indexOf('\n')
                if (nl < 0) continue
                val f = record.substring(0, nl).split(':').map { it.trim() }
                if (f.size < 8) continue
                val ent = Entity(f[0], f[3], f[7].trimStart('*'))
                for (alias in record.substring(nl + 1).replace("\n", "").replace("\r", "").split(',')) {
                    val a = OVERRIDES.replace(alias, "").trim().uppercase()
                    if (a.isEmpty()) continue
                    if (a.startsWith("=")) exact[a.substring(1)] = ent else prefixes[a] = ent
                }
            }
            return Cty(exact, prefixes)
        }

        /**
         * The part of a call that says where the station is, or null for
         * maritime or aeronautical mobile: EA8/G4ABC and G4ABC/EA8 give EA8
         * (the shorter part is the added prefix), G4ABC/P gives G4ABC, and
         * EA1ABC/8 is call area 8, EA8.
         */
        fun locationPart(call: String): String? {
            var parts = call.trim().uppercase().split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return ""
            if (parts.drop(1).any { it in NOWHERE }) return null
            parts = listOf(parts[0]) + parts.drop(1).filter { it !in NOT_A_PLACE }
            if (parts.size == 1) return parts[0]
            if (parts.size == 2 && parts[1].length == 1 && parts[1][0].isDigit()) {
                val m = AREA.find(parts[0])
                return if (m != null) m.groupValues[1] + parts[1] else parts[0] + parts[1]
            }
            return parts.take(2).minByOrNull { it.length }
        }

        /** What [load] reads: assets/cty.dat in this library, merged into every app that uses it. */
        const val ASSET = "cty.dat"

        @Volatile private var bundled: Cty? = null

        /**
         * The bundled cty.dat, parsed on first use and kept. Blocking: call
         * it from a background dispatcher. An unreadable asset gives an
         * empty table (every lookup null), never a crash: the column is a
         * nicety beside the decodes.
         */
        fun load(context: Context): Cty = bundled ?: synchronized(this) {
            bundled ?: runCatching {
                context.applicationContext.assets.open(ASSET).use { parse(String(it.readBytes(), Charsets.ISO_8859_1)) }
            }.getOrElse { Cty(emptyMap(), emptyMap()) }.also { bundled = it }
        }
    }
}
