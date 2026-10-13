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
import okio.Buffer
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * The Flex module behind the controller, for whole-session tests: every
 * answer is one the real module (flex 5b9a5d4, on its fakeflex radio
 * through tools/fakeflex_rx.py) gave and was captured into
 * resources/shack/flex/ - the hello with the newer DSP offered or not, the
 * decoder status, decodes, SSTV frames, the picture list. Records what the
 * session asked for, in order, and what it said; the session's main thread
 * records any uncaught exception, as Android's would crash on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlexStandIn(
    /** The websocket's first frame (a captured hello). */
    var hello: String = resource("flex-hello-dsp.json"),
) {
    val server = MockWebServer()
    val crashes: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val main = Executors.newSingleThreadExecutor { r ->
        Thread(r, "flex-test-main").apply { setUncaughtExceptionHandler { _, e -> crashes += e } }
    }.asCoroutineDispatcher()
    private val collector = CoroutineScope(Dispatchers.Default)

    /** Every write: (path, body), in order. */
    val sent: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(mutableListOf())
    /** Every GET path asked for, in order. */
    val got: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** The next answers to a write; the path's usual answer once these run out. */
    val replies = ConcurrentLinkedQueue<MockResponse>()
    val said: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Frames sent on the socket straight after the hello (a late joiner's SSTV replay, decodes). */
    val after = mutableListOf<String>()
    @Volatile var socket: WebSocket? = null

    private val words = object : FlexWords {
        override fun noSlice() = "no slice"
        override fun viewOnly() = "view only"
        override fun refused(http: Int) = "refused $http"
        override fun noAnswer(why: String) = "no answer"
        override fun floor(hz: Long) = "floor $hz"
        override fun ceiling(hz: Long) = "ceiling $hz"
    }

    fun start(): FlexStandIn {
        Dispatchers.setMain(main)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty().removePrefix("/shack-flex/")
                if (request.method == "POST") {
                    sent += path to JSONObject(request.body.readUtf8())
                    replies.poll()?.let { return it }
                }
                if (request.method == "GET") got += path
                return when {
                    path == "ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            socket = webSocket
                            webSocket.send(hello)
                            after.forEach { webSocket.send(it) }
                        }
                    })
                    path == "api/command" -> MockResponse().setBody("""{"ok": true, "sent": []}""")
                    path == "api/decoders" -> MockResponse().setBody(resource("flex-decoders.json"))
                    path == "api/sstv" -> MockResponse().setBody("""{"available": true, "why": null, "on": true}""")
                    path == "api/sstv/images" -> MockResponse().setBody(resource("flex-sstv-images.json"))
                    path.startsWith("api/sstv/images/") -> MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(PNG))
                    path.startsWith("api/decodes") -> MockResponse().setBody(resource("flex-decodes-history.json"))
                    path == "api/state" -> MockResponse().setBody(JSONObject(hello).apply { remove("type") }.toString())
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        return this
    }

    private val sessions = mutableListOf<FlexSession>()

    fun stop() {
        // Every session first, and a moment for its socket's last callbacks:
        // resetting Main under a live session fails the test that did nothing wrong.
        sessions.forEach { s -> main.executor.execute { s.close() } }
        Thread.sleep(150)
        collector.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private val http = OkHttpClient.Builder().retryOnConnectionFailure(false).build()

    fun client() = FlexClient(server.url("/shack-flex/").toString(), { "shackdev_T" }, http)

    fun open(): FlexSession {
        val app = ShackApp("shack-flex", Kind.FLEX, "flex", "Flex", server.url("/shack-flex/").toString(), running = true, txCapable = false)
        val s = FlexSession(words, app, "FAKESHACK", client(), onSignedOut = {})
        collector.launch { s.messages.collect { said += it } }
        sessions += s
        s.foreground(true)
        until("the hello") { s.ui.value.slice != null }
        return s
    }

    /** The writes to [path] once the session has had a moment to send them. */
    fun writes(path: String = "api/command"): List<JSONObject> {
        Thread.sleep(300)
        return sent.toList().filter { it.first == path }.map { it.second }
    }

    companion object {
        fun resource(name: String) = String(FlexStandIn::class.java.classLoader!!.getResourceAsStream("shack/flex/$name")!!.readBytes())

        fun lines(name: String) = resource(name).lines().filter { it.isNotBlank() }

        /** A 1 x 1 PNG: the picture route's bytes are passed on, not looked into. */
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
        )

        fun until(what: String, ms: Long = 5000, cond: () -> Boolean) {
            val end = System.currentTimeMillis() + ms
            while (!cond()) {
                if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
                Thread.sleep(10)
            }
        }
    }
}
