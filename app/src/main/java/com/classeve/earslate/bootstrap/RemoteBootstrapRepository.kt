package com.classeve.earslate.bootstrap

import android.content.Context
import com.classeve.earslate.BuildConfig
import com.classeve.earslate.auth.AuthStore
import com.classeve.earslate.auth.DeviceLinkClient
import com.classeve.earslate.auth.RefreshResult
import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.TargetLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Production bootstrap repository. Calls the ClassEve Worker's
 * /v1/earslate/bootstrap endpoint, which returns a Gemini Live ephemeral
 * token — single-use, ~10-min lifetime, pre-locked to the model + system
 * prompt. The raw long-lived Gemini API key never reaches the device.
 *
 * Transparently refreshes the user's device access token if it's close to
 * or past expiry before issuing the bootstrap call. On auth failure the
 * caller is expected to route the user back through onboarding (sign-in
 * + device pairing).
 */
class RemoteBootstrapRepository(
    private val appContext: Context,
    private val settings: RemoteBootstrapSettings = RemoteBootstrapSettings(),
) : SessionBootstrapRepository {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun bootstrap(): SessionBootstrap = withContext(Dispatchers.IO) {
        val accessToken = ensureFreshAccessToken()
            ?: throw BootstrapException("Sign-in required — please pair this device again.")

        val workerUrl = workerUrl()
        val request = Request.Builder()
            .url("$workerUrl/v1/earslate/bootstrap")
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json")
            .post("{}".toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val reason = try {
                    val err = JSONObject(raw)
                    val code = err.optString("code")
                    val message = err.optString("error", "Bootstrap failed")
                    "$message (HTTP ${resp.code}${if (code.isNotEmpty()) " $code" else ""})"
                } catch (_: Exception) {
                    "Bootstrap failed (HTTP ${resp.code})"
                }
                throw BootstrapException(reason)
            }

            val body = try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw BootstrapException("Unexpected bootstrap response", e)
            }

            val ephemeral = body.optString("ephemeral_token")
                .ifBlank { throw BootstrapException("Worker returned no ephemeral token") }
            val model = body.optString("model").ifBlank { DEFAULT_MODEL }

            SessionBootstrap(
                ephemeralToken = ephemeral,
                model = model,
                targetLanguage = settings.targetLanguage,
                voiceName = settings.voiceName,
                captionsEnabled = settings.captionsEnabled,
                sessionPolicy = settings.sessionPolicy,
                source = BootstrapSource.REMOTE_WORKER,
            )
        }
    }

    private suspend fun ensureFreshAccessToken(): String? {
        if (!AuthStore.isSessionExpired(appContext)) {
            return AuthStore.accessTokenOrNull(appContext)
        }
        val refresh = AuthStore.refreshTokenOrNull(appContext) ?: return null
        val result = DeviceLinkClient.refresh(refresh)
        return when (result) {
            is RefreshResult.Success -> {
                AuthStore.save(
                    appContext,
                    com.classeve.earslate.auth.AuthSession(
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        expiresAtEpochMs = System.currentTimeMillis() + result.expiresInSeconds * 1000,
                        email = AuthStore.load(appContext)?.email,
                    ),
                )
                result.accessToken
            }
            RefreshResult.Failed -> {
                AuthStore.clear(appContext)
                null
            }
        }
    }

    private fun workerUrl(): String {
        val configured = BuildConfig.WORKER_URL
        return if (configured.isNotBlank()) configured else "https://lven-api.lven.workers.dev"
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        const val DEFAULT_MODEL: String = "gemini-3.1-flash-live-preview"
    }
}

/** Settings surface for earslate's remote bootstrap. */
data class RemoteBootstrapSettings(
    val targetLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    val voiceName: String? = null,
    val captionsEnabled: Boolean = true,
    val sessionPolicy: SessionPolicy = SessionPolicy.Default,
)
