package com.classeve.earslate.bootstrap

import com.classeve.earslate.live.LiveSessionConfigFactory
import com.classeve.earslate.security.KeyProvider
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
 * Exchanges the user's own long-lived API key for a short-lived, single-use
 * session credential, then hands back everything needed to open the provider
 * socket.
 *
 * The exchange matters. We could put the user's real key straight onto the
 * WebSocket, and it would work — but that key would then live for the whole
 * session on a long-lived connection, and any log, crash report, or proxy that
 * ever saw the URL would have it forever. Instead the long-lived key is used
 * once, over HTTPS, to mint a credential that expires in minutes and is scoped
 * to exactly one session. The socket never carries the real key.
 *
 * This runs entirely on the device. There is no ClassEve server in this path —
 * or in any other path. Requests go from the phone directly to Google or
 * OpenAI, authenticated with the user's own key, billed to the user's own
 * account.
 */
class ProviderSessionMinter(
    private val http: OkHttpClient = defaultClient(),
    /**
     * Stable per-install identifier, hashed before it is sent. OpenAI uses it
     * to attribute abuse signals to a device rather than to the whole account,
     * which protects the user's other OpenAI usage if something goes wrong
     * here. It identifies an installation, never a person.
     */
    private val installId: String,
) {

    /**
     * @param captionsEnabled must be the SAME value the session's setup frame
     *   will be built with. The token locks the session configuration, so a
     *   token minted with transcription enabled and a setup frame that omits it
     *   describe two different sessions. Both are now built by
     *   [LiveSessionConfigFactory] from this one flag; see
     *   `GeminiSessionSetupParityTest`.
     */
    suspend fun mint(
        provider: KeyProvider,
        apiKey: String,
        targetLanguageCode: String,
        captionsEnabled: Boolean,
    ): SessionBootstrap = withContext(Dispatchers.IO) {
        val language = LanguageCodes.normalize(targetLanguageCode)
            ?: throw BootstrapException("Choose a supported target language.")
        when (provider) {
            KeyProvider.GEMINI -> mintGemini(apiKey, language, captionsEnabled)
            KeyProvider.OPENAI -> mintOpenAI(apiKey, language)
        }
    }

    private fun mintGemini(
        apiKey: String,
        language: String,
        captionsEnabled: Boolean,
    ): SessionBootstrap {
        val now = System.currentTimeMillis()
        val expiresAt = iso8601(now + 30 * 60_000L)
        val newSessionExpiresAt = iso8601(now + 60_000L)

        // Request shape verified against the live v1alpha endpoint on
        // 2026-07-26. Two things here are easy to get wrong, and both produced
        // a flat 400 that looked like a bad key rather than a bad request:
        //
        //  1. There is NO "authToken" wrapper. The AuthToken fields sit at the
        //     top level of the body. Sending the wrapper returns
        //     'Unknown name "authToken" at auth_token: Cannot find field'.
        //  2. inputAudioTranscription / outputAudioTranscription belong to
        //     bidiGenerateContentSetup, NOT to generationConfig. translationConfig
        //     is the opposite — it lives inside generationConfig.
        //
        // This object is NOT built here any more. It is the same session setup
        // the client sends once the socket opens, and building it twice is what
        // let the two drift: the copy that used to live here always carried
        // transcription, while the client's copy omitted it with captions off.
        // Embed, never re-describe.
        val setup = JSONObject(
            LiveSessionConfigFactory.buildTokenSessionSetup(
                model = GEMINI_MODEL,
                targetLanguageCode = language,
                // Silence this leg when the speaker is already speaking the
                // target language. It is what lets two legs run at once without
                // talking over each other.
                echoTargetLanguage = false,
                captionsEnabled = captionsEnabled,
            ),
        )

        val body = JSONObject()
            .put("uses", 1)
            .put("expireTime", expiresAt)
            .put("newSessionExpireTime", newSessionExpiresAt)
            .put("bidiGenerateContentSetup", setup)

        val request = Request.Builder()
            // The key goes in the query string because that is the only form
            // this endpoint accepts. It is one HTTPS request; the socket that
            // follows carries the minted token instead.
            .url("$GEMINI_TOKEN_URL?key=${apiKey.urlEncoded()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val json = execute(request, KeyProvider.GEMINI)
        val name = json.optString("name").takeIf { it.isNotBlank() }
            ?: throw BootstrapException("Gemini returned a session without a credential. Try again.")

        return SessionBootstrap(
            credential = name,
            provider = KeyProvider.GEMINI.provider,
            webSocketUrl = GEMINI_WSS,
            model = GEMINI_MODEL,
            expiresAt = json.optString("expireTime").takeIf { it.isNotBlank() } ?: expiresAt,
        )
    }

    private fun mintOpenAI(apiKey: String, language: String): SessionBootstrap {
        val body = JSONObject().put(
            "session",
            JSONObject()
                .put("model", OPENAI_MODEL)
                .put("audio", JSONObject().put("output", JSONObject().put("language", language))),
        )

        val request = Request.Builder()
            .url(OPENAI_SECRET_URL)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("OpenAI-Safety-Identifier", safetyIdentifier())
            .post(body.toString().toRequestBody(JSON))
            .build()

        val json = execute(request, KeyProvider.OPENAI)
        val value = json.optString("value").takeIf { it.isNotBlank() }
            ?: throw BootstrapException("OpenAI returned a session without a credential. Try again.")
        val expiresAtEpoch = json.optLong("expires_at", 0L)

        return SessionBootstrap(
            credential = value,
            provider = KeyProvider.OPENAI.provider,
            webSocketUrl = "$OPENAI_WSS?model=${OPENAI_MODEL.urlEncoded()}",
            model = OPENAI_MODEL,
            expiresAt = if (expiresAtEpoch > 0) iso8601(expiresAtEpoch * 1000L) else null,
        )
    }

    /**
     * Runs the request and turns provider failures into sentences a user can
     * act on. The provider's own error text is deliberately not surfaced: it is
     * written for API developers, often mentions parameters the user has never
     * heard of, and occasionally echoes the key back.
     */
    private fun execute(request: Request, provider: KeyProvider): JSONObject {
        val response = try {
            http.newCall(request).execute()
        } catch (io: IOException) {
            throw BootstrapException(
                "Couldn't reach ${provider.displayName}. Check your connection and try again.",
                io,
            )
        }
        response.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw explain(it.code, provider)
            return runCatching { JSONObject(raw) }.getOrElse {
                throw BootstrapException("${provider.displayName} sent a reply we couldn't read. Try again.")
            }
        }
    }

    private fun explain(code: Int, provider: KeyProvider): BootstrapException {
        val name = provider.displayName
        val message = when (code) {
            400 -> "$name rejected the session request. Your key may not have access to the " +
                "live translation model yet."

            401, 403 -> "$name did not accept that key. Check it was copied in full from " +
                "${provider.consoleName}, and that it hasn't been revoked."

            402 -> "Your $name account needs billing set up before it can run live translation."

            404 -> "$name doesn't offer the live translation model on this key. It may not be " +
                "available in your account or region yet."

            429 -> "Your $name key is out of quota, or is being rate limited. Wait a moment, or " +
                "check your usage limits."

            in 500..599 -> "$name is having trouble right now. Try again in a moment."

            else -> "$name couldn't start a translation session (error $code)."
        }
        return BootstrapException(message)
    }

    private fun safetyIdentifier(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(installId.toByteArray(Charsets.UTF_8))
        return "earslate_" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun iso8601(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(epochMillis))

    private fun String.urlEncoded(): String =
        java.net.URLEncoder.encode(this, "UTF-8")

    companion object {
        // Pinned here rather than fetched, so the app has no configuration
        // server of any kind. Bump with a release when a provider moves.
        const val GEMINI_MODEL = "gemini-3.5-live-translate-preview"
        const val OPENAI_MODEL = "gpt-realtime-translate"

        private const val GEMINI_TOKEN_URL =
            "https://generativelanguage.googleapis.com/v1alpha/auth_tokens"
        private const val OPENAI_SECRET_URL =
            "https://api.openai.com/v1/realtime/translations/client_secrets"
        const val GEMINI_WSS =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"
        const val OPENAI_WSS = "wss://api.openai.com/v1/realtime/translations"

        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * Language-code normalisation, matching what the provider APIs accept.
 *
 * Chinese and Portuguese are the two cases where the region genuinely changes
 * the output rather than just the accent, so they keep a script/region suffix;
 * everything else reduces to the base language. Pure and side-effect free so it
 * can be tested without a device.
 */
object LanguageCodes {
    private val SHAPE = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?$")

    fun normalize(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (!SHAPE.matches(value)) return null
        val parts = value.split("-")
        val language = parts[0].lowercase()
        val region = parts.getOrNull(1)?.lowercase()

        if (language == "zh" && region != null) {
            return if (region == "tw" || region == "hant") "zh-Hant" else "zh-Hans"
        }
        if (language == "pt" && region != null) {
            return if (region == "pt") "pt-PT" else "pt-BR"
        }
        return language
    }
}
