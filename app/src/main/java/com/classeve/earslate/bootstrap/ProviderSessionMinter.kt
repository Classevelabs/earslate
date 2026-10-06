package com.classeve.earslate.bootstrap

import com.classeve.earslate.live.ProviderMessage
import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

/**
 * Trades the user's long-lived API key for a short-lived credential, over one
 * HTTPS request from the phone straight to the provider. Sockets carry the
 * credential, never the key.
 */
class ProviderSessionMinter(
    http: OkHttpClient,
    /** Random per-install id, sent only as a hash in OpenAI's safety header. */
    private val installId: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val http = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** How far the provider's clock is ahead of this phone's, from its last reply. */
    @Volatile private var serverAheadMs = 0L

    private class Reply(val json: JSONObject?, val refusal: BootstrapException?)

    suspend fun mint(provider: KeyProvider, apiKey: String): SessionCredential {
        // A header cannot carry these, and the library's own complaint about
        // it would quote the key.
        if (apiKey.any { it !in VISIBLE_ASCII }) {
            throw BootstrapException("That key has a character in it that no key contains. Copy it again and paste only the key.")
        }
        return when (provider) {
            KeyProvider.GEMINI -> mintGemini(apiKey)
            KeyProvider.OPENAI -> mintOpenAi(apiKey)
        }
    }

    private suspend fun mintGemini(apiKey: String): SessionCredential {
        var ahead = serverAheadMs
        var reply = geminiToken(apiKey, ahead)
        // The request carries absolute times, and Google issues a token that
        // has already expired without complaint. Its reply is dated, so a
        // phone whose clock is wrong asks once more, on Google's clock.
        if (abs(serverAheadMs - ahead) > CLOCK_TOLERANCE_MS) {
            ahead = serverAheadMs
            reply = geminiToken(apiKey, ahead)
        }
        val json = reply.json ?: throw reply.refusal!!
        val name = json.optString("name").takeIf { it.isNotBlank() }
            ?: throw BootstrapException("Gemini returned a session without a credential. Try again.", transient = true)

        return SessionCredential(
            provider = TranslationProvider.GEMINI,
            secret = name,
            webSocketUrl = GEMINI_WSS,
            model = GEMINI_MODEL,
            expiresAtMs = now() + CREDENTIAL_LIFETIME_MS,
            serverAheadMs = ahead,
        )
    }

    private suspend fun geminiToken(apiKey: String, aheadMs: Long): Reply {
        val expires = iso8601(now() + aheadMs + CREDENTIAL_LIFETIME_MS)
        val body = JSONObject()
            // 0 is "no limit": one token opens both directions and every
            // replacement socket, so a session mints once.
            .put("uses", 0)
            .put("expireTime", expires)
            .put("newSessionExpireTime", expires)
            // The mask pins the token to the translate model and leaves the
            // language to each socket's own first frame.
            .put("bidiGenerateContentSetup", JSONObject().put("model", "models/$GEMINI_MODEL"))
            .put("fieldMask", "model")

        val request = Request.Builder()
            .url(GEMINI_TOKEN_URL)
            // In a header, not the query string: URLs end up in logs.
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON))
            .build()
        return execute(request, KeyProvider.GEMINI)
    }

    private suspend fun mintOpenAi(apiKey: String): SessionCredential {
        val safetyIdentifier = safetyIdentifier()
        val body = JSONObject()
            .put(
                "expires_after",
                JSONObject().put("anchor", "created_at").put("seconds", CREDENTIAL_LIFETIME_MS / 1000),
            )
            // No language here: each socket sets its own with session.update.
            .put("session", JSONObject().put("model", OPENAI_MODEL))

        val request = Request.Builder()
            .url(OPENAI_SECRET_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("OpenAI-Safety-Identifier", safetyIdentifier)
            .post(body.toString().toRequestBody(JSON))
            .build()

        val reply = execute(request, KeyProvider.OPENAI)
        val json = reply.json ?: throw reply.refusal!!
        val value = json.optString("value").takeIf { it.isNotBlank() }
            ?: throw BootstrapException("OpenAI returned a session without a credential. Try again.", transient = true)
        val expiresAtSeconds = json.optLong("expires_at", 0L)

        return SessionCredential(
            provider = TranslationProvider.OPENAI,
            secret = value,
            webSocketUrl = "$OPENAI_WSS?model=$OPENAI_MODEL",
            model = OPENAI_MODEL,
            // OpenAI's time, read on this phone's clock.
            expiresAtMs = if (expiresAtSeconds > 0) expiresAtSeconds * 1000 - serverAheadMs else now() + CREDENTIAL_LIFETIME_MS,
            safetyIdentifier = safetyIdentifier,
            serverAheadMs = serverAheadMs,
        )
    }

    private class Fetched(val code: Int, val date: Date?, val body: String)

    private class Dropped(cause: Throwable) : IOException(cause)

    /**
     * A refusal comes back as a value, not a throw: a phone with a wrong clock
     * has to read the reply's date before it knows whether to ask again.
     * Network failures throw.
     */
    private suspend fun execute(request: Request, provider: KeyProvider): Reply {
        val name = provider.displayName
        val call = http.newCall(request)
        // Cancellable, so Stop does not wait out a stalled network. The reply
        // is read on the library's own thread: the caller may be the main one.
        val fetched = suspendCancellableCoroutine<Result<Fetched>> { waiting ->
            waiting.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = waiting.resume(Result.failure(e))

                override fun onResponse(call: Call, response: Response) = waiting.resume(
                    try {
                        Result.success(response.use { Fetched(it.code, it.headers.getDate("Date"), it.body?.string().orEmpty()) })
                    } catch (io: IOException) {
                        Result.failure(Dropped(io))
                    },
                )
            })
        }.getOrElse { failure ->
            throw BootstrapException(
                if (failure is Dropped) {
                    "The connection to $name dropped before it finished replying. Try again."
                } else {
                    "Couldn't reach $name. Check your connection and try again."
                },
                failure,
                transient = true,
            )
        }
        fetched.date?.let { serverAheadMs = it.time - now() }
        if (fetched.code !in 200..299) return Reply(null, refusal(fetched.code, fetched.body, name))
        val json = runCatching { JSONObject(fetched.body) }.getOrNull()
            ?: throw BootstrapException("$name sent a reply that couldn't be read. Try again.", transient = true)
        return Reply(json, null)
    }

    // The provider's own sentence is the diagnosis, so it is shown — with
    // anything key-shaped removed — instead of a guess from the status code.
    private fun refusal(code: Int, raw: String, name: String): BootstrapException {
        val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
        val said = ProviderMessage.sanitize(error?.optString("message"))
        val ours = when {
            code == 401 || code == 403 || namesTheKey(error) -> "$name did not accept that key."
            code == 429 -> "$name refused the request: the key is out of quota or being rate limited."
            code in 500..599 -> "$name is having trouble right now. Try again in a moment."
            else -> "$name could not start a translation session."
        }
        return BootstrapException(if (said != null) "$ours $name said: $said" else ours, transient = code in 500..599)
    }

    // Google answers a wrong key with a plain 400; its reason code is what names it.
    private fun namesTheKey(error: JSONObject?): Boolean {
        val details = error?.optJSONArray("details") ?: return false
        return (0 until details.length()).any { details.optJSONObject(it)?.optString("reason") == "API_KEY_INVALID" }
    }

    private fun safetyIdentifier(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(installId.toByteArray(Charsets.UTF_8))
        return "earslate_" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun iso8601(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    companion object {
        // Pinned here rather than fetched, so the app has no configuration
        // server. Each is the provider's only speech-to-speech translate model.
        const val GEMINI_MODEL = "gemini-3.5-live-translate-preview"
        const val OPENAI_MODEL = "gpt-realtime-translate"

        const val CREDENTIAL_LIFETIME_MS = 30 * 60_000L

        /** A clock this close to the provider's costs nothing worth a second request. */
        const val CLOCK_TOLERANCE_MS = 2 * 60_000L

        private val VISIBLE_ASCII = '!'..'~'

        private const val GEMINI_TOKEN_URL =
            "https://generativelanguage.googleapis.com/v1beta/auth_tokens"
        const val GEMINI_WSS =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
        private const val OPENAI_SECRET_URL =
            "https://api.openai.com/v1/realtime/translations/client_secrets"
        const val OPENAI_WSS = "wss://api.openai.com/v1/realtime/translations"

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
