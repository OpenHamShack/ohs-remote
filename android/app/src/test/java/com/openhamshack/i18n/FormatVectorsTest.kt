package com.openhamshack.i18n

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ohs-libs' shared formatting vectors (I18N.md §5 item 3) run against the
 * copy of ShackFormat in this app, so a copy that drifts from the library,
 * or a library change that was not copied with its vectors, fails here.
 *
 * The library's own test is JUnit 5 and reads the vectors from an ohs-libs
 * checkout with shack-client's JSON reader; this app has neither, so this
 * is the same test on JUnit 4 and org.json, reading the copy in
 * src/test/resources/i18n. Every case of a section is run and the
 * mismatches reported together, so one refresh shows every difference at
 * once. An expected `error` is a machine code; the English is not compared.
 */
class FormatVectorsTest {
    private val vectors = JSONObject(
        javaClass.getResourceAsStream("/i18n/format.json")!!.bufferedReader(Charsets.UTF_8).readText(),
    )

    private fun cases(section: String): List<JSONObject> {
        val a: JSONArray = vectors.getJSONArray(section)
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)
    private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    /** Runs [call] on every case of [section]; compares its text (or Hz), or its error code. */
    private fun run(section: String, want: String, call: (JSONObject) -> Any) {
        val all = cases(section)
        assertTrue("$section has no cases", all.isNotEmpty())
        val wrong = all.mapNotNull { c ->
            val got: Any = try {
                call(c).toString()
            } catch (e: ShackFormat.FormatError) {
                "error:" + e.code
            }
            val expected: Any = if (c.has("error")) "error:" + c.getString("error") else c.get(want).toString()
            if (got == expected) null else "$c: got \"$got\""
        }
        assertEquals("$section mismatches:\n" + wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun frequency() = run("frequency", "text") { c ->
        ShackFormat.frequency(c.get("hz") as Number, c.getString("locale"), c.getString("unit"), c.intOrNull("decimals"))
    }

    @Test
    fun offset() = run("offset", "text") { c ->
        ShackFormat.offset(c.get("hz") as Number, c.getString("unit"), c.getString("locale"), c.intOrNull("decimals"))
    }

    @Test
    fun parseFrequency() = run("parse_frequency", "hz") { c ->
        ShackFormat.parseFrequency(c.getString("text"), c.getString("locale"), c.stringOrNull("unit"))
    }

    @Test
    fun number() = run("number", "text") { c ->
        ShackFormat.number(c.get("x") as Number, c.getString("locale"), c.getInt("decimals"))
    }

    @Test
    fun swr() = run("swr", "text") { c -> ShackFormat.swr(c.get("x") as Number, c.getString("locale")) }

    @Test
    fun theVectorsAreTheVersionThisCopyWasWrittenFor() {
        assertEquals(1, vectors.getInt("version"))
    }

    @Test
    fun aJavaLocaleTagFindsItsSymbols() {
        assertEquals(",", Symbols.of(java.util.Locale.GERMANY.toLanguageTag()).decimal)
        assertEquals("’", Symbols.of("de_CH.UTF-8@euro").group)
    }
}
