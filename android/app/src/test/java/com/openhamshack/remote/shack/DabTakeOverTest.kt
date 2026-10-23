package com.openhamshack.remote.shack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/**
 * A DAB station this device does not know holding the receiver - the TV's
 * own from before the app was reinstalled (Brian, 23/10/2026) - is taken
 * over by the DAB panel, against a stand-in Radio: on its block it is
 * shown and steered as it is, and on another block it is stopped before
 * the new one starts, rather than the start being refused as busy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DabTakeOverTest {
    private lateinit var server: MockWebServer
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "dab-take-test-main") }.asCoroutineDispatcher()
    private val sendScope = CoroutineScope(Dispatchers.Default)
    /** What reached the stand-in, in order: "POST 5A", "DELETE ds_old", "GET ds_old". */
    private val calls = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var held = true

    @Before fun up() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty()
                return when {
                    path == "/shack-radio/api/events" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"kind": "hello", "status": {"kind": "radio", "running": true}}""")
                        }
                    })
                    path == "/shack-radio/api/state" -> MockResponse().setBody(
                        if (!held) """{"vfo": 27245000, "mode": "fm", "rxOwner": {}}"""
                        else """{"vfo": 213360000, "mode": "fm", "rxOwner": {"by": "dab", "session": "ds_old", "block": "10C",
                            "freqHz": 213360000, "text": "The receiver is playing DAB from block 10C"}}""")
                    path == "/shack-radio/api/status" -> MockResponse().setBody("""{"kind": "radio", "running": true}""")
                    path == "/shack-radio/api/decoders/sessions" && request.method == "POST" -> {
                        val block = JSONObject(request.body.readUtf8()).getJSONObject("params").getString("block")
                        calls += "POST $block"
                        if (held) MockResponse().setResponseCode(409).setBody(
                            """{"error": "A DAB station on block 10C already has the receiver: stop it first", "code": "receiver_busy"}""")
                        else MockResponse().setResponseCode(201).setBody(JSONObject().put("id", "ds$block").toString())
                    }
                    path.startsWith("/shack-radio/api/decoders/sessions/") && request.method == "DELETE" -> {
                        val id = path.substringAfterLast('/')
                        calls += "DELETE $id"
                        if (id == "ds_old") held = false
                        MockResponse().setBody("""{"stopped": true}""")
                    }
                    path.startsWith("/shack-radio/api/decoders/sessions/") -> {
                        calls += "GET ${path.substringAfterLast('/')}"
                        val r = JSONObject().put("rev", 1).put("sync", true).put("ensemble", JSONObject().put("label", "Mux"))
                            .put("services", JSONArray().put(JSONObject().put("sid", "C1").put("label", "One").put("kind", "dab")))
                            .put("playing", "C1")
                        MockResponse().setBody(JSONObject().put("state", "running").put("results", JSONArray().put(r)).toString())
                    }
                    else -> MockResponse().setResponseCode(404).setBody("""{"error": "Not found.", "code": "not_found"}""")
                }
            }
        }
        server.start()
    }

    @After fun down() {
        Thread.sleep(500)
        sendScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun until(what: String, ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what; calls $calls")
            Thread.sleep(10)
        }
    }

    private fun open(): EngineSession {
        val app = ShackApp("shack-radio", Kind.RADIO, "radio", "Radio", "shack-radio/", running = true, txCapable = true)
        val s = EngineSession(app, "FAKESHACK", ShackController(server.url("/").toString(), "shackdev_T").app(app), "remote-test",
            canTransmit = false, sendScope = sendScope)
        s.foreground(true)
        until("the held receiver") { s.ui.value.holder?.session == "ds_old" }
        return s
    }

    @Test fun onItsOwnBlockTheHeldStationIsShownAsItIs() {
        val s = open()
        s.openDab(DabBlocks.byLabel("10C")!!)
        until("the held session's feed") { s.ui.value.dab?.sync == true }
        assertEquals("ds_old", s.ui.value.dab?.session)
        assertTrue("nothing started or stopped: $calls", calls.none { it.startsWith("POST") || it.startsWith("DELETE") })
        s.close()
    }

    @Test fun onAnotherBlockTheHeldStationIsStoppedFirst() {
        val s = open()
        s.openDab(DabBlocks.byLabel("5A")!!)
        until("the new session") { s.ui.value.dab?.session == "ds5A" }
        assertEquals(listOf("DELETE ds_old", "POST 5A"), calls.filter { !it.startsWith("GET") }.take(2))
        assertEquals("", s.ui.value.dab?.problem)
        s.close()
    }
}
