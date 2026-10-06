package com.classeve.earslate.bootstrap

import com.classeve.earslate.live.ProviderMessage
import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

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

    suspend fun mint(provider: KeyProvider, apiKey: String): SessionCredential =
        withContext(Dispatchers.IO) {
            when (provider) {
                KeyProvider.GEMINI -> mintGemini(apiKey)
                KeyProvider.OPENAI -> mintOpenAi(apiKey)
            }
        }

    private fun mintGemini(apiKey: String): SessionCredential {
        val expiresAtMs = now() + CREDENTIAL_LIFETIME_MS
        val expires = iso8601(expiresAtMs)
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

        val json = execute(request, KeyProvider.GEMINI)
        val name = json.optString("name").takeIf { it.isNotBlank() }
            ?: throw BootstrapException("Gemini returned a session without a credential. Try again.")

        return SessionCredential(
            provider = TranslationProvider.GEMINI,
            secret = name,
            webSocketUrl = GEMINI_WSS,
            model = GEMINI_MODEL,
            expiresAtMs = expiresAtMs,
        )
    }

    private fun mintOpenAi(apiKey: String): SessionCredential {
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

        val json = execute(request, KeyProvider.OPENAI)
        val value = json.optString("value").takeIf { it.isNotBlank() }
            ?: throw BootstrapException("OpenAI returned a session without a credential. Try again.")
        val expiresAtSeconds = json.optLong("expires_at", 0L)

        return SessionCredential(
            provider = TranslationProvider.OPENAI,
            secret = value,
            webSocketUrl = "$OPENAI_WSS?model=$OPENAI_MODEL",
            model = OPENAI_MODEL,
            expiresAtMs = if (expiresAtSeconds > 0) expiresAtSeconds * 1000 else now() + CREDENTIAL_LIFETIME_MS,
            safetyIdentifier = safetyIdentifier,
        )
    }

    private fun execute(request: Request, provider: KeyProvider): JSONObject {
        val name = provider.displayName
        val response = try {
            http.newCall(request).execute()
        } catch (io: IOException) {
            throw BootstrapException("Couldn't reach $name. Check your connection and try again.", io)
        }
        response.use {
            // Reading the body is a second network read and can fail on its own.
            val raw = try {
                it.body?.string().orEmpty()
            } catch (io: IOException) {
                throw BootstrapException("The connection to $name dropped before it finished replying. Try again.", io)
            }
            if (!it.isSuccessful) throw refusal(it.code, raw, name)
            return runCatching { JSONObject(raw) }.getOrElse {
                throw BootstrapException("$name sent a reply that couldn't be read. Try again.")
            }
        }
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
        return BootstrapException(if (said != null) "$ours $name said: $said" else ours)
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
