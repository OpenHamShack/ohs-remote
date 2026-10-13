package com.openhamshack.remote.shack

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import com.openhamshack.core.net.AuthLevel
import com.openhamshack.core.net.BoxAddress
import com.openhamshack.kind.stationflow.StationFlowKind
import com.openhamshack.kind.stationflow.StationFlowSession

/**
 * OpenHamShack Icom on a controller: the kind that came from stationflow-tv
 * (:kind-stationflow), handed what its host must provide - the app's base
 * and an HTTP client that carries the sign-in (StationFlowKind's comment).
 *
 * On a controller the sign-in is the device token, so the client adds it
 * as a bearer header to every request, the websockets' upgrades included
 * (OkHttp runs application interceptors on those too), and never into a
 * URL. The base is the controller's address plus the app's relative base
 * from the well-known document, as for every other kind; the module's TV
 * API (state, api/ws, the scope, the acknowledged audio) passes through the
 * controller's proxy unchanged.
 *
 * Receive only on every device, TV or phone: the kind's client can post
 * only tuning and the receive decoders (StationFlowClient.CONTROLS), and
 * the controller holds a TV's token at Control besides. The level handed to
 * the kind is Control because the well-known document does not say the
 * device's level per app; a View-only device learns it from the first
 * refusal, which the kind shows in the module's words.
 */
object IcomKind {
    /** The kind's address for an app base such as http://10.0.2.2:18600/shack-icom/. */
    fun address(appBase: String): BoxAddress {
        val u = appBase.toHttpUrl()
        return BoxAddress(host = u.host, port = u.port, path = u.encodedPath.trimEnd('/'), https = u.isHttps)
    }

    /** [base] with the device token, read at each request so forgetting it stops them all. */
    fun http(token: () -> String?, base: OkHttpClient = shackHttp): OkHttpClient = base.newBuilder()
        .addInterceptor { chain ->
            val t = token()
            chain.proceed(if (t == null) chain.request() else chain.request().newBuilder().header("Authorization", "Bearer $t").build())
        }
        .build()

    fun open(appBase: String, title: String, token: () -> String?, onSignedOut: () -> Unit): StationFlowSession =
        StationFlowKind.open(address(appBase), http(token), title, AuthLevel.Control) { onSignedOut() }
}
