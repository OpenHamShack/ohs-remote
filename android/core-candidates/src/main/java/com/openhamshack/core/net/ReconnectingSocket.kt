package com.openhamshack.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Where a socket is. [Down] carries the reason, in words for the screen. */
sealed interface SocketState {
    data object Connecting : SocketState
    data object Up : SocketState
    data class Down(val why: String, val httpStatus: Int? = null) : SocketState
}

/**
 * A websocket that comes back. A TV is left on for hours, its Wi-Fi drops
 * and returns, the box restarts for an update: every one of those should end
 * with the screen live again and nobody touching the remote.
 *
 * Backoff 0.5 s doubling to 8 s, back to 0.5 s once a connection has stayed
 * up for 10 s - quick after a blip, gentle on a box that is down. A 401 on
 * the upgrade stops it ([onUnauthorised]): retrying a refused session is
 * a loop that cannot succeed.
 *
 * Callbacks arrive on OkHttp's reader thread; keep them short.
 */
class ReconnectingSocket(
    private val scope: CoroutineScope,
    private val open: (WebSocketListener) -> WebSocket,
    private val onText: (String) -> Unit = {},
    private val onBytes: (ByteString) -> Unit = {},
    private val onOpen: () -> Unit = {},
    private val onUnauthorised: () -> Unit = {},
) {
    private val _state = MutableStateFlow<SocketState>(SocketState.Connecting)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile private var socket: WebSocket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var backoff = MIN_BACKOFF_MS
            while (isActive) {
                _state.value = SocketState.Connecting
                val ended = CompletableDeferred<SocketState.Down>()
                var openedAt = 0L
                val ws = open(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        openedAt = System.currentTimeMillis()
                        _state.value = SocketState.Up
                        onOpen()
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) = onText(text)
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onBytes(bytes)
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null)
                        ended.complete(SocketState.Down(reason.ifBlank { "closed by the box" }))
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        ended.complete(SocketState.Down(reason.ifBlank { "closed" }))
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val code = response?.code
                        ended.complete(SocketState.Down(
                            if (code != null) "HTTP $code" else (t.message ?: t.javaClass.simpleName), code))
                    }
                })
                socket = ws
                val down = try {
                    ended.await()
                } finally {
                    if (!isActive) ws.cancel()
                }
                socket = null
                _state.value = down
                if (down.httpStatus == 401 || down.httpStatus == 403) {
                    onUnauthorised()
                    return@launch
                }
                if (openedAt > 0 && System.currentTimeMillis() - openedAt > STABLE_MS) backoff = MIN_BACKOFF_MS
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** Send on the open socket, if there is one. */
    fun send(text: String): Boolean = socket?.send(text) ?: false

    fun stop() {
        job?.cancel()
        job = null
        socket?.cancel()
        socket = null
        _state.value = SocketState.Down("stopped")
    }

    companion object {
        const val MIN_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 8_000L
        const val STABLE_MS = 10_000L
    }
}
