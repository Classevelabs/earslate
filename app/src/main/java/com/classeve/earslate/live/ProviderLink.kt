package com.classeve.earslate.live

import com.classeve.earslate.bootstrap.SessionCredential
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One provider session on one socket. [open] connects, sends the configuration
 * and waits for the provider to accept it; after that the owner reads
 * [LiveSocketClient.frames] itself.
 */
class ProviderLink(
    val credential: SessionCredential,
    val protocol: TranslationLiveProtocol,
    val socket: LiveSocketClient,
) {
    @Volatile var targetWireLanguage: String = ""
        private set

    /**
     * @return what the provider said before it acknowledged, the
     *   acknowledgement itself left out.
     * @throws LinkFailure carrying the diagnosis when the session does not open.
     */
    suspend fun open(targetWireLanguage: String): List<LiveEvent> {
        this.targetWireLanguage = targetWireLanguage
        val name = protocol.provider.displayName
        try {
            socket.connect(credential.webSocketUrl, protocol.headers(credential))
        } catch (t: Exception) {
            throw LinkFailure("Could not reach $name.", providerSpoke = false)
        }

        val reached = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            socket.state.first { it != LiveSocketState.IDLE && it != LiveSocketState.CONNECTING }
        }
        if (reached == null) {
            socket.close()
            throw LinkFailure("$name took too long to accept the connection.", providerSpoke = false)
        }
        // A provider can accept the connection, say what is wrong and hang up
        // before anything is sent, so its frames are read whatever happened.
        if (reached == LiveSocketState.OPEN) socket.sendText(protocol.setupFrame(credential, targetWireLanguage))

        val early = ArrayList<LiveEvent>()
        var notice: String? = null

        suspend fun acknowledged(): Boolean {
            while (true) {
                // An error in answer to the setup means no acknowledgement is
                // coming, so the wait for one is cut short.
                val next = if (notice == null) {
                    socket.frames.receiveCatching()
                } else {
                    withTimeoutOrNull(AFTER_NOTICE_MS) { socket.frames.receiveCatching() } ?: return false
                }
                val events = protocol.parse(next.getOrNull() ?: return false)
                for (event in events) {
                    if (event is LiveEvent.ProviderNotice) notice = event.message
                    if (event !is LiveEvent.SetupComplete) early += event
                }
                if (events.any { it is LiveEvent.SetupComplete }) return true
            }
        }

        return when (withTimeoutOrNull(SETUP_TIMEOUT_MS) { acknowledged() }) {
            true -> early
            false -> {
                val failure = refused(notice)
                socket.close()
                throw failure
            }
            null -> {
                socket.close()
                // A refusal the provider explained beats "did not become ready".
                throw notice?.let { said(name, it) }
                    ?: LinkFailure("$name did not become ready.", providerSpoke = false)
            }
        }
    }

    /** Points a running session at another language. False when the provider cannot. */
    fun retarget(targetWireLanguage: String): Boolean {
        val frame = protocol.retargetFrame(targetWireLanguage) ?: return false
        if (!socket.sendText(frame)) return false
        this.targetWireLanguage = targetWireLanguage
        return true
    }

    /**
     * Asks the provider to finish what it is saying and close.
     * @return false when this provider has no such request, and can just be closed.
     */
    fun sayGoodbye(): Boolean {
        val frame = protocol.gracefulCloseFrame() ?: return false
        return socket.sendText(frame)
    }

    fun close() {
        protocol.gracefulCloseFrame()?.let { socket.sendText(it) }
        socket.close()
    }

    private fun refused(notice: String?): LinkFailure =
        socket.closure.failure(protocol.provider.displayName, notice)

    companion object {
        private const val OPEN_TIMEOUT_MS = 10_000L

        // A busy provider can take several seconds to acknowledge. Giving up
        // early only starts the same wait again on a new socket.
        private const val SETUP_TIMEOUT_MS = 20_000L
        private const val AFTER_NOTICE_MS = 2_000L
    }
}

/**
 * Why a provider session could not be opened or kept. [providerSpoke] is true
 * when the reason came from the provider, so the fix is on the user's provider
 * account rather than their network.
 */
class LinkFailure(message: String, val providerSpoke: Boolean) : Exception(message)

private fun said(provider: String, words: String): LinkFailure? =
    ProviderMessage.sanitize(words)?.let { LinkFailure("$provider said: $it", providerSpoke = true) }

/** The plainest true sentence about how a socket ended. */
fun SocketClosure?.failure(provider: String, notice: String?): LinkFailure {
    notice?.let { said(provider, it) }?.let { return it }
    val reason = ProviderMessage.sanitize(this?.reason)
    return when (this?.httpStatus) {
        401, 403 -> LinkFailure(
            "$provider did not accept the session." + reason?.let { " $provider said: $it" }.orEmpty(),
            providerSpoke = true,
        )
        429 -> LinkFailure(
            "$provider refused the session: the key is out of quota or being rate limited.",
            providerSpoke = true,
        )
        null -> reason?.let { LinkFailure("$provider ended the session. $provider said: $it", providerSpoke = true) }
            ?: LinkFailure("The connection to $provider was lost.", providerSpoke = false)
        // A provider in trouble is worth another attempt; a refusal is not.
        in 500..599 -> LinkFailure("$provider is having trouble right now.", providerSpoke = false)
        else -> LinkFailure(
            "$provider refused the connection (HTTP $httpStatus)." + reason?.let { " $provider said: $it" }.orEmpty(),
            providerSpoke = true,
        )
    }
}
