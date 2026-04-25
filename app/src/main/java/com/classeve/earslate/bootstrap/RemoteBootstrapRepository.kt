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
 *
 * Worker error mapping (see /home/mani/Music/SYSTEM/Lven /Lven-Infrastructure
 * /cloudflare-worker/src/routes/earslate.ts):
 *
 *   401              → AuthRequiredException        — refresh failed; clear session
 *   402 SUBSCRIPTION_REQUIRED / ENTITLEMENT_MISSING → SubscriptionRequiredException
 *   429 DAILY_LIMIT_REACHED → DailyLimitReachedException
 *   anything else    → BootstrapException
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
            ?: throw AuthRequiredException()

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
                throw mapErrorResponse(resp.code, raw)
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

    /**
     * Returns a non-expired access token, refreshing if needed. Returns null
     * (and clears the stored session) if no refresh token is present or the
     * refresh failed — the caller treats null as "user must sign in again".
     */
    suspend fun ensureFreshAccessToken(): String? = withContext(Dispatchers.IO) {
        if (!AuthStore.isSessionExpired(appContext)) {
            return@withContext AuthStore.accessTokenOrNull(appContext)
        }
        val refresh = AuthStore.refreshTokenOrNull(appContext) ?: return@withContext null
        val result = DeviceLinkClient.refresh(refresh)
        when (result) {
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

    private fun mapErrorResponse(httpStatus: Int, raw: String): BootstrapException {
        // Parse JSON best-effort. Worker always returns JSON, but the body may
        // be truncated or proxied through an upstream that mangled it.
        val parsed = try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
        val code = parsed?.optString("code").orEmpty()
        val message = parsed?.optString("error", "")
            ?.takeIf { it.isNotBlank() }
            ?: "Bootstrap failed (HTTP $httpStatus)"

        return when {
            httpStatus == 401 -> {
                // Access token rejected even after refresh — wipe it so we
                // don't loop. The session may have been revoked server-side.
                AuthStore.clear(appContext)
                AuthRequiredException(message)
            }
            httpStatus == 402 || code == "SUBSCRIPTION_REQUIRED" || code == "ENTITLEMENT_MISSING" -> {
                SubscriptionRequiredException(message)
            }
            httpStatus == 429 || code == "DAILY_LIMIT_REACHED" -> {
                val cap = parsed?.optInt("daily_cap_seconds", -1)?.takeIf { it > 0 }
                val used = parsed?.optInt("daily_used_seconds", -1)?.takeIf { it >= 0 }
                DailyLimitReachedException(
                    message = message,
                    dailyCapSeconds = cap,
                    dailyUsedSeconds = used,
                )
            }
            else -> BootstrapException("$message (HTTP $httpStatus${if (code.isNotEmpty()) " $code" else ""})")
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
