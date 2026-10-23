package com.openhamshack.remote.shack

import org.json.JSONObject

// DAB on Radio: a station list and play (RADIO-LISTEN-CONTRACT §3; Brian
// chose that over a scanner or an ensemble browser). The server runs a
// decoder session (`dab`) on one Band III block; its feed, polled with
// `?since=`, says when the block has synced and which services it carries,
// and a service is chosen with `params.service`. The audio is Radio's own
// receive stream, so nothing here plays anything: this is only the list.
//
// Each service is "dab" (MP2) or "dab+" (HE-AAC). A server without faad2
// lists its dab+ services with a `why` sentence and cannot play them (the
// Radio session, 23/10/2026: so it is on ohs-box until faad2 arrives), so a
// service with a why is shown dimmed with its sentence and never asked for.

/** One service on the block: [why] non-empty means the server cannot play it, and says why. */
data class DabService(val sid: String, val label: String, val kind: String, val bitrate: Int?, val why: String) {
    val playable: Boolean get() = why.isEmpty()
}

/**
 * DAB's panel, while it is open: the block being listened to, its session
 * once the server has started one, and what the feed has said. [problem] is
 * the server's sentence for a session it refused (decoder_unavailable: no
 * faad2, or no dab decoder at all) or a block that has nothing ([sync]
 * false with a why); [since] is the last result read.
 */
data class DabUi(
    val block: DabBlock,
    val session: String? = null,
    val sync: Boolean = false,
    val ensemble: String = "",
    val services: List<DabService> = emptyList(),
    val playing: String? = null,
    val problem: String = "",
    val since: Int = 0,
) {
    /** Still acquiring: a session and nothing heard yet, nor refused. */
    val acquiring: Boolean get() = session != null && !sync && problem.isEmpty()
}

object DabFeed {
    const val DECODER = "dab"

    /** `POST api/decoders/sessions`'s body for [block]: its centre is the dial (and the session's frequency). */
    fun start(block: DabBlock): JSONObject = JSONObject().put("decoder", DECODER).put("freqHz", block.centreHz)
        .put("params", JSONObject().put("block", block.label))

    /**
     * The panel after one poll of the session (`GET .../sessions/{id}?since=`):
     * each result newer than [DabUi.since] that reports the ensemble (it
     * carries `sync`) replaces what was known, in order, so the last word
     * wins. A session the server has stopped ("state": "stopped" or a why on
     * the view itself) says so as the problem.
     */
    fun apply(ui: DabUi, poll: JSONObject): DabUi {
        var out = ui
        val results = poll.optJSONArray("results")
        for (i in 0 until (results?.length() ?: 0)) {
            val r = results!!.optJSONObject(i) ?: continue
            val rev = r.optInt("rev", 0)
            if (rev <= out.since) continue
            out = out.copy(since = rev)
            if (!r.has("sync")) continue
            val sync = r.optBoolean("sync", false)
            out = out.copy(
                sync = sync,
                ensemble = r.optJSONObject("ensemble")?.optString("label").orEmpty().trim(),
                services = services(r),
                playing = r.optString("playing").ifEmpty { null }.takeIf { r.has("playing") && !r.isNull("playing") },
                problem = if (!sync) sentence(r) else "",
            )
        }
        if (poll.optString("state") == "failed" || poll.optString("state") == "stopped") {
            out = out.copy(problem = poll.optString("whyText").ifEmpty { out.problem })
        }
        return out
    }

    /** A result's services, in the server's order; one with no id is dropped (nothing could choose it). */
    fun services(r: JSONObject): List<DabService> {
        val a = r.optJSONArray("services") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val s = a.optJSONObject(i) ?: return@mapNotNull null
            val sid = s.optString("sid")
            if (sid.isEmpty()) null
            else DabService(sid, s.optString("label").trim().ifEmpty { sid }, s.optString("kind"),
                s.optInt("bitrate", 0).takeIf { it > 0 }, sentence(s))
        }
    }

    /** The server's sentence: `whyText` where it gives one beside a code, else `why`. */
    private fun sentence(o: JSONObject): String =
        o.optString("whyText").ifEmpty { if (o.isNull("why")) "" else o.optString("why") }

    /** Whether choosing [sid] should go to the server: a service it listed and can play. */
    fun canPlay(ui: DabUi, sid: String): Boolean = ui.session != null && ui.services.any { it.sid == sid && it.playable }
}
