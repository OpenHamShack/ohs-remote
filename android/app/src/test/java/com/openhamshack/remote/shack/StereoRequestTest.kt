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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

/**
 * What a session asks `api/audio/rx` for, against stand-in servers of each
 * age: Radio from 78ec61f (stereo on request), the same server refusing it,
 * a Radio from before (no `stereo` in its status), and NB. The audio itself
 * never plays here (no AudioTrack on the JVM), so each answer ends the
 * stream and the session asks again after RETRY_MS - which is what shows
 * whether a refusal is remembered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StereoRequestTest {
    private lateinit var server: MockWebServer
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "stereo-test-main") }.asCoroutineDispatcher()
    private val sendScope = CoroutineScope(Dispatchers.Default)
    /** api/audio/rx request targets, in order, path and query. */
    private val asked = Collections.synchronizedList(mutableListOf<String>())

    @Volatile private var statusAudio: String? = NEW_AUDIO
    /** The server answers ?channels=2 with 400 bad_channels, as one would that advertised what it cannot do. */
    @Volatile private var refuseStereo = false

    @Before fun up() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val app = path.removePrefix("/").substringBefore('/')
                val kind = if (app == "shack-qo100nb") "qo100nb" else "radio"
                return when (path.substringAfter("/$app").substringBefore('?')) {
                    "/api/status" -> MockResponse().setBody(
                        """{"kind": "$kind", "running": true${statusAudio?.let { ", \"audio\": $it" } ?: ""}}""")
                    "/api/audio/rx" -> audio(path.substringAfter("/$app/"))
                    else -> MockResponse().setResponseCode(404).setBody("""{"error": "Not found.", "code": "not_found"}""")
                }
            }
        }
        server.start()
    }

    private fun audio(target: String): MockResponse {
        asked += target
        val ch = target.substringAfter("channels=", "1").toIntOrNull() ?: 0
        if (ch !in 1..2 || (ch == 2 && refuseStereo)) return MockResponse().setResponseCode(400)
            .setBody("""{"error": "channels must be 1 or 2", "code": "bad_channels"}""")
        return MockResponse()
            .setHeader("Content-Type", "audio/L16; rate=48000; channels=$ch")
            .setHeader("X-Audio-Format", """{"encoding": "s16le", "rate": 48000, "channels": $ch}""")
            .setBody(okio.Buffer().write(ByteArray(48000 / 50 * 2 * ch)))
    }

    @After fun down() {
        sendScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun open(id: String, kind: Kind): EngineSession {
        val app = ShackApp(id, kind, kind.wire, id, "$id/", running = true, txCapable = true)
        val client = ShackController(server.url("/").toString(), "shackdev_T").app(app)
        return EngineSession(app, "FAKESHACK", client, "remote-test", canTransmit = false, sendScope = sendScope)
            .also { it.foreground(true) }
    }

    private fun until(what: String, ms: Long = 6000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what: $asked")
            Thread.sleep(10)
        }
    }

    @Test fun aNewRadioIsAskedForStereoEveryTime() {
        val s = open("shack-radio", Kind.RADIO)
        until("a second connection") { asked.size >= 2 }
        s.close()
        assertEquals(listOf("api/audio/rx?channels=2", "api/audio/rx?channels=2"), asked.take(2))
    }

    @Test fun aRefusalFallsBackToMonoAtOnceAndIsRemembered() {
        refuseStereo = true
        val s = open("shack-radio", Kind.RADIO)
        until("the retry after the first stream") { asked.size >= 3 }
        s.close()
        // The mono request follows the refusal straight away, and the next connection does not ask again.
        assertEquals(listOf("api/audio/rx?channels=2", "api/audio/rx", "api/audio/rx"), asked.take(3))
    }

    @Test fun anOlderRadioGetsTodaysRequest() {
        statusAudio = """{"encoding": "s16le", "rate": 12000, "channels": 1, "listeners": 0}"""
        val s = open("shack-radio", Kind.RADIO)
        until("the audio") { asked.isNotEmpty() }
        s.close()
        assertEquals("api/audio/rx", asked.first())
    }

    @Test fun nbIsNeverAskedForStereo() {
        val s = open("shack-qo100nb", Kind.QO100NB)
        until("the audio") { asked.isNotEmpty() }
        s.close()
        assertTrue(asked.none { "channels" in it })
    }

    @Test fun theStereoFlagIsTheStatusOwn() {
        val s = open("shack-radio", Kind.RADIO)
        until("the status") { s.ui.value.status != null }
        assertTrue(s.ui.value.stereo)
        s.close()
        statusAudio = NEW_AUDIO.replace("\"stereo\": true", "\"stereo\": false")
        val t = open("shack-radio", Kind.RADIO)
        until("the status") { t.ui.value.status != null }
        assertFalse(t.ui.value.stereo)
        t.close()
    }

    companion object {
        const val NEW_AUDIO = """{"encoding": "s16le", "rate": 48000, "channels": 1, "stereo": true, "listeners": 1,
            "tx": {"encoding": "s16le", "rate": 12000, "channels": 1}}"""
    }
}
