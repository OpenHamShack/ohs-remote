package com.openhamshack.remote.shack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * An app's 401 against a stand-in controller: the platform's own device
 * list says whether the token stands, and only its "revoked" gives the
 * token up. The case that unpaired Brian's Fire TV - the Flex module
 * answering 401 proxy_required once while it restarted - must keep the
 * token and reconnect.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TokenGuardTest {
    private lateinit var server: MockWebServer
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** What the platform's `GET api/shack/v1/devices` answers. */
    @Volatile private var platform: MockResponse = MockResponse().setBody("""{"devices": [{"id": "dev_FAKETV"}]}""")
    /** How many more times the Flex module refuses, as one restarting does. */
    private val moduleRefusals = AtomicInteger(0)
    private val platformAsked = AtomicInteger(0)

    private val hello by lazy { String(javaClass.classLoader!!.getResourceAsStream("shack/flex/flex-hello.json")!!.readBytes()) }

    @Before fun up() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/api/shack/v1/devices" -> {
                    platformAsked.incrementAndGet()
                    if (request.getHeader("Authorization") != "Bearer shackdev_T") MockResponse().setResponseCode(401)
                        .setBody("""{"error": "This needs a device token.", "code": "credentials_required"}""")
                    else platform
                }
                "/shack-flex/ws" ->
                    if (moduleRefusals.getAndDecrement() > 0) MockResponse().setResponseCode(401)
                        .setBody("""{"error": "Not through the controller.", "code": "proxy_required"}""")
                    else MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(hello) }
                    })
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun down() {
        scope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun controller(token: String? = "shackdev_T") = ShackController(server.url("/").toString(), token)

    private fun until(what: String, ms: Long = 8000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun refusal(code: String) = MockResponse().setResponseCode(401).setBody("""{"error": "No.", "code": "$code"}""")

    @Test fun onlyThePlatformsOwnRevokedOrUnknownTokenCountsAsSignedOut() = runBlocking {
        val c = controller()
        assertEquals(TokenState.VALID, c.tokenState())
        val r = server.takeRequest()
        assertEquals("/api/shack/v1/devices", r.path)
        assertEquals("Bearer shackdev_T", r.getHeader("Authorization"))
        platform = refusal("token_revoked"); assertEquals(TokenState.REVOKED, c.tokenState())
        platform = refusal("bad_credentials"); assertEquals(TokenState.REVOKED, c.tokenState())
        // Not the platform's verdict on this token: kept.
        platform = refusal("proxy_required"); assertEquals(TokenState.UNKNOWN, c.tokenState())
        platform = MockResponse().setResponseCode(502).setBody("<html>Bad Gateway</html>"); assertEquals(TokenState.UNKNOWN, c.tokenState())
        platform = MockResponse().setResponseCode(404); assertEquals(TokenState.UNKNOWN, c.tokenState())   // a controller without the route
        val gone = ShackController("http://127.0.0.1:1/", "shackdev_T")
        assertEquals(TokenState.UNKNOWN, gone.tokenState())                                            // nothing answering at all
    }

    /** A Flex session whose 401s go to a guard over the stand-in controller, as ShackNav wires them. */
    private inner class Rig {
        val revoked = AtomicInteger(0)
        val reopened = AtomicInteger(0)
        var session: FlexSession? = null
        val guard = TokenGuard(scope, check = { controller().tokenState() },
            onRevoked = { revoked.incrementAndGet() },
            onStillGood = { reopened.incrementAndGet(); session?.close(); open() })

        fun open() {
            val app = ShackApp("shack-flex", Kind.FLEX, "flex", "Flex", server.url("/shack-flex/").toString(), running = true, txCapable = false)
            session = FlexSession(Words, app, "FAKESHACK", FlexClient(app.base, { "shackdev_T" }, OkHttpClient()),
                onSignedOut = { guard.refused() }).also { it.foreground(true) }
        }
    }

    @Test fun aModuleRestartingKeepsTheTokenAndReconnects() {
        moduleRefusals.set(1)
        val rig = Rig()
        rig.open()
        until("the guard to reopen the app") { rig.reopened.get() == 1 }
        until("the radio's state on the reopened session") { rig.session!!.ui.value.slice != null }
        assertEquals(0, rig.revoked.get())
        assertEquals(1, platformAsked.get())
        assertFalse(rig.guard.reconnecting.value)
        rig.session!!.close()
    }

    @Test fun aRevokedTokenStartsPairingAgain() {
        moduleRefusals.set(1_000)
        platform = refusal("token_revoked")
        val rig = Rig()
        rig.open()
        until("pairing to start") { rig.revoked.get() == 1 }
        Thread.sleep(300)
        assertEquals(0, rig.reopened.get())
        rig.session!!.close()
    }

    @Test fun refusalsTogetherAreOneQuestionAndRepeatsBackOff() {
        // The platform takes its time; meanwhile three sockets all say 401.
        var asked = 0
        val gate = java.util.concurrent.CountDownLatch(1)
        val reopened = AtomicInteger(0)
        val g = TokenGuard(scope, check = { asked++; gate.await(); TokenState.VALID }, onRevoked = {}, onStillGood = { reopened.incrementAndGet() })
        repeat(3) { g.refused() }
        until("the check to start") { asked == 1 }
        assertTrue(g.reconnecting.value)
        gate.countDown()
        until("the reconnect") { reopened.get() == 1 }
        assertEquals(1, asked)
        assertFalse(g.reconnecting.value)
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), (1..7).map { TokenGuard.backoffMs(it) })
    }

    private object Words : FlexWords {
        override fun noSlice() = ""
        override fun viewOnly() = ""
        override fun refused(http: Int) = ""
        override fun noAnswer(why: String) = ""
        override fun floor(hz: Long) = ""
        override fun ceiling(hz: Long) = ""
    }
}
