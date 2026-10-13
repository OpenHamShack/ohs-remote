package com.openhamshack.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.openhamshack.remote.api.BoxAddress
import com.openhamshack.remote.api.OpenHamShackClient
import com.openhamshack.remote.data.DiscoveredBox
import com.openhamshack.remote.data.Discovery
import com.openhamshack.remote.data.PlayerSettings
import com.openhamshack.remote.data.SavedBox
import com.openhamshack.remote.shack.NewerControllerException
import com.openhamshack.remote.shack.ShackNav
import com.openhamshack.remote.ui.isTelevision

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val lbApp = app as OpenHamShackRemoteApp
    private val store = lbApp.store

    val boxes: StateFlow<List<SavedBox>> = store.boxes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playerSettings: StateFlow<PlayerSettings> =
        store.player.stateIn(viewModelScope, SharingStarted.Eagerly, store.defaultPlayer)

    private val _session = MutableStateFlow<BoxSession?>(null)
    val session: StateFlow<BoxSession?> = _session.asStateFlow()

    private val _clientId = MutableStateFlow("")
    val clientId: StateFlow<String> = _clientId.asStateFlow()

    private var foreground = false

    /** OpenHamShack controllers: pairing, their apps, and the NB/Radio kinds' sessions. */
    val shack = ShackNav(app, store, viewModelScope, lbApp.sendScope, isTelevision(app)) { store.clientId() }

    /** Boxes on this network (Bonjour); looked for only while the box list is on screen. */
    val discovery = Discovery(app)

    init {
        viewModelScope.launch { _clientId.value = store.clientId() }
        viewModelScope.launch {
            playerSettings.collect { s ->
                _session.value?.setPlayerSettings(s)
                shack.setPlayerSettings(s)
            }
        }
    }

    /** Validates the address; returns why not, or null when added. */
    fun addBox(name: String, address: String): String? {
        val addr = try {
            BoxAddress.parse(address)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
        // Saved as a controller when one answers there (its front door is
        // port 80 when no port was typed), else as a Classic box as before.
        viewModelScope.launch {
            val ctl = runCatching { ShackNav.probe(address, addr) }.getOrNull()
            if (ctl != null) store.addBox(name.ifBlank { ctl.second.name }, ctl.first, ctl.second.controllerId)
            else store.addBox(name, addr)
        }
        return null
    }

    /**
     * The TV's "Add and watch": nothing is saved until a OpenHamShack answers at
     * the address (typing on a remote is slow; a typo should say so now,
     * not as a black screen). A PIN, if typed, signs in before opening -
     * the TV needs it only to change channel. An address already saved is
     * reused, so this is also how a saved box gets its PIN on the TV.
     * [done] gets why not, or null as the box opens.
     */
    fun addAndWatch(address: String, pin: String, done: (String?) -> Unit) {
        val addr = try {
            BoxAddress.parse(address)
        } catch (e: IllegalArgumentException) {
            done(e.message)
            return
        }
        viewModelScope.launch {
            // A controller first: its front door answers /.well-known, and a
            // PIN means nothing to it (pairing replaces it).
            val ctl = try {
                ShackNav.probe(address, addr)
            } catch (_: NewerControllerException) {
                done(getApplication<Application>().getString(R.string.shack_newer_controller))
                return@launch
            }
            if (ctl != null) {
                val (at, wk) = ctl
                val box = boxes.value.firstOrNull { it.address == at && it.controllerId == wk.controllerId }
                    ?: store.addBox(wk.name, at, wk.controllerId)
                done(null)
                open(box)
                return@launch
            }
            val client = OpenHamShackClient(addr)
            val st = try {
                client.status()
            } catch (e: Exception) {
                done("No OpenHamShack answered at ${addr.display}: ${e.message ?: "no answer"}")
                return@launch
            }
            val token = if (pin.isBlank()) null else try {
                client.login(pin.trim())
            } catch (e: Exception) {
                done("PIN not accepted: ${e.message ?: "refused"}")
                return@launch
            }
            var box = boxes.value.firstOrNull { it.address == addr } ?: store.addBox(st.config.callsign, addr)
            if (token != null) {
                box = box.copy(session = token)
                store.updateBox(box)
            }
            done(null)
            open(box)
        }
    }

    fun removeBox(id: String) {
        viewModelScope.launch { store.removeBox(id) }
    }

    fun open(box: SavedBox) {
        _session.value?.close()
        if (box.isController) {
            _session.value = null
            shack.open(box)
            updateDiscovery()
            return
        }
        shack.close()
        viewModelScope.launch {
            val s = BoxSession(getApplication(), box, store.clientId(), store, lbApp.sendScope)
            s.setPlayerSettings(playerSettings.value)
            _session.value = s
            updateDiscovery()
            if (foreground) s.foreground(true)
        }
    }

    fun closeBox() {
        _session.value?.close()
        _session.value = null
        updateDiscovery()
    }

    /** A discovered box: the saved entry with that address if there is one, else saved now, then opened. */
    fun openDiscovered(d: DiscoveredBox) {
        viewModelScope.launch {
            val box = boxes.value.firstOrNull { it.address == d.address } ?: store.addBox(d.name, d.address)
            open(box)
        }
    }

    private fun updateDiscovery() {
        if (foreground && _session.value == null && shack.screen.value == null) discovery.start() else discovery.stop()
    }

    fun setPlayerSettings(s: PlayerSettings) {
        viewModelScope.launch { store.setPlayer(s) }
    }

    fun foreground(on: Boolean, reason: String = "the app went to the background") {
        foreground = on
        _session.value?.foreground(on, reason)
        shack.foreground(on, reason)
        updateDiscovery()
    }

    /** The screen went off: the transmitter stops at once, before onStop arrives. */
    fun screenOff() {
        _session.value?.ptt?.release("the screen was locked")
        shack.screenOff()
    }

    /** Leave a controller for the box list. */
    fun closeController() {
        shack.close()
        updateDiscovery()
    }

    override fun onCleared() {
        discovery.stop()
        shack.close()
        _session.value?.close()
        _session.value = null
    }
}
