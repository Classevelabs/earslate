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
 * Worker error mapping (see the Worker source
 * /cloudflare-worker/src/routes/earslate.ts):
 *
 *   401              → AuthRequiredException        — sign-in required (session kept; re-pair overwrites)
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
     * if no refresh token is present or the Worker definitively rejected the
     * refresh — the caller treats null as "user must sign in again".
     *
     * Transient refresh failures (5xx/429/network) propagate as IOException
     * from [DeviceLinkClient.refresh] so they surface as BOOTSTRAP_FAILED
     * (retry later), never as a sign-in bounce.
     *
     * NOTE: this method never clears the stored session. Auth UX directive:
     * once a device is paired it stays paired — only an explicit user
     * sign-out (or a successful re-pair overwriting the slot) may replace
     * tokens. Even on a definitive rejection we keep the local session so a
     * server-side hiccup misclassified as a 4xx cannot permanently un-pair
     * the device; the user lands on the sign-in screen and re-pairing
     * overwrites the session.
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
            RefreshResult.Failed -> null
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
                // Access token rejected. Zero the stored expiry so the next
                // attempt is forced through a refresh-token rotation instead
                // of replaying the same rejected access token. Deliberately
                // NOT AuthStore.clear(): once paired, only manual sign-out
                // (or a successful re-pair) replaces local tokens — a spurious
                // 401 must never permanently un-pair the device.
                AuthStore.markAccessTokenExpired(appContext)
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
        return if (configured.isNotBlank()) configured else "https://api.classeve.com"
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        const val DEFAULT_MODEL: String = "gemini-3.5-live-translate-preview"
    }
}

/** Settings surface for earslate's remote bootstrap. */
data class RemoteBootstrapSettings(
    val targetLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    val voiceName: String? = null,
    val captionsEnabled: Boolean = true,
    val sessionPolicy: SessionPolicy = SessionPolicy.Default,
)
