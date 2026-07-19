package com.classeve.earslate.bootstrap

import android.content.Context
import com.classeve.earslate.BuildConfig
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Account-free client for the anonymous short-lived session broker. */
class AnonymousSessionBootstrapRepository(
    context: Context,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) : SessionBootstrapRepository {
    private val installId = InstallationId.loadOrCreate(context.applicationContext)

    override suspend fun bootstrap(
        provider: TranslationProvider,
        targetLanguageCode: String,
    ): SessionBootstrap = withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.EARSLATE_WORKER_URL.trimEnd('/') + "/v1/earslate/session"
        val requestBody = JSONObject()
            .put("provider", provider.wireValue)
            .put("target_language", targetLanguageCode)
            .toString()
        val request = Request.Builder()
            .url(endpoint)
            .header("Content-Type", "application/json")
            .header("X-Earslate-Install-Id", installId)
            .post(requestBody.toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val body = runCatching { JSONObject(raw) }.getOrNull()
            if (!response.isSuccessful) {
                val message = body?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: "Translation service is unavailable (HTTP ${response.code})."
                throw BootstrapException(message)
            }
            val actualProvider = TranslationProvider.fromWireValue(body?.optString("provider"))
            if (actualProvider == TranslationProvider.AUTOMATIC) {
                throw BootstrapException("The translation service returned an invalid provider.")
            }
            SessionBootstrap(
                credential = body?.optString("credential")?.takeIf { it.isNotBlank() }
                    ?: throw BootstrapException("The translation service returned no credential."),
                provider = actualProvider,
                webSocketUrl = body.optString("wss_url").takeIf { it.startsWith("wss://") }
                    ?: throw BootstrapException("The translation service returned an invalid endpoint."),
                model = body.optString("model").takeIf { it.isNotBlank() }
                    ?: throw BootstrapException("The translation service returned no model."),
                expiresAt = body.optString("expires_at").takeIf { it.isNotBlank() },
            )
        }
    }

    private object InstallationId {
        private const val PREFS = "earslate_installation"
        private const val KEY = "anonymous_install_id"

        fun loadOrCreate(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY, null)
            if (existing != null && runCatching { UUID.fromString(existing) }.isSuccess) return existing
            return UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).apply() }
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
