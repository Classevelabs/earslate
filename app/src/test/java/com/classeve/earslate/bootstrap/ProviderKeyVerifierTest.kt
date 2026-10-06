package com.classeve.earslate.bootstrap

import com.classeve.earslate.bootstrap.ProviderKeyVerifier.Result
import com.classeve.earslate.security.KeyProvider
import com.classeve.earslate.testing.FakeSocket
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A key is called good only after a real session has opened with it. */
class ProviderKeyVerifierTest {

    private val requests = AtomicInteger()
    private var status = 200
    private var reply = """{"name":"auth_tokens/1"}"""

    private val http = OkHttpClient.Builder().addInterceptor(
        Interceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        },
    ).build()

    private val sockets = ArrayList<FakeSocket>()

    private fun verify(provider: KeyProvider, language: String, socket: () -> FakeSocket): Result = runBlocking {
        ProviderKeyVerifier(
            minter = ProviderSessionMinter(http, installId = "i"),
            socketFactory = { socket().also { sockets += it } },
        ).verify(provider, "a-key", language)
    }

    private fun rejection(result: Result): String = (result as Result.Rejected).message

    @Test
    fun `a key that opens a real session is good, and the session is closed again`() {
        val result = verify(KeyProvider.GEMINI, "en-US") {
            FakeSocket(onSend = { if (it.contains("\"setup\"")) serve("""{"setupComplete":{}}""") })
        }
        assertEquals(Result.Valid, result)
        assertTrue(sockets.single().closedByApp)
    }

    @Test
    fun `an OpenAI key is proved the same way, and OpenAI is asked to finish`() {
        reply = """{"value":"ek_1","expires_at":1790000000}"""
        val result = verify(KeyProvider.OPENAI, "es-ES") {
            FakeSocket(onSend = { if (it.contains("session.update")) serve("""{"type":"session.updated","session":{}}""") })
        }
        assertEquals(Result.Valid, result)
        assertTrue(sockets.single().sent.last().contains("session.close"))
        assertTrue(sockets.single().closedByApp)
    }

    @Test
    fun `a key the provider refuses is rejected in the provider's words, and no socket is opened`() {
        status = 401
        reply = """{"error":{"message":"Incorrect API key provided.","code":"invalid_api_key"}}"""
        val result = verify(KeyProvider.OPENAI, "es-ES") { FakeSocket() }
        assertEquals("OpenAI did not accept that key. OpenAI said: Incorrect API key provided.", rejection(result))
        assertTrue(sockets.isEmpty())
    }

    // A key can be real and still not be able to translate: the session is the proof.
    @Test
    fun `a key that is accepted but cannot open a session is not called good`() {
        val result = verify(KeyProvider.GEMINI, "en-US") {
            FakeSocket(onConnect = { fails(httpStatus = 403, body = "This model is not available to your project.") })
        }
        assertTrue(rejection(result), rejection(result).contains("not available to your project"))
    }

    @Test
    fun `a language the provider cannot speak is said before the key leaves the phone`() {
        val result = verify(KeyProvider.OPENAI, "pl-PL") { FakeSocket() }
        assertTrue(rejection(result), rejection(result).startsWith("OpenAI can't translate into the language you chose."))
        assertEquals(0, requests.get())
        assertTrue(sockets.isEmpty())
    }
}
