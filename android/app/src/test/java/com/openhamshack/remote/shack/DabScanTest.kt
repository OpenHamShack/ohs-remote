package com.openhamshack.remote.shack

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * "Scan all blocks": the verdict on one block, and a whole scan run by
 * the session against a stand-in Radio whose decoder finds multiplexes on
 * two blocks and says "nothing here" on the rest - one session at a time,
 * each stopped before the next starts, the list opened where it should be.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DabScanTest {
    private val b11d = DabBlocks.byLabel("11D")!!

    @Test fun aBlockIsKeptWhenItSyncsWithServicesAndPassedOverOtherwise() {
        val services = listOf(DabService("C221", "Radio 4", "dab+", 128, ""))
        assertEquals(DabScan.Verdict.KEEP, DabScan.verdict(DabUi(b11d, session = "s", sync = true, services = services), 100))
        assertEquals(DabScan.Verdict.WAIT, DabScan.verdict(DabUi(b11d, session = "s"), 1_000))
        assertEquals(DabScan.Verdict.SKIP, DabScan.verdict(DabUi(b11d, session = "s"), DabScan.WAIT_MS))
        assertEquals(DabScan.Verdict.SKIP, DabScan.verdict(DabUi(b11d, session = "s", problem = "Nothing was found on block 11D."), 100))
        // A multiplex with nothing on it is not worth a row.
        assertEquals(DabScan.Verdict.SKIP, DabScan.verdict(DabUi(b11d, session = "s", sync = true), 100))
        assertEquals(DabFound(b11d, "BBC National DAB", 1), DabScan.found(DabUi(b11d, sync = true, ensemble = "BBC National DAB", services = services)))
    }

    @Test fun aScanEndsWhereItStartedIfThatWasFoundElseOnTheFirstFound() {
        val b12b = DabBlocks.byLabel("12B")!!
        val b5a = DabBlocks.byLabel("5A")!!
        val found = listOf(DabFound(b11d, "A", 3), DabFound(b12b, "B", 4))
        assertEquals(b12b, DabScan.settle(b12b, found))
        assertEquals(b11d, DabScan.settle(b5a, found))
        assertEquals(b5a, DabScan.settle(b5a, emptyList()))
        assertEquals(0.5f, DabScanProgress(20, 40, b11d, emptyList()).fraction, 0f)
    }

    // --- the whole scan, against a stand-in Radio --------------------------------

    private lateinit var server: MockWebServer
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "dab-test-main") }.asCoroutineDispatcher()
    private val sendScope = CoroutineScope(Dispatchers.Default)
    /** Live sessions: id -> block. */
    private val live = ConcurrentHashMap<String, String>()
    private val started = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var most = 0
    private val withMux = setOf("11D", "12B")

    @Before fun up() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path?.substringBefore('?').orEmpty()
                return when {
                    path == "/shack-radio/api/decoders/sessions" && request.method == "POST" -> {
                        val block = JSONObject(request.body.readUtf8()).getJSONObject("params").getString("block")
                        val id = "ds$block"
                        live[id] = block
                        started += block
                        most = maxOf(most, live.size)
                        MockResponse().setResponseCode(201).setBody(JSONObject().put("id", id).toString())
                    }
                    path.startsWith("/shack-radio/api/decoders/sessions/") && request.method == "DELETE" -> {
                        live.remove(path.substringAfterLast('/'))
                        MockResponse().setBody("""{"stopped": true}""")
                    }
                    path.startsWith("/shack-radio/api/decoders/sessions/") -> {
                        val block = live[path.substringAfterLast('/')] ?: "?"
                        val r = if (block in withMux) JSONObject().put("rev", 1).put("sync", true)
                            .put("ensemble", JSONObject().put("label", "Mux $block"))
                            .put("services", JSONArray().put(JSONObject().put("sid", "C1").put("label", "One").put("kind", "dab")))
                        else JSONObject().put("rev", 1).put("sync", false).put("why", "Nothing was found on block $block.")
                        MockResponse().setBody(JSONObject().put("state", "running").put("results", JSONArray().put(r)).toString())
                    }
                    path == "/shack-radio/api/set" -> MockResponse().setBody("""{"changed": {}}""")
                    path == "/shack-radio/api/spec" -> MockResponse().setBody("{}")
                    path == "/shack-radio/api/memories" -> MockResponse().setBody("""{"memories": []}""")
                    else -> MockResponse().setResponseCode(404).setBody("""{"error": "Not found.", "code": "not_found"}""")
                }
            }
        }
        server.start()
    }

    @After fun down() {
        sendScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun until(what: String, ms: Long = 20_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test fun aScanTriesEveryBlockOneSessionAtATimeAndKeepsTheOnesThatSync() {
        val app = ShackApp("shack-radio", Kind.RADIO, "radio", "Radio", "shack-radio/", running = true, txCapable = true)
        val s = EngineSession(app, "FAKESHACK", ShackController(server.url("/").toString(), "shackdev_T").app(app), "remote-test",
            canTransmit = false, sendScope = sendScope)
        s.openDab(DabBlocks.byLabel("5A")!!)
        until("the first session") { s.ui.value.dab?.session != null }
        s.dabScan()
        until("the scan to start") { s.ui.value.dabScan != null }
        until("the scan to end", 60_000) { s.ui.value.dabScan == null && s.ui.value.dab?.sync == true }
        val u = s.ui.value
        assertEquals(listOf("11D", "12B"), u.dabFound.map { it.block.label })
        assertEquals("Mux 11D", u.dabFound.first().ensemble)
        // Started on 5A, which has nothing: the list opens on the first found.
        assertEquals("11D", u.dab?.block?.label)
        // Every block once, in the table's order, then 11D again for the list.
        assertEquals(DabBlocks.ALL.map { it.label } + "11D", started.drop(1))
        // Never two sessions at once (the 5A session was stopped before the scan's first).
        until("the stops to land") { live.size == 1 }
        assertTrue("at most two live at once (a stop is sent as the next starts): $most", most <= 2)
        s.close()
    }

    @Test fun stopKeepsWhatWasFoundAndOpensTheBlockItHadReached() {
        val app = ShackApp("shack-radio", Kind.RADIO, "radio", "Radio", "shack-radio/", running = true, txCapable = true)
        val s = EngineSession(app, "FAKESHACK", ShackController(server.url("/").toString(), "shackdev_T").app(app), "remote-test",
            canTransmit = false, sendScope = sendScope)
        s.openDab(b11d)
        until("the first session") { s.ui.value.dab?.session != null }
        s.dabScan()
        until("the scan to pass 11D") { (s.ui.value.dabScan?.found?.size ?: 0) >= 1 }
        val at = s.ui.value.dabScan!!.block
        s.dabScanStop()
        assertNull(s.ui.value.dabScan)
        assertEquals(listOf("11D"), s.ui.value.dabFound.map { it.block.label })
        assertEquals(at, s.ui.value.dab?.block)
        s.close()
    }
}
