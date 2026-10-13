package com.openhamshack.remote.shack

import android.app.Application
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.openhamshack.remote.BoxSession
import com.openhamshack.remote.PlatformApp
import com.openhamshack.remote.api.BoxAddress
import com.openhamshack.remote.data.PlayerSettings
import com.openhamshack.remote.data.SavedBox
import com.openhamshack.remote.data.Store
import com.openhamshack.kind.stationflow.StationFlowSession

/** Why a TV's pairing stopped. */
enum class PairEnd { DENIED, EXPIRED }

/** Where the operator is on a controller, before an app's own screen. */
sealed interface ShackScreen {
    val box: SavedBox

    /** A TV showing its code until someone approves it ([code] null while it is being asked for). */
    data class Pairing(override val box: SavedBox, val code: String?, val ended: PairEnd? = null, val problem: Problem? = null) : ShackScreen

    /** A phone or tablet: the controller's user name and password, once. */
    data class SignIn(override val box: SavedBox, val problem: Problem? = null, val needTotp: Boolean = false, val busy: Boolean = false) : ShackScreen

    /** The controller's apps; [wk] null while it is being asked. */
    data class Apps(override val box: SavedBox, val wk: WellKnown?, val problem: Problem? = null) : ShackScreen
}

/**
 * Remote on an OpenHamShack controller: pairing, the app list, and the
 * kind's session. The Classic path (BoxSession) is untouched; a saved box
 * with a controllerId comes here instead (AppViewModel.open).
 *
 * Kinds are chosen by [Kind.of] on the app's `kind` and nothing else.
 */
