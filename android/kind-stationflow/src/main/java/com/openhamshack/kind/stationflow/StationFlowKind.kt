package com.openhamshack.kind.stationflow

import okhttp3.OkHttpClient
import com.openhamshack.core.net.AuthLevel
import com.openhamshack.core.net.BoxAddress
import com.openhamshack.kind.stationflow.api.StationFlowClient

/**
 * StationFlow as one kind of box in the OpenHamShack remote app.
 *
 * The host finds the box, signs in, and hands over the app's base URL, an
 * HTTP client that carries the session, and the level the platform gave
 * this device (StationFlow itself no longer reports one - TV-API.md section
 * 2); this kind does the rest:
 *
 * ```
 * val s = StationFlowKind.open(base, http, "IC-9700") { why -> /* ask for sign-in again */ }
 * StationFlowTvScreen(s, onExit = { s.close(); back() })
 * ```
 *
 * Receive only, on every platform it will ever run on: see StationFlowClient.
 */
object StationFlowKind {
    /** TXT `kind` and the Dock360 app id family name. */
    const val KIND = "stationflow"

    fun open(
        base: BoxAddress, http: OkHttpClient, title: String,
        level: AuthLevel = AuthLevel.Control,
        onSignedOut: (String) -> Unit = {},
    ): StationFlowSession = StationFlowSession(StationFlowClient(base, http), title, level, onSignedOut)
}
