package com.openhamshack.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import com.openhamshack.core.net.BoxAddress
import com.openhamshack.core.net.SessionCookie
import java.util.UUID

/** A box the operator has added: where it is, what kind, and the session this device holds for it. */
data class SavedBox(
    val id: String,
    val name: String,
    val kind: String,
    val address: BoxAddress,
    /** The box's session cookie, once signed in here. Never the password or PIN. */
    val session: SessionCookie? = null,
    /** A CA certificate (PEM) the operator chose to trust for this box; see BoxTrust. */
    val trustedCa: String? = null,
) {
    val title: String get() = name.ifBlank { address.display }
}

/**
 * The saved boxes, in app-private DataStore (the app sets allowBackup false,
 * so session cookies do not leave the device in a backup).
 */
class SavedBoxes(private val ds: DataStore<Preferences>) {
    val boxes: Flow<List<SavedBox>> = ds.data.map { decode(it[BOXES]) }

    /** The box at [address] of [kind], updated with [name] and [session] if given; added if new. */
    suspend fun upsert(
        kind: String, address: BoxAddress, name: String,
        session: SessionCookie? = null, trustedCa: String? = null,
    ): SavedBox {
        var out: SavedBox? = null
        ds.edit { prefs ->
            val list = decode(prefs[BOXES])
            val existing = list.firstOrNull { it.address == address && it.kind == kind }
            val box = existing?.copy(
                name = name.ifBlank { existing.name },
                session = session ?: existing.session,
                trustedCa = trustedCa ?: existing.trustedCa,
            ) ?: SavedBox(UUID.randomUUID().toString(), name.trim(), kind, address, session, trustedCa)
            out = box
            prefs[BOXES] = encode(if (existing != null) list.map { if (it.id == box.id) box else it } else list + box)
        }
        return out!!
    }

    suspend fun setSession(id: String, session: SessionCookie?) {
        ds.edit { prefs -> prefs[BOXES] = encode(decode(prefs[BOXES]).map { if (it.id == id) it.copy(session = session) else it }) }
    }

    suspend fun setTrustedCa(id: String, pem: String?) {
        ds.edit { prefs -> prefs[BOXES] = encode(decode(prefs[BOXES]).map { if (it.id == id) it.copy(trustedCa = pem) else it }) }
    }

    suspend fun remove(id: String) {
        ds.edit { prefs -> prefs[BOXES] = encode(decode(prefs[BOXES]).filter { it.id != id }) }
    }

    companion object {
        private val BOXES = stringPreferencesKey("boxes")

        fun decode(raw: String?): List<SavedBox> {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val addr = runCatching { BoxAddress.parse(o.getString("address")) }.getOrNull() ?: return@mapNotNull null
                SavedBox(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    name = o.optString("name"),
                    kind = o.optString("kind").ifEmpty { DiscoveredBox.LEGACY_KIND },
                    address = addr,
                    session = SessionCookie.parse(o.optString("session")),
                    trustedCa = o.optString("trustedCa").ifEmpty { null },
                )
            }
        }

        fun encode(list: List<SavedBox>): String = JSONArray(list.map { b ->
            JSONObject().put("id", b.id).put("name", b.name).put("kind", b.kind)
                // The address as it would be typed: one string that round-trips through parse.
                .put("address", (if (b.address.https) "https://" else "http://") + b.address.urlHost + ":" + b.address.port + b.address.path)
                .apply {
                    b.session?.let { put("session", it.header) }
                    b.trustedCa?.let { put("trustedCa", it) }
                }
        }).toString()
    }
}
