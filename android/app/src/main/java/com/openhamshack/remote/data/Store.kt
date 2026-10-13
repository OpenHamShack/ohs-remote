package com.openhamshack.remote.data

import android.content.Context
import com.openhamshack.remote.ui.isTelevision
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import com.openhamshack.remote.api.BoxAddress
import java.util.UUID

/** A box the operator has added: where it is, and what this phone holds for it. */
data class SavedBox(
    val id: String,
    val name: String,
    val address: BoxAddress,
    /** The PIN session (`ohssession` cookie value), once the PIN has been entered here. */
    val session: String? = null,
    /** The SRT passphrase typed by hand, when the box would not include it. */
    val passphrase: String? = null,
    /**
     * Set when the address is an OpenHamShack controller rather than a
     * Classic box (its `/.well-known/openhamshack` answered): its
     * controllerId, which pairing and the token belong to.
     */
    val controllerId: String? = null,
    /** The controller's device token (`shackdev_…`), once paired. Sent only to this address. */
    val token: String? = null,
) {
    val isController: Boolean get() = controllerId != null
    val title: String get() = name.ifBlank { address.display }
}

/** How the receive picture is played. */
data class PlayerSettings(
    /**
     * libVLC network-caching in ms. 250 by default: the box's own player
     * runs low-latency mpv, and on a LAN 200-300 ms rides out Wi-Fi jitter
     * without adding a noticeable delay to an over.
     */
    val cachingMs: Int = 250,
    /** MediaCodec decoding. Off decodes in software, which some phones handle broken streams better with. */
    val hwDecode: Boolean = true,
) {
    companion object {
        /**
         * What a fresh install starts with: software decoding on a TV,
         * MediaCodec elsewhere. Measured on a Fire TV (MediaTek, October
         * 2026) at 2-5 % packet loss: both keep the picture moving now, but
         * its hardware decoders show a damaged frame as a white or grey
         * sheet until the next I-frame, where libavcodec conceals the hole
         * and the film stays recognisable - and libavcodec at 720p25 costs
         * a half to one of its four cores (top, 0-5 % loss). A phone keeps MediaCodec
         * (battery; not measured there). The setting overrides either way.
         */
        fun defaultFor(context: Context) = PlayerSettings(hwDecode = !isTelevision(context))
    }
}

private val Context.dataStore by preferencesDataStore("openhamshack-remote")

class Store(private val context: Context) {
    private val ds get() = context.dataStore

    val boxes: Flow<List<SavedBox>> = ds.data.map { decodeBoxes(it[BOXES]) }
    /** Before anything is saved (PlayerSettings.defaultFor). */
    val defaultPlayer = PlayerSettings.defaultFor(context)
    val player: Flow<PlayerSettings> = ds.data.map {
        PlayerSettings(it[CACHING] ?: defaultPlayer.cachingMs, it[HW] ?: defaultPlayer.hwDecode)
    }

    /**
     * This install's remote id for the PTT lease: random, made once, kept.
     * Not derived from the device (no ANDROID_ID): it only has to tell this
     * app's heartbeats from another's, and should say nothing else.
     */
    suspend fun clientId(): String {
        ds.data.first()[CLIENT_ID]?.let { return it }
        val id = "android-" + UUID.randomUUID().toString()
        ds.edit { if (it[CLIENT_ID] == null) it[CLIENT_ID] = id }
        return ds.data.first()[CLIENT_ID]!!
    }

    suspend fun addBox(name: String, address: BoxAddress, controllerId: String? = null): SavedBox {
        val box = SavedBox(UUID.randomUUID().toString(), name.trim(), address, controllerId = controllerId)
        ds.edit { it[BOXES] = encodeBoxes(decodeBoxes(it[BOXES]) + box) }
        return box
    }

    suspend fun updateBox(box: SavedBox) {
        ds.edit { prefs -> prefs[BOXES] = encodeBoxes(decodeBoxes(prefs[BOXES]).map { if (it.id == box.id) box else it }) }
    }

    suspend fun removeBox(id: String) {
        ds.edit { prefs -> prefs[BOXES] = encodeBoxes(decodeBoxes(prefs[BOXES]).filter { it.id != id }) }
    }

    suspend fun setPlayer(settings: PlayerSettings) {
        ds.edit {
            it[CACHING] = settings.cachingMs
            it[HW] = settings.hwDecode
        }
    }

    companion object {
        private val BOXES = stringPreferencesKey("boxes")
        private val CLIENT_ID = stringPreferencesKey("clientId")
        private val CACHING = intPreferencesKey("networkCachingMs")
        private val HW = booleanPreferencesKey("hwDecode")

        fun decodeBoxes(raw: String?): List<SavedBox> {
            if (raw.isNullOrBlank()) return emptyList()
            val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val addr = runCatching { BoxAddress(o.getString("host"), o.optInt("port", BoxAddress.DEFAULT_PORT)) }
                    .getOrNull() ?: return@mapNotNull null
                SavedBox(
                    id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                    name = o.optString("name"),
                    address = addr,
                    session = o.optString("session").ifEmpty { null },
                    passphrase = o.optString("passphrase").ifEmpty { null },
                    controllerId = o.optString("controllerId").ifEmpty { null },
                    token = o.optString("token").ifEmpty { null },
                )
            }
        }

        fun encodeBoxes(list: List<SavedBox>): String = JSONArray(list.map { b ->
            JSONObject().put("id", b.id).put("name", b.name).put("host", b.address.host).put("port", b.address.port)
                .apply {
                    b.session?.let { put("session", it) }
                    b.passphrase?.let { put("passphrase", it) }
                    b.controllerId?.let { put("controllerId", it) }
                    b.token?.let { put("token", it) }
                }
        }).toString()
    }
}
