package com.classeve.earslate.testing

import com.classeve.earslate.live.LiveSocketClient
import com.classeve.earslate.live.LiveSocketState
import com.classeve.earslate.live.SocketClosure
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A provider socket a test drives by hand: it records what the app sends and
 * lets the test play the server's side.
 */
class FakeSocket(
    /** Runs when the app connects; the default is a server that accepts. */
    private val onConnect: FakeSocket.() -> Unit = { accept() },
    /** Runs for every frame the app sends, after it is recorded. */
    private val onSend: FakeSocket.(String) -> Unit = {},
) : LiveSocketClient {

    private val _frames = Channel<String>(Channel.UNLIMITED)
    override val frames: ReceiveChannel<String> = _frames

    private val _state = MutableStateFlow(LiveSocketState.IDLE)
    override val state: StateFlow<LiveSocketState> = _state

    @Volatile override var closure: SocketClosure? = null
        private set

    val sent = CopyOnWriteArrayList<String>()
    @Volatile var url: String? = null
        private set
    @Volatile var headers: Map<String, String> = emptyMap()
        private set
    @Volatile var closedByApp = false
        private set

    override fun connect(url: String, headers: Map<String, String>) {
        this.url = url
        this.headers = headers
        _state.value = LiveSocketState.CONNECTING
        onConnect()
    }

    override fun sendText(json: String): Boolean {
        if (_state.value != LiveSocketState.OPEN) return false
        sent += json
        onSend(json)
        return true
    }

    override fun close(code: Int, reason: String) {
        if (_state.value == LiveSocketState.CLOSED || _state.value == LiveSocketState.FAILED) return
        closedByApp = true
        end(LiveSocketState.CLOSED, SocketClosure(code, reason, null, byClient = true))
    }

    // ── the server's side ───────────────────────────────────────────────

    fun accept() {
        _state.value = LiveSocketState.OPEN
    }

    fun serve(frame: String) {
        _frames.trySend(frame)
    }

    /** The server closes the socket with a WebSocket close [code] and [reason]. */
    fun serverCloses(code: Int, reason: String) =
        end(LiveSocketState.CLOSED, SocketClosure(code, reason, null, byClient = false))

    /** The upgrade is refused with an HTTP status, or the network simply fails. */
    fun fails(httpStatus: Int? = null, body: String = "") =
        end(LiveSocketState.FAILED, SocketClosure(null, body, httpStatus, byClient = false))

    private fun end(state: LiveSocketState, why: SocketClosure) {
        if (closure == null) closure = why
        _state.value = state
        _frames.close()
    }
}
