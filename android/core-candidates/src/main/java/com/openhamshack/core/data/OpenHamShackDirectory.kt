package com.openhamshack.core.data

import org.json.JSONObject
import com.openhamshack.core.net.BoxAddress

/** One app a OpenHamShack platform server has installed. */
data class OpenHamShackApp(
    val id: String,
    val kind: String,
    val name: String,
    val version: String,
    /** Where the app is reached: its base URL, resolved against the server. */
    val base: BoxAddress,
    val running: Boolean,
    val txCapable: Boolean,
)

/**
 * `GET /.well-known/openhamshack` on a platform server (PLATFORM-PLAN section
 * 2.1, being designed): `{name, version, auth, apps: [{id, kind, name,
 * version, base, running, txCapable}]}`. Read leniently, as the design may
 * still move: an app without a kind or a base is skipped, not fatal.
 *
 * `base` may be a path on the same server ("/icom/") - the usual case,
 * Dock360 mounting each app under a prefix - or a full URL (an app on a
 * subdomain); either way the result is the base URL a kind is handed.
 */
object OpenHamShackDirectory {
    fun parse(server: BoxAddress, o: JSONObject): List<OpenHamShackApp> {
        val arr = o.optJSONArray("apps") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = a.optString("kind").trim().lowercase().ifEmpty { return@mapNotNull null }
            val rawBase = a.optString("base").trim().ifEmpty { return@mapNotNull null }
            val base = runCatching {
                if (rawBase.contains("://")) BoxAddress.parse(rawBase)
                // The path validated as BoxAddress validates any path, by
                // parsing it behind a placeholder host.
                else server.copy(path = BoxAddress.parse("placeholder/" + rawBase.trimStart('/')).path)
            }.getOrNull() ?: return@mapNotNull null
            OpenHamShackApp(
                id = a.optString("id"),
                kind = kind,
                name = a.optString("name").ifBlank { a.optString("id") },
                version = a.optString("version"),
                base = base,
                running = a.optBoolean("running", true),
                txCapable = a.optBoolean("txCapable"),
            )
        }
    }
}
