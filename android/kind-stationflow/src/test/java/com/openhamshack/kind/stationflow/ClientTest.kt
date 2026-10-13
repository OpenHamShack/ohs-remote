package com.openhamshack.kind.stationflow

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.openhamshack.core.net.BoxAddress
import com.openhamshack.kind.stationflow.api.NotSignedIn
import com.openhamshack.kind.stationflow.api.Refused
import com.openhamshack.kind.stationflow.api.StationFlowClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The client against a server that records everything: what it sends for
 * each control, that it carries the host's session, how refusals come back -
 * and that no transmit path can be sent at all.
 */
class ClientTest {
    private val server = MockWebServer()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()
    private var answer: (RecordedRequest) -> MockResponse = { MockResponse().setBody("""{"ok": true}""") }
    private lateinit var client: StationFlowClient

    @Before fun up() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { seen += request; return answer(request) }
        }
        server.start()
        val base = BoxAddress.parse("http://${server.hostName}:${server.port}/app/icom/")
        // The host's client, as the OpenHamShack app will hand it over: its session on every request.
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { c ->
            c.proceed(c.request().newBuilder().header("Cookie", "icom9700=v1.c.9.ab").build())
        }).build()
        client = StationFlowClient(base, http)
    }

    @After fun down() = server.shutdown()

    @Test fun `controls go under the base path, with the host's session`() = runBlocking {
        client.setFrequency(145_500_000)
        client.setMode("USB-D")
        client.setFilter("FIL2")
        client.setVfo("VFOB")
        client.setSplit(true)
        client.setPreamp(1)
        client.setPreampMode(3)
        client.setAtt(10)
        val got = seen.map { it.path to JSONObject(it.body.readUtf8()).toString() }
        assertEquals(listOf(
            "/app/icom/api/freq" to """{"hz":145500000}""",
            "/app/icom/api/mode" to """{"mode":"USB-D"}""",
            "/app/icom/api/filter" to """{"filter":"FIL2"}""",
            "/app/icom/api/vfo" to """{"vfo":"VFOB"}""",
            "/app/icom/api/split" to """{"on":true}""",
            "/app/icom/api/preamp" to """{"level":1}""",
            "/app/icom/api/preamp" to """{"mode":3}""",
            "/app/icom/api/att" to """{"db":10}""",
        ), got)
        assertTrue(seen.all { it.getHeader("Cookie") == "icom9700=v1.c.9.ab" })
    }

    @Test fun `no transmit path can be sent, and nothing reaches the server trying`() {
        val tx = listOf("api/ptt", "api/digi/arm", "api/digi/tx", "api/digi/reply", "api/digi/cq", "api/digi/tune",
            "api/digi/config", "api/digi/cancel", "api/digi/abort", "api/audio/tx", "api/settings", "api/radios",
            "/api/freq", "api/freq/../ptt")
        for (p in tx) {
            assertThrows(p, IllegalArgumentException::class.java) { runBlocking { client.post(p, JSONObject()) } }
        }
        assertEquals(0, seen.size)
    }

    @Test fun `every write the client offers is a receive control`() = runBlocking {
        client.setFrequency(144_174_000); client.setMode("FM"); client.setFilter("FIL1"); client.setVfo("VFOA")
        client.setSplit(false); client.startDecoder("FT8"); client.startSstv(); client.startAprs()
        val writes = seen.filter { it.method == "POST" }.map { it.path!!.removePrefix("/app/icom/") }
        assertEquals(8, writes.size)
        assertTrue(writes.toString(), writes.all { it in StationFlowClient.CONTROLS })
        assertTrue(writes.none { it.contains("ptt") || it.contains("tx") || it.contains("arm") })
        // Never "force": a TV does not take the decoder from somebody else.
        val dm = seen.first { it.path!!.endsWith("api/digi/mode") }
        assertEquals("""{"mode":"FT8"}""", dm.body.readUtf8())
    }

    @Test fun `scope settings and NR2 post the contract's bodies, and are receive controls`() = runBlocking {
        client.setScope(JSONObject().put("span_hz", 50000))
        client.setScope(JSONObject().put("ref_db", -2.5))
        client.setNr2(on = true)
        client.setNr2(level = 70)
        val got = seen.map { it.path to JSONObject(it.body.readUtf8()).toString() }
        assertEquals(listOf(
            "/app/icom/api/scope" to """{"span_hz":50000}""",
            "/app/icom/api/scope" to """{"ref_db":-2.5}""",
            "/app/icom/api/nr2" to """{"on":true}""",
            "/app/icom/api/nr2" to """{"level":70}""",
        ), got)
        assertTrue(listOf("api/scope", "api/nr2").all { it in StationFlowClient.CONTROLS })
    }

    @Test fun `refusals come back in the module's words`() {
        answer = { MockResponse().setResponseCode(401).setBody("""{"error": "not signed in"}""") }
        assertThrows(NotSignedIn::class.java) { runBlocking { client.setFrequency(145_500_000) } }
        answer = { MockResponse().setResponseCode(400).setBody("""{"error": "IC-9700 tunes 144 to 1300 MHz"}""") }
        val e = assertThrows(Refused::class.java) { runBlocking { client.setFrequency(5) } }
        assertEquals(400, e.status)
        assertEquals("IC-9700 tunes 144 to 1300 MHz", e.message)
        answer = { MockResponse().setResponseCode(503).setBody("""{"error": "not connected to the radio"}""") }
        assertEquals(503, assertThrows(Refused::class.java) { runBlocking { client.setMode("FM") } }.status)
    }

    @Test fun `1_23_0's redirect to the login page is a lost session`() {
        answer = { MockResponse().setResponseCode(302).setHeader("Location", "/icom/login") }
        assertThrows(NotSignedIn::class.java) { runBlocking { client.state() } }
    }

    @Test fun `the state parses from the contract's example`() = runBlocking {
        val body = Fixtures2.text("tvapi-state-example.json")
        answer = { MockResponse().setBody(body) }
        assertEquals(144_174_000L, client.state().freqHz)
        assertEquals("/app/icom/api/state", seen.single().path)
    }
}

private object Fixtures2 {
    fun text(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
}
