package com.openhamshack.kind.stationflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import com.openhamshack.core.net.BoxAddress
import com.openhamshack.kind.stationflow.api.StationFlowClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The APRS tab tunes the radio, against a module that answers as the real
 * one does: its channel list, stations with no frequency of their own (the
 * module's records carry none), and a start route that moves nothing.
 *
 * Brian on the Fire TV, 10 October 2026: OK on the APRS tab "doesn't tune to
 * 144.800". These run the whole session - foreground, its fetches and
 * sockets - on a real thread, and look at what reached the module.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AprsTuneTest {
    private val server = MockWebServer()
    private val posts = CopyOnWriteArrayList<Pair<String, String>>()
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private var stations = """[{"call": "GB7XYZ-10", "last": 1.0, "symbol_name": "Digipeater"}]"""
    private lateinit var session: StationFlowSession

    @Before fun up() {
        Dispatchers.setMain(main)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.removePrefix("/icom/")
                if (request.method == "POST") {
                    posts += path to request.body.readUtf8()
                    return MockResponse().setBody("""{"ok": true}""")
                }
                return when (path.substringBefore('?')) {
                    "api/aprs" -> MockResponse().setBody("""{"stations": $stations}""")
                    // The stations socket answers at once and the channel list late, so the stations are first
                    // read with no channel to tune to - the order that left OK on them saying "no frequency".
                    "api/aprs/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send("""{"state": {"stations": $stations}}""")
                        }
                    })
                    "api/aprs/channels" -> MockResponse().setBodyDelay(500, TimeUnit.MILLISECONDS)
                        .setBody("""{"channels": [{"name": "Europe", "hz": 144800000, "mode": "FM"}]}""")
                    "api/log/spots" -> MockResponse().setBody("""{"spots": []}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error": "not here"}""")
                }
            }
        }
        server.start()
        val client = StationFlowClient(BoxAddress.parse("http://${server.hostName}:${server.port}/icom/"), OkHttpClient())
        session = StationFlowSession(client, "test")
    }

    @After fun down() {
        session.close()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    @Test fun `the APRS key tunes to the channel in FM with nothing heard yet`() {
        stations = "[]"
        session.foreground(true)
        waitFor("the channel") { session.aprsChannel.value == 144_800_000L }
        assertTrue(session.aprs.value.isEmpty())

        session.tuneToAprsChannel()
        waitFor("the frequency") { posts.any { it.first == "api/freq" } }
        // FM first, then the channel: never the new frequency in the old mode.
        assertEquals(listOf(
            "api/mode" to """{"mode":"FM"}""",
            "api/freq" to """{"hz":144800000}""",
        ), posts.toList())
        assertEquals(144_800_000L, session.pendingHz.value)
    }

    @Test fun `OK on a station heard tunes to the channel too, though it was heard before the channel was known`() {
        session.foreground(true)
        waitFor("the station") { session.aprs.value.isNotEmpty() }
        waitFor("a station with the channel to tune to") { session.aprs.value.singleOrNull()?.tuneHz == 144_800_000L }

        session.tuneToHeard(session.aprs.value.single())
        waitFor("the frequency") { posts.any { it.first == "api/freq" } }
        assertEquals(listOf(
            "api/mode" to """{"mode":"FM"}""",
            "api/freq" to """{"hz":144800000}""",
        ), posts.toList())
    }

    @Test fun `starting the receiver moves nothing, as the module's own route does not`() {
        session.foreground(true)
        session.startAprs()
        waitFor("the start") { posts.isNotEmpty() }
        Thread.sleep(300)
        assertEquals(listOf("api/aprs/start"), posts.map { it.first })
        assertEquals(JSONObject().toString(), JSONObject(posts.single().second).toString())
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            if (ok()) return
            Thread.sleep(20)
        }
        fail("never saw $what; posts: $posts")
    }
}
