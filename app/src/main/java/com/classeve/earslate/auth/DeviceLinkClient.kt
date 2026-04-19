package com.classeve.earslate.auth

import android.os.Build
import com.classeve.earslate.BuildConfig
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
 * RFC 8628 device-authorization grant against the ClassEve Worker. Mirrors
 * the Lven-Android implementation so a user who has signed in for Lven can
 * immediately use earslate on the same account — one device pairing per
 * device, shared across both products.
 */
data class DeviceCodeResponse(
    val deviceCode: String,
    val userCode: String,
    val verificationUriComplete: String,
    val expiresIn: Int,
    val interval: Int,
)

sealed class PollResult {
    data class Authorized(
        val accessToken: String,
        val refreshToken: String,
        val expiresInSeconds: Long,
    ) : PollResult()
    data object Pending : PollResult()
    data object SlowDown : PollResult()
    data object Expired : PollResult()
    data object Denied : PollResult()
}

sealed class RefreshResult {
    data class Success(val accessToken: String, val refreshToken: String, val expiresInSeconds: Long) : RefreshResult()
    data object Failed : RefreshResult()
}

object DeviceLinkClient {
    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun base(): String {
        val configured = BuildConfig.WORKER_URL
        return if (configured.isNotBlank()) configured else "https://lven-api.lven.workers.dev"
    }

    suspend fun requestCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("device_kind", "android")
            .put("device_name", Build.MODEL ?: "android")
        val req = Request.Builder()
            .url("${base()}/v1/device/code")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val json = exec(req)
        DeviceCodeResponse(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUriComplete = json.getString("verification_uri_complete"),
            expiresIn = json.optInt("expires_in", 900),
            interval = json.optInt("interval", 5),
        )
    }

    suspend fun poll(deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_code", deviceCode)
        val req = Request.Builder()
            .url("${base()}/v1/device/poll")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val json = JSONObject(raw)
            if (resp.isSuccessful) {
                return@withContext PollResult.Authorized(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.getString("refresh_token"),
                    expiresInSeconds = json.optLong("expires_in", 3600),
                )
            }
            when (json.optString("error")) {
                "authorization_pending" -> PollResult.Pending
                "slow_down" -> PollResult.SlowDown
                "expired_token" -> PollResult.Expired
                "access_denied" -> PollResult.Denied
                else -> throw IOException("Unexpected poll error HTTP ${resp.code}")
            }
        }
    }

    suspend fun refresh(refreshToken: String): RefreshResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("refresh_token", refreshToken)
        val req = Request.Builder()
            .url("${base()}/v1/auth/refresh")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return@withContext RefreshResult.Failed
            val json = JSONObject(raw)
            RefreshResult.Success(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                expiresInSeconds = json.optLong("expires_in", 3600),
            )
        }
    }

    private fun exec(req: Request): JSONObject {
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = try { JSONObject(raw).optString("error", "Request failed") } catch (_: Exception) { "Request failed (${resp.code})" }
                throw IOException(msg)
            }
            return JSONObject(raw)
        }
    }
}
