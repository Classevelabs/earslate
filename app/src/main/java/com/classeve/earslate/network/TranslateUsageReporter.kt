package com.classeve.earslate.network

import android.content.Context
import android.util.Log
import com.classeve.earslate.BuildConfig
import com.classeve.earslate.auth.AuthStore
import com.classeve.earslate.bootstrap.RemoteBootstrapRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Periodic usage reporter for the live translator.
 *
 * Worker contract (see /v1/earslate/heartbeat in
 * Lven-Infrastructure/cloudflare-worker/src/routes/earslate.ts):
 *
 *   POST {"used_seconds": <int>}
 *   200 → {"daily_used_seconds": <int>, "daily_remaining_seconds": <int>}
 *   429 → {"error":..., "code":"DAILY_LIMIT_REACHED"} — close the session
 *   401 → token expired; refresh via [RemoteBootstrapRepository] and retry once
 *
 * The session coordinator calls [heartbeat] every ~60 s. The result is one of:
 *   - [Result.Ok]              keep the session alive
 *   - [Result.LimitReached]    stop the session, surface to UI
 *   - [Result.AuthRequired]    refresh failed; bounce to sign-in
 *   - [Result.TransientError]  network blip; coordinator may retry on the next tick
 *
 * The reporter also exposes a hot [dailyLimitReached] flow so the UI can
 * subscribe directly without going through coordinator state — convenient
 * because the coordinator may be torn down before the toast shows.
 */
class TranslateUsageReporter(
    private val appContext: Context,
    private val bootstrap: RemoteBootstrapRepository,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    sealed class Result {
        data class Ok(val dailyUsedSeconds: Int, val dailyRemainingSeconds: Int) : Result()
        object LimitReached : Result()
        object AuthRequired : Result()
        data class TransientError(val message: String) : Result()
    }

    private val _dailyLimitReached = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 4)
    val dailyLimitReached: SharedFlow<Unit> = _dailyLimitReached.asSharedFlow()

    /**
     * Posts a heartbeat carrying [secondsActive]. Returns the parsed result.
     * Never throws — all failure paths return a [Result] case so the caller
     * can decide whether to keep the session or stop.
     *
     * On a 401 we drop the stored access token (so the next
     * [ensureFreshAccessToken] is forced to refresh from the refresh-token,
     * not return the same rejected access token) and retry exactly once. If
     * the refresh fails or the second attempt also 401s, we return
     * [Result.AuthRequired] and the caller bounces to the sign-in screen.
     */
    suspend fun heartbeat(secondsActive: Int): Result {
        if (secondsActive <= 0) return Result.Ok(0, 0)
        val capped = secondsActive.coerceAtMost(MAX_DELTA_SECONDS)

        // First attempt with the current token. A transient refresh failure
        // (5xx/network — surfaced as an exception from ensureFreshAccessToken)
        // must NOT escape: the heartbeat contract is "never throws", and an
        // exception here would crash the whole live session instead of just
        // carrying the unsent seconds to the next tick.
        var token = try {
            bootstrap.ensureFreshAccessToken()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return Result.TransientError(t.message ?: "Token refresh failed")
        } ?: return Result.AuthRequired

        var result = postOnce(token, capped)

        // 401 — server rejected a token the local clock believed was valid
        // (server-side revocation, key rotation, clock skew). Force the
        // refresh by zeroing the stored token's expiry, then retry once.
        if (result is Result.AuthRequired) {
            AuthStore.markAccessTokenExpired(appContext)
            token = try {
                bootstrap.ensureFreshAccessToken()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                return Result.TransientError(t.message ?: "Token refresh failed")
            } ?: return Result.AuthRequired
            result = postOnce(token, capped)
        }

        if (result is Result.LimitReached) {
            _dailyLimitReached.tryEmit(Unit)
        }
        return result
    }

    private suspend fun postOnce(token: String, seconds: Int): Result = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${workerUrl()}/v1/earslate/heartbeat")
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .post(
                JSONObject()
                    .put("used_seconds", seconds)
                    .toString()
                    .toRequestBody(JSON),
            )
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                when (resp.code) {
                    200 -> {
                        val json = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                        Result.Ok(
                            dailyUsedSeconds = json.optInt("daily_used_seconds", 0),
                            dailyRemainingSeconds = json.optInt("daily_remaining_seconds", 0),
                        )
                    }
                    401 -> {
                        Log.w(TAG, "heartbeat 401 — token rejected")
                        Result.AuthRequired
                    }
                    429 -> {
                        Log.i(TAG, "heartbeat 429 — daily limit reached")
                        Result.LimitReached
                    }
                    else -> Result.TransientError("HTTP ${resp.code}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "heartbeat network error: ${t.message}")
            Result.TransientError(t.message ?: "Network error")
        }
    }

    private fun workerUrl(): String {
        val configured = BuildConfig.WORKER_URL
        return if (configured.isNotBlank()) configured else "https://api.classeve.com"
    }

    companion object {
        private const val TAG = "TranslateUsage"

        /**
         * Worker caps a single heartbeat at 300 s. We send 60 by default so
         * one missed tick can still be made up on the next, but never more
         * than the worker accepts.
         */
        private const val MAX_DELTA_SECONDS = 300

        private val JSON = "application/json".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
