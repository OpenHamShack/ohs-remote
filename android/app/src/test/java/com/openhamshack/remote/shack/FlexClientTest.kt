package com.openhamshack.remote.shack

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The Flex client against a recording server standing where the controller
 * would: the token as a bearer header (never in a URL), the module's own
 * paths under the app's base, and no way to send anything that transmits.
 */
class FlexClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @Before fun up() { server = MockWebServer().apply { start() } }
    @After fun down() { server.shutdown() }

    private fun client(token: String? = "shackdev_T") = FlexClient(server.url("/shack-flex/").toString(), { token }, http)

    @Test fun commandsGoToTheModulesRouteWithTheBearerToken() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok": true, "sent": ["slice tune 0 14.202000 autopan=0"]}"""))
        client().command("slice.tune", JSONObject().put("slice_id", 0).put("frequency", 14.202))
        val r = server.takeRequest()
        assertEquals("/shack-flex/api/command", r.path)
        assertEquals("Bearer shackdev_T", r.getHeader("Authorization"))
        val body = JSONObject(r.body.readUtf8())
        assertEquals("slice.tune", body.getString("action"))
        assertEquals(14.202, body.getJSONObject("arguments").getDouble("frequency"), 0.0)
        assertTrue("token in the URL", !r.path!!.contains("shackdev"))
    }

    @Test fun nothingThatTransmitsCanBeSent() = runBlocking {
        // The module's keying and transmit-setting actions, and a made-up one:
        // each refused here, before a byte leaves.
        for (action in listOf("slice.tx", "transmit.set", "xmit", "atu.start", "tune", "slice.xit", "anything")) {
            try {
                client().command(action, JSONObject().put("slice_id", 0))
                fail("$action was sent")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun aRefusalCarriesTheControllersSentence() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody(
            """{"error": "This needs the Control level; your level for Flex is View.", "code": "level_too_low"}"""))
        try {
            client().band("20", 0)
            fail("not refused")
        } catch (e: ShackException) {
            assertEquals(403, e.http)
            assertEquals("level_too_low", e.code)
            assertTrue(e.message!!.contains("Control level"))
        }
        val r = server.takeRequest()
        assertEquals("/shack-flex/api/band", r.path)
        assertEquals(JSONObject().put("band", "20").put("slice_id", 0).toString(), JSONObject(r.body.readUtf8()).toString())
    }

    @Test fun theSocketIsOpenedWithTheTokenAsAHeader() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("""{"type": "hello"}""") }
        }))
        val got = CountDownLatch(1)
        val ws = client().socket(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { got.countDown() }
        })
        assertTrue(got.await(5, TimeUnit.SECONDS))
        ws.cancel()
        val r = server.takeRequest()
        assertEquals("/shack-flex/ws", r.path)
        assertEquals("Bearer shackdev_T", r.getHeader("Authorization"))
    }

    @Test fun stateParsesTheModulesEnvelope() = runBlocking {
        val body = javaClass.classLoader!!.getResourceAsStream("shack/flex/flex-state.json")!!.readBytes().decodeToString()
        server.enqueue(MockResponse().setBody(body))
        val (st, tables) = client().state()
        assertEquals(14_201_000L, st.worked!!.hz)
        assertTrue(tables.modes.isNotEmpty())
        assertEquals("/shack-flex/api/state", server.takeRequest().path)
    }
}
