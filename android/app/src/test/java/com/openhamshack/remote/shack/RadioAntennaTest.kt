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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Radio's SDR antenna input: the labels read as the server's
 * ohscore/antennas.py reads them, the words a screen shows, and a whole
 * Radio session against a stand-in for the store server - what api/set
 * is asked for, that the screen follows the state the server then sends,
 * and that a view-only person's change is refused the way the mode's is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioAntennaTest {

    // --- labels and words -------------------------------------------------

    private val labels = """{"Antenna A":"Diamond X7000","Antenna B":"Unused","Antenna C":"Loop Antenna"}"""

    @Test fun labelsAreReadFromTheSettingsOneLineOfJson() {
        val l = Antennas.parseLabels(labels)
        assertEquals(mapOf("Antenna A" to "Diamond X7000", "Antenna B" to "Unused", "Antenna C" to "Loop Antenna"), l)
        assertEquals("Antenna C — Loop Antenna", Antennas.display("Antenna C", l, "Driver default"))
    }

    @Test fun anInputWithNoLabelIsShownByItsOwnName() {
        assertEquals("Antenna B", Antennas.display("Antenna B", emptyMap(), "Driver default"))
        assertEquals("Antenna B", Antennas.display("Antenna B", Antennas.parseLabels("""{"Antenna A":"Wire"}"""), "Driver default"))
        // A label cleared to "" is no label (format_labels drops it; a hand edit may not have).
        assertEquals("Antenna B", Antennas.display("Antenna B", Antennas.parseLabels("""{"Antenna B":""}"""), "Driver default"))
    }

    @Test fun theEmptySettingIsTheDriversDefault() {
        assertEquals("Driver default", Antennas.display("", Antennas.parseLabels(labels), "Driver default"))
    }

    @Test fun aDamagedSettingIsNoLabelsRatherThanAFailure() {
        for (bad in listOf("", "   ", "{not json", "[\"Antenna A\"]", "\"Antenna A\"", "42", "null")) {
            assertEquals("for $bad", emptyMap<String, String>(), Antennas.parseLabels(bad))
        }
        assertEquals(emptyMap<String, String>(), Antennas.parseLabels(null))
        // Inside an object, only string labels count, as parse_labels keeps them.
        assertEquals(mapOf("Antenna C" to "Loop"), Antennas.parseLabels("""{"Antenna A": 3, "Antenna B": {"x": 1}, "Antenna C": "Loop"}"""))
    }

    @Test fun theStateSaysWhatThereIsToPick() {
        val s = EngineState(state("Antenna C", "Antenna C"))
        val c = AntennaChoice.of(s)!!
        assertEquals(listOf("", "Antenna A", "Antenna B", "Antenna C"), c.rows)
        assertEquals("Antenna C", c.current)
        assertEquals("Antenna C", c.chosen)
        assertEquals("Loop Antenna", c.labels["Antenna C"])
        // No SDR, or a server older than the keys: no picker at all.
        assertNull(AntennaChoice.of(EngineState(JSONObject("""{"vfo": 14074000, "deviceStatus": {}}"""))))
        assertNull(AntennaChoice.of(EngineState()))
        // Antennas but no setting (an in-between server): the input in use is the one lit.
        val old = JSONObject("""{"deviceStatus": {"antenna": "Antenna B", "antennas": ["Antenna A", "Antenna B"]}}""")
        assertEquals("Antenna B", AntennaChoice.of(EngineState(old))!!.chosen)
    }

    @Test fun aStateChangeReplacesTheDevicesReportWhole() {
        val s = EngineState(state("Antenna C", "Antenna C"))
            .merge(JSONObject().put("deviceStatus", JSONObject().put("antenna", "Antenna A").put("antennas", JSONArray(INPUTS))).put("soapyAntenna", ""))
        val c = AntennaChoice.of(s)!!
        assertEquals("Antenna A", c.current)
        assertEquals("", c.chosen)
    }

    // --- a session against a stand-in server --------------------------------

    private lateinit var server: MockWebServer
    private val crashes = Collections.synchronizedList(mutableListOf<Throwable>())
    private val main = Executors.newSingleThreadExecutor { r ->
        Thread(r, "radio-test-main").apply { setUncaughtExceptionHandler { _, e -> crashes += e } }
    }.asCoroutineDispatcher()
    private val sendScope = CoroutineScope(Dispatchers.Default)

    /** api/set bodies, in order. */
    private val sent = Collections.synchronizedList(mutableListOf<JSONObject>())
    /** The Radio's state as api/state answers it; an accepted set changes it, as the server's does. */
    @Volatile private var current = state("Antenna C", "Antenna C")
    /** The level the stand-in gives this person: below control, every api/set is the gate's 403. */
    @Volatile private var viewOnly = false
    @Volatile private var socket: WebSocket? = null

    @Before fun up() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/shack-radio/api/events" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        socket = webSocket
                        webSocket.send("""{"kind": "hello", "status": {"kind": "radio", "running": true}}""")
                    }
                })
                "/shack-radio/api/state" -> MockResponse().setBody(current.toString())
                "/shack-radio/api/status" -> MockResponse().setBody("""{"kind": "radio", "running": true}""")
                "/shack-radio/api/set" -> set(JSONObject(request.body.readUtf8()))
                else -> MockResponse().setResponseCode(404).setBody("""{"error": "Not found.", "code": "not_found"}""")
            }
        }
        server.start()
    }

    /** The gate first (control level), then the server: the input switches and the state feed says so. */
    private fun set(body: JSONObject): MockResponse {
        sent += body
        if (viewOnly) return MockResponse().setResponseCode(403).setBody(
            """{"error": "This needs the Control level; your level for Radio is View.", "code": "level_too_low", "level": "view", "needs": "control"}""")
        val name = body.getJSONObject("changes").getString("soapyAntenna")
        // The driver's default on an RSPdx is its first input.
        val now = name.ifEmpty { "Antenna A" }
        val changes = JSONObject().put("soapyAntenna", name)
            .put("deviceStatus", JSONObject().put("antenna", now).put("antennas", JSONArray(INPUTS)))
        current = state(name, now)
        socket?.send(JSONObject().put("kind", "state").put("changes", changes).toString())
        return MockResponse().setBody(JSONObject().put("changed", JSONObject().put("soapyAntenna", name)).toString())
    }

    @After fun down() {
        sendScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun open(): EngineSession {
        val app = ShackApp("shack-radio", Kind.RADIO, "radio", "Radio", "shack-radio/", running = true, txCapable = true)
        val client = ShackController(server.url("/").toString(), "shackdev_T").app(app)
        val s = EngineSession(app, "FAKESHACK", client, "remote-test", canTransmit = false, sendScope = sendScope)
        s.foreground(true)
        until("the state") { AntennaChoice.of(s.ui.value.state) != null }
        return s
    }

    private fun until(what: String, ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test fun choosingAnInputSendsItAndTheScreenFollowsTheRadio() {
        val s = open()
        until("the socket") { s.ui.value.connected }
        s.setAntenna("Antenna A")
        until("the radio's report") { AntennaChoice.of(s.ui.value.state)?.current == "Antenna A" }
        assertEquals(1, sent.size)
        assertEquals("""{"changes":{"soapyAntenna":"Antenna A"}}""", sent[0].toString())
        assertEquals("Antenna A", AntennaChoice.of(s.ui.value.state)!!.chosen)
        assertFalse(s.ui.value.viewOnly)
        assertNull(s.ui.value.problem)
        assertTrue("crashed: $crashes", crashes.isEmpty())
        s.close()
    }

    @Test fun theDriversDefaultIsTheEmptySetting() {
        val s = open()
        until("the socket") { s.ui.value.connected }
        s.setAntenna("")
        until("the radio's report") { AntennaChoice.of(s.ui.value.state)?.chosen == "" }
        assertEquals("", sent.single().getJSONObject("changes").getString("soapyAntenna"))
        // The input the driver picked is what the radio now reports.
        assertEquals("Antenna A", AntennaChoice.of(s.ui.value.state)!!.current)
        s.close()
    }

    @Test fun theInputAlreadyChosenIsNotSentAgain() {
        val s = open()
        s.setAntenna("Antenna C")
        Thread.sleep(300)
        assertTrue(sent.isEmpty())
        s.close()
    }

    @Test fun aViewOnlyPersonIsRefusedAndTheScreenSaysWhy() {
        viewOnly = true
        val s = open()
        s.setAntenna("Antenna B")
        until("the refusal") { s.ui.value.viewOnly }
        assertEquals("This needs the Control level; your level for Radio is View.", s.ui.value.toast)
        // Refused, so nothing moved: the screen still shows the input the radio is on.
        assertEquals("Antenna C", AntennaChoice.of(s.ui.value.state)!!.current)
        // A refusal for the level is not a broken connection: no problem line.
        assertNull(s.ui.value.problem)
        assertTrue("crashed: $crashes", crashes.isEmpty())
        s.close()
    }

    companion object {
        private val INPUTS = listOf("Antenna A", "Antenna B", "Antenna C")

        /** Radio's state as the store server has it with an RSPdx open: the setting, the labels and the device's report. */
        private fun state(chosen: String, now: String) = JSONObject()
            .put("vfo", 7_074_000.0).put("mode", "usb").put("band", "40m")
            .put("soapyAntenna", chosen)
            .put("soapyAntennaLabels", """{"Antenna A":"Diamond X7000","Antenna B":"Unused","Antenna C":"Loop Antenna"}""")
            .put("deviceStatus", JSONObject().put("antenna", now).put("antennas", JSONArray(INPUTS)))
    }
}
