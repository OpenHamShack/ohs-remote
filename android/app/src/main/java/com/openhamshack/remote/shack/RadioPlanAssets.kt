package com.openhamshack.remote.shack

import android.content.Context
import org.json.JSONObject

/**
 * The shared band-plan data from assets/bandplan (RadioPlan.kt says why it
 * is there), read once per process: four plans and the vocabulary are
 * ~120 kB of JSON, a few tens of milliseconds to parse, so the session
 * reads it off the main thread and the screen draws no strip until then.
 * A missing or unreadable file leaves that plan out rather than failing
 * the screen: the strip is a help, never something Radio needs to work.
 */
object RadioPlanAssets {
    @Volatile private var cached: PlanData? = null

    fun load(context: Context): PlanData {
        cached?.let { return it }
        val assets = context.applicationContext.assets
        fun read(path: String): JSONObject? =
            runCatching { JSONObject(assets.open("bandplan/$path").bufferedReader().use { it.readText() }) }.getOrNull()
        val vocab = read("vocab.json")?.let { PlanVocab.parse(it) } ?: PlanVocab(emptyList(), emptyMap())
        val plans = PlanData.PLAN_IDS.mapNotNull { id -> read("plans/$id.json")?.let { BandPlan.parse(it) } }.associateBy { it.id }
        return PlanData(vocab, plans).also { cached = it }
    }
}

/**
 * [BandMemory] in this device's preferences, one value per box: the
 * controller and the app, so two shacks' Radios each keep their own 20 m.
 */
class BandMemoryPrefs(context: Context) : BandMemory {
    private val prefs = context.applicationContext.getSharedPreferences("radio_bands", Context.MODE_PRIVATE)

    override fun load(key: String): Map<String, BandSpot> = RadioBands.decode(prefs.getString(key, null))

    override fun save(key: String, spots: Map<String, BandSpot>) { prefs.edit().putString(key, RadioBands.encode(spots)).apply() }

    override fun loadRecent(key: String): List<String> = RecentBands.decode(prefs.getString("$key|recent", null))

    override fun saveRecent(key: String, names: List<String>) { prefs.edit().putString("$key|recent", RecentBands.encode(names)).apply() }

    override fun loadDab(key: String): Map<String, String> {
        val o = runCatching { org.json.JSONObject(prefs.getString("$key|dab", null) ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }.filterValues { it.isNotEmpty() }
    }

    override fun saveDab(key: String, services: Map<String, String>) {
        prefs.edit().putString("$key|dab", org.json.JSONObject(services as Map<*, *>).toString()).apply()
    }
}
