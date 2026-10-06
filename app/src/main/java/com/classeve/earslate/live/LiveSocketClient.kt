package com.classeve.earslate.live

import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * One provider WebSocket. Pure transport: it opens, sends, hands back frames,
 * and says why it ended. It knows nothing about any provider's protocol.
 */
interface LiveSocketClient {

    /** Every text frame from the server, in order, none dropped. Closed when the socket ends. */
    val frames: ReceiveChannel<String>

    val state: StateFlow<LiveSocketState>

    /** Why the socket ended; null until it has. */
    val closure: SocketClosure?

    fun connect(url: String, headers: Map<String, String> = emptyMap())

    /** Returns false if the socket is not open. */
    fun sendText(json: String): Boolean

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

/**
 * How a socket ended. [reason] is the provider's own text — its close reason,
 * or the error body of an upgrade it refused with [httpStatus].
 */
data class SocketClosure(
    val code: Int?,
    val reason: String,
    val httpStatus: Int?,
    val byClient: Boolean,
)

class OkHttpLiveSocketClient(
    private val httpClient: OkHttpClient,
) : LiveSocketClient {

    // A channel, not a shared flow: frames sent before the reader attaches
    // wait for it, and a slow reader delays frames instead of losing them.
    private val _frames = Channel<String>(Channel.UNLIMITED)
    override val frames: ReceiveChannel<String> = _frames

    private val _state = MutableStateFlow(LiveSocketState.IDLE)
    override val state: StateFlow<LiveSocketState> = _state.asStateFlow()

    @Volatile override var closure: SocketClosure? = null
        private set

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closedByClient = false

    override fun connect(url: String, headers: Map<String, String>) {
        _state.value = LiveSocketState.CONNECTING
        try {
            val builder = Request.Builder().url(url)
            for ((name, value) in headers) builder.addHeader(name, value)
            socket = httpClient.newWebSocket(builder.build(), Listener())
        } catch (t: Throwable) {
            end(LiveSocketState.FAILED, SocketClosure(null, "", null, byClient = false))
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

    override fun close(code: Int, reason: String) {
        val s = socket ?: return
        closedByClient = true
        if (_state.value == LiveSocketState.OPEN || _state.value == LiveSocketState.CONNECTING) {
            _state.value = LiveSocketState.CLOSING
        }
        s.close(code, reason)
    }

    private fun end(state: LiveSocketState, why: SocketClosure) {
        if (closure == null) closure = why
        socket = null
        _state.value = state
        _frames.close()
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _state.value = LiveSocketState.OPEN
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _frames.trySend(text)
        }

        // Gemini sends its JSON as binary frames.
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            _frames.trySend(bytes.utf8())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            if (closure == null) closure = SocketClosure(code, reason, null, closedByClient)
            _state.value = LiveSocketState.CLOSING
            // OkHttp reports onClosed only once this side has closed too.
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "closed code=$code byClient=$closedByClient")
            end(LiveSocketState.CLOSED, SocketClosure(code, reason, null, closedByClient))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // The exception's message can embed the request, credential included.
            Log.w(TAG, "failed http=${response?.code} kind=${t.javaClass.simpleName}")
            end(
                LiveSocketState.FAILED,
                SocketClosure(null, refusal(response), response?.code, closedByClient),
            )
        }
    }

    /** The provider's own words from a refused upgrade, when it gave any. */
    private fun refusal(response: Response?): String {
        val body = runCatching { response?.peekBody(MAX_REFUSAL_BYTES)?.string() }.getOrNull()
            ?.trim().orEmpty()
        if (body.isEmpty()) return ""
        return runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: body
    }

    companion object {
        private const val TAG = "LiveSocket"
        private const val MAX_REFUSAL_BYTES = 4_096L

        // A dead network leaves a socket open in name; the ping finds it
        // within two intervals. Long enough that a slow link is not taken for
        // a dead one: on poor Wi-Fi a pong has been seen to take five seconds.
        const val PING_INTERVAL_SECONDS = 10L

        /** The client every live socket and credential request shares. */
        fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            // A provider with nothing to say is silent for as long as the room is.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .socketFactory(ImmediateSockets)
            .build()
    }
}

// An audio frame ends in a part-filled packet, which the system holds back
// hoping to fill it. Speech in real time is worth more than the packet saved.
private object ImmediateSockets : SocketFactory() {
    private val system = getDefault()

    private fun Socket.immediate(): Socket = apply { tcpNoDelay = true }

    override fun createSocket(): Socket = system.createSocket().immediate()

    override fun createSocket(host: String, port: Int): Socket = system.createSocket(host, port).immediate()

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        system.createSocket(host, port, localHost, localPort).immediate()

    override fun createSocket(host: InetAddress, port: Int): Socket = system.createSocket(host, port).immediate()

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        system.createSocket(address, port, localAddress, localPort).immediate()
}
