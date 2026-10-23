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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * A tune refused because background decoder sessions hold the capture
 * (Radio's 409 span_pinned): the server's sentence on the toast line, the
 * connection still up - not the screen's "lost the box".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpanPinnedTest {
    private lateinit var server: MockWebServer
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "span-test-main") }.asCoroutineDispatcher()
    private val sendScope = CoroutineScope(Dispatchers.Default)
    private val sentence = "PSK31 on 14.070 is decoding in the background: tuning outside 13.518-14.622 MHz stops it"

    @Before fun up() {
        Dispatchers.setMain(main)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/shack-radio/api/events" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"kind": "hello", "status": {"kind": "radio", "running": true}}""")
                    }
                })
                "/shack-radio/api/state" -> MockResponse().setBody("""{"vfo": 14070000, "mode": "usb"}""")
                "/shack-radio/api/status" -> MockResponse().setBody("""{"kind": "radio", "running": true}""")
                "/shack-radio/api/set" -> MockResponse().setResponseCode(409).setBody(
                    """{"error": "$sentence", "code": "span_pinned", "sessions": [{"id": "ds_1", "decoder": "psk31"}]}""")
                else -> MockResponse().setResponseCode(404).setBody("""{"error": "Not found.", "code": "not_found"}""")
            }
        }
        server.start()
    }

    @After fun down() {
        Thread.sleep(300)
        sendScope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun until(what: String, ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    @Test fun aTuneOutOfAPinnedSpanIsSaidNotALostLink() {
        val app = ShackApp("shack-radio", Kind.RADIO, "radio", "Radio", "shack-radio/", running = true, txCapable = true)
        val s = EngineSession(app, "FAKESHACK", ShackController(server.url("/").toString(), "shackdev_T").app(app), "remote-test",
            canTransmit = false, sendScope = sendScope)
        s.foreground(true)
        until("the socket and the dial") { s.ui.value.connected && s.ui.value.state.dialHz != null }
        s.setDial(7_074_000.0)
        until("the refusal") { s.ui.value.toast == sentence }
        assertNull(s.ui.value.problem)
        assertTrue(s.ui.value.connected)
        until("the dial shown to fall back") { s.ui.value.pendingDialHz == null }
        assertEquals(14_070_000.0, s.ui.value.dialHz!!, 0.0)
        s.close()
    }
}
