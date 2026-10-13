package com.openhamshack.remote.shack

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.openhamshack.kind.stationflow.api.StationFlowClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Icom kind as Remote hands it a controller: the app's base as the
 * kind's address, and the device token added to its requests and its
 * websockets by the client Remote gives it - driven through the kind's own
 * StationFlowClient against a recording server.
 */
class IcomKindTest {
    private lateinit var server: MockWebServer
    private var token: String? = "shackdev_T"

    @Before fun up() { server = MockWebServer().apply { start() } }
    @After fun down() { server.shutdown() }

    private fun kindClient() = StationFlowClient(IcomKind.address(server.url("/shack-icom/").toString()), IcomKind.http({ token }, OkHttpClient()))

    @Test fun theAppsBaseBecomesTheKindsAddress() {
        val a = IcomKind.address("http://10.0.2.2:18600/shack-icom/")
        assertEquals("http://10.0.2.2:18600/shack-icom/api/state", a.url("api/state"))
        assertEquals("ws://10.0.2.2:18600/shack-icom/api/ws", a.wsUrl("api/ws"))
        // At the root of a subdomain, on port 80: no port and no path.
        assertEquals("http://icom.shack/api/state", IcomKind.address("http://icom.shack/").url("api/state"))
        assertEquals("https://[fd00::1]:8443/x/api/state", IcomKind.address("https://[fd00::1]:8443/x/").url("api/state"))
    }

    @Test fun stateIsAskedForUnderTheAppWithTheBearerToken() = runBlocking {
        val body = javaClass.classLoader!!.getResourceAsStream("shack/icom-state.json")!!.readBytes().decodeToString()
        server.enqueue(MockResponse().setBody(body))
        val st = kindClient().state()
        assertEquals("IC-9700", st.name)
        val r = server.takeRequest()
        assertEquals("/shack-icom/api/state", r.path)
        assertEquals("Bearer shackdev_T", r.getHeader("Authorization"))
    }

    @Test fun aForgottenTokenIsNoLongerSent() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": "Sign in to use Icom.", "code": "credentials_required"}"""))
        token = null
        runCatching { kindClient().state() }
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test fun theKindsSocketsCarryTheTokenToo() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("{}") }
        }))
        val got = CountDownLatch(1)
        val ws = kindClient().socket("api/ws", object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { got.countDown() }
        })
        assertTrue(got.await(5, TimeUnit.SECONDS))
        ws.cancel()
        val r = server.takeRequest()
        assertEquals("/shack-icom/api/ws", r.path)
        assertEquals("Bearer shackdev_T", r.getHeader("Authorization"))
    }
}
