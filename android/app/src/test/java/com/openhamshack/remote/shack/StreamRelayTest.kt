package com.openhamshack.remote.shack

import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The loopback relay a player opens instead of the controller: driven over
 * real sockets against a pretend controller that records what it was asked
 * and answers with a status and a body.
 */
class StreamRelayTest {
    /** What the pretend controller saw: the request line and the Authorization header. */
    private data class Seen(val line: String, val auth: String?)

    private val seen = LinkedBlockingQueue<Seen>()
    private var answer = 200
    private val body = ByteArray(188 * 50) { (it % 251).toByte() }
    private val upstream = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val relays = mutableListOf<StreamRelay>()

    init {
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { upstream.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val r = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val line = r.readLine()
                        var auth: String? = null
                        while (true) {
                            val h = r.readLine() ?: break
                            if (h.isEmpty()) break
                            if (h.startsWith("Authorization:", ignoreCase = true)) auth = h.substringAfter(':').trim()
                        }
                        seen.put(Seen(line, auth))
                        val out = it.getOutputStream()
                        if (answer != 200) {
                            out.write("HTTP/1.1 $answer No\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else {
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    private fun relay(token: String?) = StreamRelay(OkHttpClient(), "http://127.0.0.1:${upstream.localPort}/shack-datv") { token }
        .also { relays += it }

    private fun get(url: String): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 3000
        val code = c.responseCode
        val bytes = if (code == 200) c.inputStream.use { it.readBytes() } else ByteArray(0)
        c.disconnect()
        return code to bytes
    }

    @After fun tearDown() {
        relays.forEach { it.close() }
        upstream.close()
    }

    @Test fun theStreamIsOpenedWithTheTokenInAHeaderAndCopiedUntouched() {
        val r = relay("shackdev_abc")
        val (code, bytes) = get(r.url("/api/rx/stream.ts?rx=2"))
        assertEquals(200, code)
        assertArrayEquals(body, bytes)
        val s = seen.poll(3, TimeUnit.SECONDS)!!
        // The same path below the app's base, query and all; the token as a bearer header.
        assertEquals("GET /shack-datv/api/rx/stream.ts?rx=2 HTTP/1.1", s.line)
        assertEquals("Bearer shackdev_abc", s.auth)
        // ... and never in the URL the player was given.
        assertEquals(false, r.url("/api/rx/stream.ts").contains("shackdev_"))
    }

    @Test fun withoutATokenNoneIsSent() {
        val r = relay(null)
        assertEquals(200, get(r.url("/api/rx/stream.ts")).first)
        assertNull(seen.poll(3, TimeUnit.SECONDS)!!.auth)
    }

    @Test fun theControllersRefusalReachesThePlayerAsItsStatus() {
        answer = 401
        val r = relay("shackdev_revoked")
        assertEquals(401, get(r.url("/api/rx/stream.ts")).first)
    }

    @Test fun onlyTheRelaysOwnSecretPathIsServed() {
        val r = relay("shackdev_abc")
        val port = URL(r.url("/api/x")).port
        // Another app on the device guessing at the port gets nothing, and
        // the controller is never asked.
        assertEquals(404, get("http://127.0.0.1:$port/api/rx/stream.ts").first)
        assertEquals(404, get(r.url("/api/x").replace("/api/x", "") + "/../api/rx/stream.ts").first)
        assertNull(seen.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun upstreamPathsStayUnderTheAppsApi() {
        assertEquals("/api/rx/stream.ts", StreamRelay.upstreamPath("/s3cret/api/rx/stream.ts", "s3cret"))
        assertEquals("/api/rx/stream.ts?rx=2", StreamRelay.upstreamPath("/s3cret/api/rx/stream.ts?rx=2", "s3cret"))
        assertNull(StreamRelay.upstreamPath("/api/rx/stream.ts", "s3cret"))
        assertNull(StreamRelay.upstreamPath("/s3cret/../shack-icom/api/ptt", "s3cret"))
        assertNull(StreamRelay.upstreamPath("/s3cret/api/../../x", "s3cret"))
        assertNull(StreamRelay.upstreamPath("/s3cretx/api/rx/stream.ts", "s3cret"))
        assertNull(StreamRelay.upstreamPath("/s3cret", "s3cret"))
        assertNull(StreamRelay.upstreamPath(null, "s3cret"))
    }

    @Test fun theRequestLineIsReadNoFurtherThanTheHead() {
        val head = "GET /a/b?c=1 HTTP/1.1\r\nHost: x\r\n\r\nBODY"
        assertEquals("/a/b?c=1", StreamRelay.readRequestTarget(ByteArrayInputStream(head.toByteArray())))
        assertNull(StreamRelay.readRequestTarget(ByteArrayInputStream("POST /a HTTP/1.1\r\n\r\n".toByteArray())))
    }
}
