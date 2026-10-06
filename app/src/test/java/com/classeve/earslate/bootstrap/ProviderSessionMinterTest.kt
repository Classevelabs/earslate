package com.classeve.earslate.bootstrap

import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.session.TranslationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private class Exchange(val status: Int, val body: String, val date: String? = null) {
        var request: Request? = null
        var sent: String = ""
        val all = ArrayList<String>()
    }

    private fun minter(exchange: Exchange, failWith: IOException? = null): ProviderSessionMinter {
        val client = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                failWith?.let { throw it }
                val request = chain.request()
                exchange.request = request
                exchange.sent = Buffer().also { request.body?.writeTo(it) }.readUtf8()
                exchange.all += exchange.sent
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(exchange.status)
                    .message("")
                    .apply { exchange.date?.let { header("Date", it) } }
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

    // The request carries absolute times. Asked on a slow clock, Google issues
    // a token that has already expired, without a word; measured 2026-10-06.
    @Test
    fun `a phone whose clock is wrong asks again on the provider's clock`() = runBlocking {
        // The phone thinks it is 14:13:20. The reply is dated forty minutes later.
        val exchange = Exchange(200, """{"name":"auth_tokens/abc"}""", date = "Mon, 21 Sep 2026 14:53:20 GMT")
        val credential = minter(exchange).mint(KeyProvider.GEMINI, key)

        assertEquals("asked twice", 2, exchange.all.size)
        assertEquals("first by its own clock", "2026-09-21T14:43:20Z", JSONObject(exchange.all[0]).getString("expireTime"))
        assertEquals("then by the provider's", "2026-09-21T15:23:20Z", JSONObject(exchange.all[1]).getString("expireTime"))
        assertEquals("good for the full half hour from now", now + 30 * 60_000L, credential.expiresAtMs)
        assertEquals(40 * 60_000L, credential.serverAheadMs)
    }

    @Test
    fun `a phone whose clock is right asks once`() = runBlocking {
        val exchange = Exchange(200, """{"name":"auth_tokens/abc"}""", date = "Mon, 21 Sep 2026 14:13:50 GMT")
        minter(exchange).mint(KeyProvider.GEMINI, key)
        assertEquals(1, exchange.all.size)
    }

    // ── OpenAI ──────────────────────────────────────────────────────────

    @Test
    fun `OpenAI's expiry is read on the provider's clock, not the phone's`() = runBlocking {
        // The reply is dated ten minutes ahead of the phone; the secret expires half an hour after that.
        val exchange = Exchange(200, """{"value":"ek_123","expires_at":1790002400}""", date = "Mon, 21 Sep 2026 14:23:20 GMT")
        val credential = minter(exchange).mint(KeyProvider.OPENAI, "sk-test")

        assertEquals("half an hour from now, on this phone", now + 30 * 60_000L, credential.expiresAtMs)
        assertEquals(10 * 60_000L, credential.serverAheadMs)
    }

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

    // The first retry of a lost connection used to end the session on any of these.
    @Test
    fun `only what another attempt could cure is marked as worth one`() {
        fun failure(status: Int, io: IOException? = null): BootstrapException = runBlocking {
            runCatching { minter(Exchange(status, "{}"), io).mint(KeyProvider.GEMINI, key) }.exceptionOrNull() as BootstrapException
        }
        assertTrue("no network", failure(200, IOException("unreachable")).transient)
        assertTrue("the provider is in trouble", failure(503).transient)
        assertFalse("a refused key", failure(403).transient)
        assertFalse("out of quota", failure(429).transient)
    }

    // A zero-width space survives trim() and every check for spaces, and the
    // library's complaint about such a header quotes the whole key.
    @Test
    fun `a key carrying a character no header can hold is refused before it is sent, and never quoted`() = runBlocking {
        val exchange = Exchange(200, """{"name":"auth_tokens/abc"}""")
        val pasted = key.take(16) + "\u200B" + key.drop(16)
        val message = try {
            minter(exchange).mint(KeyProvider.GEMINI, pasted)
            fail("it was sent")
            ""
        } catch (failure: BootstrapException) {
            failure.message.orEmpty()
        }
        assertTrue(message, message.startsWith("That key has a character in it"))
        assertFalse(message, message.contains(key.take(16)))
        assertNull("nothing was sent", exchange.request)
    }

    // Stop used to wait out the whole request, up to twenty-five seconds.
    @Test
    fun `a request nobody is waiting for any more is let go at once`() {
        val never = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                Thread.sleep(4_000)
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            },
        ).build()
        val began = System.currentTimeMillis()
        runBlocking {
            val asking = launch(Dispatchers.Default) {
                runCatching { ProviderSessionMinter(never, installId = "i").mint(KeyProvider.GEMINI, key) }
            }
            delay(200)
            asking.cancelAndJoin()
        }
        assertTrue("let go after ${System.currentTimeMillis() - began} ms", System.currentTimeMillis() - began < 2_000)
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
