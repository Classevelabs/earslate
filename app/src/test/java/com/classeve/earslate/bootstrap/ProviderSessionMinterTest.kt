package com.classeve.earslate.bootstrap

import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * The credential request each provider is actually sent, captured from the
 * real minter, and what the user is told when a provider says no.
 */
class ProviderSessionMinterTest {

    private val now = 1_790_000_000_000L
    private val key = "AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY"

    private class Exchange(val status: Int, val body: String) {
        var request: Request? = null
        var sent: String = ""
    }

    private fun minter(exchange: Exchange, failWith: IOException? = null): ProviderSessionMinter {
        val client = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                failWith?.let { throw it }
                val request = chain.request()
                exchange.request = request
                exchange.sent = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(exchange.status)
                    .message("")
                    .body(exchange.body.toResponseBody("application/json".toMediaType()))
                    .build()
            },
        ).build()
        return ProviderSessionMinter(client, installId = "install-1", now = { now })
    }

    private fun refusal(provider: KeyProvider, status: Int, body: String): String = runBlocking {
        try {
            minter(Exchange(status, body)).mint(provider, key)
            fail("the provider was expected to refuse")
            ""
        } catch (failure: BootstrapException) {
            failure.message.orEmpty()
        }
    }

    // ── Gemini ──────────────────────────────────────────────────────────

    @Test
    fun `the Gemini key travels in a header and never in the address`() = runBlocking {
        val exchange = Exchange(200, """{"name":"auth_tokens/abc"}""")
        minter(exchange).mint(KeyProvider.GEMINI, key)
        val request = exchange.request!!
        assertEquals("https://generativelanguage.googleapis.com/v1beta/auth_tokens", request.url.toString())
        assertEquals(key, request.header("x-goog-api-key"))
        assertNull(request.url.query)
        assertFalse(request.url.toString().contains(key))
    }

    @Test
    fun `one Gemini token serves a whole session and is pinned to the translate model`() = runBlocking {
        val exchange = Exchange(200, """{"name":"auth_tokens/abc"}""")
        minter(exchange).mint(KeyProvider.GEMINI, key)
        val body = JSONObject(exchange.sent)

        assertEquals("no limit on sessions, so both directions and every replacement share it", 0, body.getInt("uses"))
        assertEquals("2026-09-21T14:43:20Z", body.getString("expireTime"))
        assertEquals(body.getString("expireTime"), body.getString("newSessionExpireTime"))
        assertEquals("model", body.getString("fieldMask"))
        val pinned = body.getJSONObject("bidiGenerateContentSetup")
        assertEquals("models/gemini-3.5-live-translate-preview", pinned.getString("model"))
        assertEquals("the language is each socket's own to set", 1, pinned.length())
        assertFalse("the endpoint rejects an authToken wrapper", body.has("authToken"))
    }

    @Test
    fun `the Gemini credential points at the v1beta constrained socket`() = runBlocking {
        val credential = minter(Exchange(200, """{"name":"auth_tokens/abc"}""")).mint(KeyProvider.GEMINI, key)
        assertEquals(TranslationProvider.GEMINI, credential.provider)
        assertEquals("auth_tokens/abc", credential.secret)
        assertEquals(now + 30 * 60_000L, credential.expiresAtMs)
        assertTrue(credential.webSocketUrl.endsWith("v1beta.GenerativeService.BidiGenerateContentConstrained"))
        assertFalse("printing a credential must not print its secret", credential.toString().contains("auth_tokens/abc"))
    }

    // ── OpenAI ──────────────────────────────────────────────────────────

    @Test
    fun `the OpenAI secret is asked for on the translations endpoint, for the translate model`() = runBlocking {
        val exchange = Exchange(200, """{"value":"ek_123","expires_at":1790001800}""")
        val credential = minter(exchange).mint(KeyProvider.OPENAI, "sk-test")
        val request = exchange.request!!
        assertEquals("https://api.openai.com/v1/realtime/translations/client_secrets", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        assertTrue(request.header("OpenAI-Safety-Identifier")!!.startsWith("earslate_"))
        assertFalse("the install id itself is never sent", request.header("OpenAI-Safety-Identifier")!!.contains("install-1"))

        val body = JSONObject(exchange.sent)
        assertEquals("gpt-realtime-translate", body.getJSONObject("session").getString("model"))
        assertFalse("each socket sets its own language", body.getJSONObject("session").has("audio"))
        assertEquals("created_at", body.getJSONObject("expires_after").getString("anchor"))
        assertEquals(1800, body.getJSONObject("expires_after").getInt("seconds"))

        assertEquals("ek_123", credential.secret)
        assertEquals(1_790_001_800_000L, credential.expiresAtMs)
        assertEquals("wss://api.openai.com/v1/realtime/translations?model=gpt-realtime-translate", credential.webSocketUrl)
        assertEquals(request.header("OpenAI-Safety-Identifier"), credential.safetyIdentifier)
    }

    // ── refusals ────────────────────────────────────────────────────────

    // A guess from the status code used to be all the user got: "your key may not have access".
    @Test
    fun `the provider's own reason is shown`() {
        val message = refusal(
            KeyProvider.OPENAI,
            400,
            """{"error":{"message":"Unsupported output language: pa. Supported: es, pt, fr.","type":"invalid_request_error"}}""",
        )
        assertTrue(message, message.contains("OpenAI said: Unsupported output language: pa"))
    }

    @Test
    fun `a rejected key is named as a rejected key, with the provider's words after it`() {
        val message = refusal(
            KeyProvider.GEMINI,
            403,
            """{"error":{"code":403,"message":"Method doesn't allow unregistered callers.","status":"PERMISSION_DENIED"}}""",
        )
        assertTrue(message, message.startsWith("Google Gemini did not accept that key."))
        assertTrue(message, message.contains("unregistered callers"))
    }

    // Google's reply on 2026-10-06 to a key that was never real.
    @Test
    fun `Google's reply to a wrong key is named as a rejected key`() {
        assertEquals(
            "Google Gemini did not accept that key. Google Gemini said: API key not valid. Please pass a valid API key.",
            refusal(KeyProvider.GEMINI, 400, GOOGLE_WRONG_KEY),
        )
    }

    // The fields of OpenAI's reply on 2026-10-06 to a key that was never real.
    @Test
    fun `OpenAI's reply to a wrong key is named as a rejected key`() {
        val message = refusal(
            KeyProvider.OPENAI,
            401,
            """{"error":{"message":"Incorrect API key provided: sk-not-a*********-000. You can find your API key at """ +
                """https://platform.openai.com/account/api-keys.","type":"invalid_request_error","code":"invalid_api_key"}}""",
        )
        assertTrue(message, message.startsWith("OpenAI did not accept that key. OpenAI said: Incorrect API key provided"))
    }

    @Test
    fun `quota is named as quota`() {
        val message = refusal(KeyProvider.OPENAI, 429, """{"error":{"message":"You exceeded your current quota."}}""")
        assertTrue(message, message.contains("out of quota"))
        assertTrue(message, message.contains("You exceeded your current quota"))
    }

    @Test
    fun `a key the provider echoes back is never shown`() {
        val message = refusal(KeyProvider.GEMINI, 400, """{"error":{"message":"API key not valid: $key"}}""")
        assertFalse(message, message.contains(key))
        assertTrue(message, message.contains("API key not valid"))
    }

    @Test
    fun `a refusal with nothing readable in it still says something true`() {
        assertEquals("OpenAI could not start a translation session.", refusal(KeyProvider.OPENAI, 400, "<html>bad gateway</html>"))
        assertEquals(
            "Google Gemini is having trouble right now. Try again in a moment.",
            refusal(KeyProvider.GEMINI, 503, ""),
        )
    }

    @Test
    fun `no network is reported as no network`() = runBlocking {
        val message = try {
            minter(Exchange(200, "{}"), failWith = IOException("unreachable")).mint(KeyProvider.GEMINI, key)
            fail("expected a failure")
            ""
        } catch (failure: BootstrapException) {
            failure.message.orEmpty()
        }
        assertTrue(message, message.startsWith("Couldn't reach Google Gemini."))
    }

    @Test
    fun `a reply without a credential is a failure, not a session`() {
        assertTrue(refusalOnSuccess(KeyProvider.GEMINI, "{}").contains("without a credential"))
        assertTrue(refusalOnSuccess(KeyProvider.OPENAI, """{"expires_at":1}""").contains("without a credential"))
        assertTrue(refusalOnSuccess(KeyProvider.GEMINI, "not json").contains("couldn't be read"))
    }

    private fun refusalOnSuccess(provider: KeyProvider, body: String): String = refusal(provider, 200, body)

    private companion object {
        const val GOOGLE_WRONG_KEY =
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID","domain":"googleapis.com","metadata":{"service":"generativelanguage.googleapis.com"}},{"@type":"type.googleapis.com/google.rpc.LocalizedMessage","locale":"en-US","message":"API key not valid. Please pass a valid API key."}]}}"""
    }
}
