package com.openhamshack.remote.shack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * A whole Flex session against a stand-in for the module behind the
 * controller: the websocket's hello (the real module's, captured, with the
 * slice moved where each test needs it) and api/command answering as each
 * test says. What is checked is what the radio would be asked for, what the
 * screen would say, and that nothing escapes to end the app - the session's
 * main thread records any uncaught exception, as Android's would crash on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlexSessionTest {
    private lateinit var server: MockWebServer
    private val crashes = Collections.synchronizedList(mutableListOf<Throwable>())
    private val main = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flex-test-main").apply { setUncaughtExceptionHandler { _, e -> crashes += e } }
    }.asCoroutineDispatcher()
    private val collector = CoroutineScope(Dispatchers.Default)

    /** api/command and api/band bodies, in order. */
    private val sent = Collections.synchronizedList(mutableListOf<JSONObject>())
    /** The next answers to api/command or api/band; a plain OK once these run out. */
    private val replies = ConcurrentLinkedQueue<MockResponse>()
    private val said = Collections.synchronizedList(mutableListOf<String>())
    private var sliceMhz = 14.2
    /** Where api/state says the radio is; null for the hello's frequency. */
    @Volatile private var stateMhz: Double? = null

    private val words = object : FlexWords {
        override fun noSlice() = "no slice"
        override fun viewOnly() = "view only"
        override fun refused(http: Int) = "refused $http"
        override fun noAnswer(why: String) = "no answer"
        override fun floor(hz: Long) = "floor $hz"
        override fun ceiling(hz: Long) = "ceiling $hz"
    }

    private fun hello(mhz: Double = sliceMhz): String {
        val o = JSONObject(String(javaClass.classLoader!!.getResourceAsStream("shack/flex/flex-hello.json")!!.readBytes()))
        o.getJSONObject("state").getJSONObject("slices").getJSONObject("0").put("frequency", mhz)
        return o.toString()
    }

    @Before fun up() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/shack-flex/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(hello()) }
                })
                "/shack-flex/api/command", "/shack-flex/api/band" -> {
                    sent += JSONObject(request.body.readUtf8())
                    replies.poll() ?: MockResponse().setBody("""{"ok": true}""")
                }
                // api/state is the hello's state and tables without the type.
                "/shack-flex/api/state" -> MockResponse().setBody(JSONObject(hello(stateMhz ?: sliceMhz)).apply { remove("type") }.toString())
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun down() {
        collector.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    // No silent retry, so a dropped connection reaches the session as it would on a TV whose Wi-Fi blinked.
    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    private fun open(): FlexSession {
        val app = ShackApp("shack-flex", Kind.FLEX, "flex", "Flex", server.url("/shack-flex/").toString(), running = true, txCapable = false)
        val s = FlexSession(words, app, "FAKESHACK", FlexClient(app.base, { "shackdev_T" }, http), onSignedOut = {})
        collector.launch { s.messages.collect { said += it } }
        s.foreground(true)
        until("the hello") { s.ui.value.slice != null }
        return s
    }

    private fun until(what: String, ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    /** Let the session's sending loop drain, then the tunes the radio was asked for, in Hz. */
    private fun tunes(): List<Long> {
        Thread.sleep(300)
        return sent.toList().filter { it.optString("action") == "slice.tune" }
            .map { Math.round(it.getJSONObject("arguments").getDouble("frequency") * 1e6) }
    }

    @Test fun holdingLeftAtTheBottomStopsThereAndSaysSo() {
        sliceMhz = 0.0301
        val s = open()
        repeat(6) { s.tuneBy(-1) }                         // a held Left, 100 Hz steps
        until("the floor hint") { "floor 30000" in said }
        assertEquals(listOf(30_000L), tunes())             // once, to the floor; never below it
        assertEquals(30_000L, s.ui.value.dialHz)
        assertTrue("crashed: $crashes", crashes.isEmpty())
        s.close()
    }

    @Test fun aBigStepNearTheBottomLandsOnTheFloorNotBelowZero() {
        sliceMhz = 0.5
        val s = open()
        s.tuneTo(FlexTuning.tune(500_000, 1_000_000, -1))
        until("the floor hint") { "floor 30000" in said }
        assertEquals(listOf(30_000L), tunes())
        assertTrue("crashed: $crashes", crashes.isEmpty())
        s.close()
    }

    @Test fun theTopOfTheReceiverStopsTuningToo() {
        sliceMhz = 53.999
        val s = open()
        repeat(3) { s.tuneTo(s.ui.value.dialHz!! + 1000) }
        until("the ceiling hint") { "ceiling 54000000" in said }
        assertEquals(listOf(54_000_000L), tunes())
        s.close()
    }

    @Test fun aRefusedTuneSaysTheModulesSentenceAndTheAppCarriesOn() {
        replies += MockResponse().setResponseCode(400).setBody("""{"error": "Flex tunes 30 kHz to 54 MHz; 0.000 MHz is outside that."}""")
        val s = open()
        s.tuneBy(+1)
        until("the refusal") { said.isNotEmpty() }
        assertEquals("Flex tunes 30 kHz to 54 MHz; 0.000 MHz is outside that.", said.single())
        until("the dial to return to the radio's") { s.ui.value.pendingHz == null }
        // The next press is sent as usual.
        s.tuneBy(+1)
        assertEquals(listOf(14_200_100L, 14_200_100L), tunes())
        assertTrue("crashed: $crashes", crashes.isEmpty())
        s.close()
    }

    @Test fun aRefusedTuneShowsWhereTheRadioReallyIs() {
        // Brian's Fire TV: the module refused 3.5 kHz and the readout kept
        // the refused value. The socket's last word was 14.2 MHz, but the
        // radio is on 7.1 MHz by now (another client moved it) - the module's
        // state is the truth, asked for after the refusal.
        replies += MockResponse().setResponseCode(400)
            .setBody("""{"error": "0.003500 MHz is outside what this radio receives (0.03-54 MHz)"}""")
        stateMhz = 7.1
        val s = open()
        s.tuneTo(14_100_000)
        until("the refusal") { said.isNotEmpty() }
        until("the readout to be the radio's") { s.ui.value.dialHz == 7_100_000L }
        assertNull(s.ui.value.pendingHz)
        // The next step is from the radio's frequency, not the refused one.
        s.tuneBy(-1)
        assertEquals(listOf(14_100_000L, 7_099_900L), tunes())
        s.close()
    }

    @Test fun anyServerErrorIsSaidNeverThrown() {
        // A proxy's HTML 500, a 502 with no body, refusals of a mode change
        // and a band change, and a dropped connection (last: the client
        // does not retry here, so a dropped connection spoils the pooled one
        // for whatever comes straight after it).
        replies += MockResponse().setResponseCode(500).setBody("<html><body>Internal Server Error</body></html>")
        replies += MockResponse().setResponseCode(502)
        replies += MockResponse().setResponseCode(400).setBody("""{"error": "No such mode: XYZ."}""")
        replies += MockResponse().setResponseCode(400).setBody("""{"error": "No such band: 99."}""")
        replies += MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        val s = open()
        s.tuneBy(+1)
        until("the 500") { said.size == 1 }
        until("the dial to settle") { s.ui.value.pendingHz == null }
        s.tuneBy(+1)
        until("the 502") { said.size == 2 }
        until("the dial to settle") { s.ui.value.pendingHz == null }
        s.setMode("XYZ")
        until("the mode refusal") { said.size == 3 }
        s.cycleBandTo("99")
        until("the band refusal") { said.size == 4 }
        s.tuneBy(+1)
        until("the drop", ms = 15_000) { said.size == 5 }
        assertEquals(listOf("refused 500", "refused 502", "No such mode: XYZ.", "No such band: 99.", "no answer"), said.toList())
        until("the dial to settle") { s.ui.value.pendingHz == null }
        assertTrue("crashed: $crashes", crashes.isEmpty())
        s.close()
    }
}
