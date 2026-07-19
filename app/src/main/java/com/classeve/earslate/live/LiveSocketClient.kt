package com.classeve.earslate.live

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * Transport layer for the Gemini Live API WebSocket. **Pure transport** — knows
 * how to open a connection, send text/binary frames, and forward incoming frames
 * as raw strings. It knows nothing about the Gemini protocol, setup messages,
 * audio chunks, or caption parsing — those are the job of
 * [LiveSessionConfigFactory], [LiveMessageParser], and the SessionCoordinator.
 *
 * Advisor note: this split exists so that a bug in our JSON parsing cannot kill
 * the live socket. The parser throws → the coordinator logs the offender and
 * keeps listening.
 */
interface LiveSocketClient {

    /** Raw JSON text frames from the server. One frame per emission. */
    val frames: Flow<String>

    /** Current socket lifecycle state. */
    val state: StateFlow<LiveSocketState>

    /** Open the socket. Suspends until the listener is attached. [headers] are added to the
     *  WebSocket upgrade request (used to carry `Authorization: Token <ephemeral>` for Gemini). */
    suspend fun connect(url: String, headers: Map<String, String> = emptyMap())

    /** Send a text frame. Returns false if the socket is not open. */
    fun sendText(json: String): Boolean

    /** Send binary bytes (unused by current Gemini Live API, reserved for future). */
    fun sendBytes(data: ByteArray): Boolean

    /** Close the socket gracefully. OkHttp's close is synchronous, so this is non-suspending. */
    fun close(code: Int = 1000, reason: String = "client_close")
}

enum class LiveSocketState {
    IDLE,
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED,
    FAILED,
}

class OkHttpLiveSocketClient(
    private val httpClient: OkHttpClient = defaultClient(),
) : LiveSocketClient {

    private val _frames = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val frames = _frames.asSharedFlow()

    private val _state = MutableStateFlow(LiveSocketState.IDLE)
    override val state: StateFlow<LiveSocketState> = _state.asStateFlow()

    @Volatile private var socket: WebSocket? = null

    override suspend fun connect(url: String, headers: Map<String, String>) {
        _state.value = LiveSocketState.CONNECTING
        try {
            val builder = Request.Builder().url(url)
            for ((name, value) in headers) builder.addHeader(name, value)
            socket = httpClient.newWebSocket(builder.build(), Listener())
        } catch (t: Throwable) {
            // Request.Builder().url() throws IllegalArgumentException on a
            // malformed URL. The caller (SessionCoordinator) already wraps
            // connect() in try/catch, so this doesn't crash — but without
            // updating _state here, THIS socket's own reported state stayed
            // stuck at CONNECTING forever for anything observing it directly.
            _state.value = LiveSocketState.FAILED
            throw t
        }
    }

    override fun sendText(json: String): Boolean {
        val s = socket ?: return false
        return try {
            s.send(json)
        } catch (e: Exception) {
            false
        }
    }

    override fun sendBytes(data: ByteArray): Boolean {
        val s = socket ?: return false
        return try {
            s.send(ByteString.of(*data))
        } catch (e: Exception) {
            false
        }
    }

    override fun close(code: Int, reason: String) {
        val s = socket ?: return
        _state.value = LiveSocketState.CLOSING
        s.close(code, reason)
        socket = null
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = LiveSocketState.OPEN
            Log.i(TAG, "socket open, http=${response.code}")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _frames.tryEmit(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Gemini Live API v1beta sends JSON text frames, not binary. Some previews
            // may swap to binary protobuf; forward bytes as decoded UTF-8 in case.
            _frames.tryEmit(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = LiveSocketState.CLOSING
            Log.i(TAG, "socket closing code=$code reason=$reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _state.value = LiveSocketState.CLOSED
            socket = null
            Log.i(TAG, "socket closed code=$code reason=$reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _state.value = LiveSocketState.FAILED
            socket = null
            // Do NOT log t.message — it frequently embeds the full request URL
            // request metadata can still include credentials. Stick to a fixed
            // taxonomy of kind + HTTP code only.
            Log.w(TAG, "socket failure http=${response?.code} kind=${t.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "LiveSocket"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket is long-lived
            .build()
    }
}
