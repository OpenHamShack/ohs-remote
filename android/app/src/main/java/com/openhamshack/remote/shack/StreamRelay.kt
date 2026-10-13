package com.openhamshack.remote.shack

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Hands a stream that needs the device token to a player that cannot send
 * one: libVLC opens [url] (loopback, this process), and the relay opens the
 * same path below the app's base on the controller with `Authorization:
 * Bearer` and copies the bytes across untouched.
 *
 * Why not the contract's other answers (CONTROLLER-CONTRACT 4.1, 4.6):
 * - The token never goes in a URL, and Android's libVLC has no option for a
 *   request header (only user agent and referrer).
 * - Stream tickets (`v1/tickets`, `?shack_ticket=`) are not built in the
 *   controller yet (Dock360 feature/ohs-edition, 09/10/2026), and would be
 *   one more request before every reopen.
 * - Opening with no credential works only while the app has anonymous
 *   viewing on (QO-100 WB by default; DATV and Icom never), and stops the
 *   moment an admin turns it off. Through the relay the stream is opened as
 *   the person, whatever that setting, so one path serves every video kind.
 *
 * The cost is one local copy of a few Mb/s. The listener is bound to the
 * loopback address only, and [url]'s path carries 128 random bits, so
 * another app on the device cannot use this device's credential by
 * connecting to the port. Each connection is one upstream request; the
 * player's own reconnect logic (RxPlayer) is unchanged: an upstream failure
 * closes the local connection, which the player sees as the stream ending.
 *
 * The answer to the player is HTTP/1.0 with no length, closed when the
 * upstream ends, which is how the Classic box itself serves the stream.
 */
class StreamRelay(
    http: OkHttpClient,
    /** The app's base URL, no trailing slash ("http://host/shack-datv"). */
    private val appBase: String,
    /** The device token now (it can be forgotten while the relay lives); null sends none. */
    private val token: () -> String?,
) : Closeable {

    private val client = http.newBuilder()
        // The box sends ten null packets a second while it receives nothing;
        // 20 s of silence is a dead connection, as RxPlayer's own watchdog has it.
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    // 127.0.0.1 by number, not getLoopbackAddress(): on Android that can be
    // ::1, and the player, told 127.0.0.1, was refused (the JVM test, where
    // it is 127.0.0.1, passed; the emulator did not).
    private val server = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val open = mutableSetOf<Socket>()
    @Volatile private var closed = false

    private val local = "http://127.0.0.1:${server.localPort}/$secret"

    /**
     * What the player opens for [path] below the app's base
     * ("/api/rx/stream.ts?rx=2"). Each path is its own URL, so a change of
     * receiver is a change of URL to the player, as it is on a Classic box.
     */
    fun url(path: String): String {
        require(path.startsWith("/api/")) { "only the app's API" }
        return local + path
    }

    init {
        Thread({ acceptLoop() }, "stream-relay-accept").apply { isDaemon = true }.start()
    }

    private fun acceptLoop() {
        while (!closed) {
            val s = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            synchronized(open) { open += s }
            Thread({ serve(s) }, "stream-relay").apply { isDaemon = true }.start()
        }
    }

    private fun serve(s: Socket) {
        try {
            s.soTimeout = 5000
            val upstream = upstreamPath(readRequestTarget(s.getInputStream()), secret)
            val out = s.getOutputStream()
            if (upstream == null) {
                out.write("HTTP/1.0 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                return
            }
            val req = Request.Builder().url(appBase + upstream).apply { token()?.let { header("Authorization", "Bearer $it") } }.build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    // The controller's refusal reaches the player as the same
                    // status, which it treats as an error opening (a reopen
                    // with backoff); the session's status poll says why.
                    out.write("HTTP/1.0 ${r.code} Upstream\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    return
                }
                out.write("HTTP/1.0 200 OK\r\nContent-Type: video/mp2t\r\nCache-Control: no-store\r\n\r\n".toByteArray())
                out.flush()
                copy(r.body!!.byteStream(), out)
            }
        } catch (_: IOException) {
            // Either end went away: the player stopped, or the controller did.
        } finally {
            synchronized(open) { open -= s }
            runCatching { s.close() }
        }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buf = ByteArray(32 * 1024)
        while (!closed) {
            val n = from.read(buf)
            if (n < 0) break
            to.write(buf, 0, n)
            to.flush()
        }
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
        synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
    }

    companion object {
        /**
         * What a local request may reach upstream: a path under the app's
         * `/api/`, only behind this relay's secret, never climbing out of
         * the app's base. Null for anything else (404).
         */
        internal fun upstreamPath(target: String?, secret: String): String? {
            val rest = target?.removePrefix("/$secret")?.takeIf { it != target } ?: return null
            if (!rest.startsWith("/api/") || rest.contains("..") || rest.contains("://")) return null
            return rest
        }

        /** The target of `GET <target> HTTP/1.x`, reading no further than the end of the head (8 KB at most). */
        internal fun readRequestTarget(input: InputStream): String? {
            val head = StringBuilder()
            while (head.length < 8192) {
                val c = input.read()
                if (c < 0) break
                head.append(c.toChar())
                if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
            }
            val first = head.lineSequence().firstOrNull()?.split(' ') ?: return null
            if (first.size < 2 || (first[0] != "GET" && first[0] != "HEAD")) return null
            return first[1]
        }
    }
}