class ShackNav(
    private val app: Application,
    private val store: Store,
    private val scope: CoroutineScope,
    private val sendScope: CoroutineScope,
    private val tv: Boolean,
    private val clientId: suspend () -> String,
) {
    private val _screen = MutableStateFlow<ShackScreen?>(null)
    val screen: StateFlow<ShackScreen?> = _screen.asStateFlow()
    private val _engine = MutableStateFlow<EngineSession?>(null)
    val engine: StateFlow<EngineSession?> = _engine.asStateFlow()
    /** QO-100 WB on this controller: the Classic box's screens over the app's base (BoxSession with a PlatformApp). */
    private val _wb = MutableStateFlow<BoxSession?>(null)
    val wb: StateFlow<BoxSession?> = _wb.asStateFlow()
    private val _datv = MutableStateFlow<DatvSession?>(null)
    val datv: StateFlow<DatvSession?> = _datv.asStateFlow()
    /** OpenHamShack Icom: the kind's own session; its screens follow the lifecycle themselves. */
    private val _icom = MutableStateFlow<StationFlowSession?>(null)
    val icom: StateFlow<StationFlowSession?> = _icom.asStateFlow()
    private val _flex = MutableStateFlow<FlexSession?>(null)
    val flex: StateFlow<FlexSession?> = _flex.asStateFlow()
    private var playerSettings = PlayerSettings()

    private var pairing: Job? = null
    private var foreground = false
    /** The app open now, so a transient 401 can open it again. */
    private var openedApp: ShackApp? = null

    /** An app's 401 is checked with the platform before the token is given up (TokenGuard). */
    private val guard = TokenGuard(
        scope,
        check = { _screen.value?.box?.let { controller(it).tokenState() } ?: TokenState.UNKNOWN },
        onRevoked = { forgetToken() },
        onStillGood = { openedApp?.let { openApp(it) } ?: refreshApps() },
    )
    /** A refused token is being checked with the controller, or a reconnect waited for. */
    val reconnecting: StateFlow<Boolean> = guard.reconnecting

    private fun controller(box: SavedBox) = ShackController(baseUrl(box.address), box.token)

    fun open(box: SavedBox) {
        close()
        if (box.token == null) startAuth(box) else listApps(box)
    }

    private fun startAuth(box: SavedBox) {
        if (!tv) {
            _screen.value = ShackScreen.SignIn(box)
            return
        }
        _screen.value = ShackScreen.Pairing(box, null)
        pairing = scope.launch {
            val c = controller(box)
            try {
                val p = c.pairStart(deviceName(), clientId())
                _screen.value = ShackScreen.Pairing(box, p.code)
                val until = System.currentTimeMillis() + p.expiresIn * 1000L
                while (System.currentTimeMillis() < until) {
                    delay(p.pollEvery * 1000L)
                    when (val r = c.pairPoll(p)) {
                        is PairPoll.Approved -> {
                            val saved = box.copy(token = r.token)
                            store.updateBox(saved)
                            listApps(saved)
                            return@launch
                        }
                        PairPoll.Denied -> {
                            _screen.value = ShackScreen.Pairing(box, p.code, PairEnd.DENIED)
                            return@launch
                        }
                        PairPoll.Pending -> Unit
                    }
                }
                _screen.value = ShackScreen.Pairing(box, p.code, PairEnd.EXPIRED)
            } catch (e: ShackException) {
                _screen.value = ShackScreen.Pairing(box, null, if (e.http == 410) PairEnd.EXPIRED else null, Problem(e.http, e.message.orEmpty()))
            } catch (e: Exception) {
                _screen.value = ShackScreen.Pairing(box, null, null, Problem(null, e.message.orEmpty()))
            }
        }
    }

    /** Ask for a new code (after it expired or was refused). */
    fun retryPairing() {
        _screen.value?.box?.let { b -> pairing?.cancel(); startAuth(b.copy(token = null)) }
    }

    fun signIn(user: String, password: String, totp: String?) {
        val s = _screen.value as? ShackScreen.SignIn ?: return
        _screen.value = s.copy(busy = true, problem = null)
        scope.launch {
            try {
                val token = controller(s.box).signIn(user.trim(), password, totp, deviceName(), deviceKind(), clientId())
                val saved = s.box.copy(token = token)
                store.updateBox(saved)
                listApps(saved)
            } catch (e: ShackException) {
                _screen.value = s.copy(busy = false, needTotp = s.needTotp || e.code == "totp_required",
                    problem = if (e.code == "totp_required") null else Problem(e.http, e.message.orEmpty()))
            } catch (e: Exception) {
                _screen.value = s.copy(busy = false, problem = Problem(null, e.message.orEmpty()))
            }
        }
    }

    private fun listApps(box: SavedBox) {
        _screen.value = ShackScreen.Apps(box, null)
        scope.launch {
            _screen.value = try {
                ShackScreen.Apps(box, controller(box).wellKnown() ?: throw ShackException(404, "not_controller", ""))
            } catch (e: ShackException) {
                ShackScreen.Apps(box, null, Problem(e.http, e.message.orEmpty()))
            } catch (e: Exception) {
                ShackScreen.Apps(box, null, Problem(null, e.message.orEmpty()))
            }
        }
    }

    fun refreshApps() { _screen.value?.box?.let { listApps(it) } }

    /**
     * Open [a] with its kind's screen: NB and Radio, QO-100 WB, DATV, Icom
     * and Flex. A kind this build does not know is listed, not opened. Every URL is the controller's base
     * (what the operator typed) plus the app's relative base from the
     * well-known document; the token is read from the saved entry at each
     * request, so forgetting it stops them all.
     */
    fun openApp(a: ShackApp) {
        val s = _screen.value as? ShackScreen.Apps ?: return
        if (!a.kind.opens) return
        val name = s.wk?.name ?: s.box.title
        val base = baseUrl(s.box.address) + a.base
        val token = { (_screen.value?.box ?: s.box).token }
        scope.launch {
            closeApp()
            openedApp = a
            when {
                a.kind.isEngine -> {
                    // Radio's band buttons remember per box: this controller (its id, which
                    // outlives an address change) and this app on it.
                    val boxKey = (s.wk?.controllerId?.takeIf { it.isNotEmpty() } ?: s.box.controllerId ?: s.box.id) + "|" + a.id
                    val e = EngineSession(a, name, controller(s.box).app(a), clientId(),
                        canTransmit = !tv && a.txCapable, sendScope = sendScope, autoOpen = PanelPrefs.autoOpen(app),
                        boxKey = boxKey, bandMemory = BandMemoryPrefs(app), planData = { RadioPlanAssets.load(app) })
                    _engine.value = e
                    if (foreground) e.foreground(true)
                }
                a.kind == Kind.QO100WB -> {
                    // A TV gets the receive-only TV screen and a token capped
                    // at Control; the transmit panel exists only on phones.
                    val w = BoxSession(app, s.box, clientId(), store, sendScope,
                        PlatformApp(base.trimEnd('/'), a.name, name, token, onSignedOut = { tokenRefused() }))
                    w.setPlayerSettings(playerSettings)
                    _wb.value = w
                    if (foreground) w.foreground(true)
                }
                a.kind == Kind.DATV -> {
                    val d = DatvSession(app, a, name, DatvClient(base, token), base, token, onSignedOut = { tokenRefused() })
                    d.setPlayerSettings(playerSettings)
                    _datv.value = d
                    if (foreground) d.foreground(true)
                }
                // The Icom kind starts and stops its sockets with the screen
                // (StationFlowTvScreen / StationFlowTouchScreen), not here.
                a.kind == Kind.ICOM -> _icom.value = IcomKind.open(base, name, token, onSignedOut = { tokenRefused() })
                a.kind == Kind.FLEX -> {
                    val f = FlexSession(FlexWords.of(app), a, name, FlexClient(base, token), onSignedOut = { tokenRefused() },
                        autoOpen = PanelPrefs.autoOpen(app))
                    _flex.value = f
                    if (foreground) f.foreground(true)
                }
            }
        }
    }

    fun setPlayerSettings(s: PlayerSettings) {
        playerSettings = s
        _wb.value?.setPlayerSettings(s)
        _datv.value?.setPlayerSettings(s)
    }

    /** Back from an app to the controller's list. */
    fun closeApp() {
        openedApp = null
        _engine.value?.close()
        _engine.value = null
        _wb.value?.close()
        _wb.value = null
        _datv.value?.close()
        _datv.value = null
        _icom.value?.close()
        _icom.value = null
        _flex.value?.close()
        _flex.value = null
    }

    fun close() {
        guard.cancel()
        pairing?.cancel()
        closeApp()
        _screen.value = null
    }

    /**
     * Something behind the controller answered 401, or the person chose
     * "pair again" / "sign in" after one. The token is forgotten only if the
     * controller itself says it is revoked; otherwise the app reconnects.
     */
    fun tokenRefused() = guard.refused()

    /** The controller revoked the token (or was reinstalled): forget it and pair again. Only via [tokenRefused]. */
    private fun forgetToken() {
        val b = _screen.value?.box ?: return
        scope.launch {
            val fresh = b.copy(token = null)
            store.updateBox(fresh)
            closeApp()
            startAuth(fresh)
        }
    }

    fun foreground(on: Boolean, reason: String) {
        foreground = on
        _engine.value?.foreground(on, reason)
        _wb.value?.foreground(on, reason)
        _datv.value?.foreground(on)
        _flex.value?.foreground(on)
    }

    fun screenOff() {
        _engine.value?.ptt?.release("the screen was locked")
        _wb.value?.ptt?.release("the screen was locked")
    }

    private fun deviceName(): String =
        Settings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun deviceKind(): String = if (app.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"

    companion object {
        /** http://host:port/ for an address: the base every relative reference resolves against. */
        fun baseUrl(a: BoxAddress) = a.baseUrl + "/"

        /**
         * Is there a controller at what the operator typed? A controller's
         * front door is port 80 and a Classic box's 8080 (BoxAddress's
         * default), so an address typed without a port is asked at 80
         * first. Returns the address that answered and its document, or
         * null (then the caller tries it as a Classic box).
         */
        suspend fun probe(typed: String, parsed: BoxAddress): Pair<BoxAddress, WellKnown>? {
            val candidates = if (hasPort(typed)) listOf(parsed) else listOf(parsed.copy(port = 80), parsed)
            for (a in candidates) {
                val wk = try {
                    ShackController(baseUrl(a)).wellKnown()
                } catch (e: NewerControllerException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (wk != null) return a to wk
            }
            return null
        }

        /** Whether the operator typed a port: "host:1234", "[v6]:1234"; a bare IPv6 literal has none. */
        fun hasPort(typed: String): Boolean {
            val s = typed.trim().removePrefix("http://").removePrefix("HTTP://").trimEnd('/')
            if (s.startsWith("[")) return s.substringAfter("]", "").startsWith(":")
            return s.count { it == ':' } == 1
        }
    }
}
