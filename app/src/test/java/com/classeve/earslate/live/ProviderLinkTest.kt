package com.classeve.earslate.live

import com.classeve.earslate.bootstrap.SessionCredential
import com.classeve.earslate.session.TranslationProvider
import com.classeve.earslate.testing.FakeSocket
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Opening a provider session, and saying truthfully why one did not open. */
class ProviderLinkTest {

    private fun credential(provider: TranslationProvider) = SessionCredential(
        provider = provider,
        secret = "secret",
        webSocketUrl = "wss://example.invalid/socket",
        model = "model",
        expiresAtMs = Long.MAX_VALUE,
    )

    private fun gemini(socket: FakeSocket) =
        ProviderLink(credential(TranslationProvider.GEMINI), GeminiTranslationProtocol, socket)

    private fun openAi(socket: FakeSocket) =
        ProviderLink(credential(TranslationProvider.OPENAI), OpenAiTranslationProtocol, socket)

    private fun failureOf(link: ProviderLink, target: String = "es"): LinkFailure = runBlocking {
        try {
            link.open(target)
            fail("the session was expected not to open")
            throw IllegalStateException()
        } catch (failure: LinkFailure) {
            failure
        }
    }

    @Test
    fun `a session opens once the provider acknowledges the setup`() = runBlocking {
        val socket = FakeSocket(onSend = { if (it.contains("\"setup\"")) serve("""{"setupComplete":{}}""") })
        val link = gemini(socket)
        val early = link.open("es")

        assertTrue(early.isEmpty())
        assertEquals("wss://example.invalid/socket", socket.url)
        assertEquals("Token secret", socket.headers["Authorization"])
        val setup = JSONObject(socket.sent.single()).getJSONObject("setup")
        assertEquals(
            "es",
            setup.getJSONObject("generationConfig").getJSONObject("translationConfig").getString("targetLanguageCode"),
        )
        assertEquals("es", link.targetWireLanguage)
    }

    @Test
    fun `what OpenAI says before it acknowledges is kept for the session`() = runBlocking {
        val socket = FakeSocket(
            onConnect = {
                accept()
                serve("""{"type":"session.created","session":{"expires_at":1790000000}}""")
            },
            onSend = { if (it.contains("session.update")) serve("""{"type":"session.updated","session":{}}""") },
        )
        val early = openAi(socket).open("es")
        assertEquals(listOf<LiveEvent>(LiveEvent.SessionExpiry(1_790_000_000)), early)
    }

    @Test
    fun `a refused upgrade is reported with the provider's own words`() {
        val failure = failureOf(
            gemini(FakeSocket(onConnect = { fails(httpStatus = 403, body = "API key not valid. Please pass a valid API key.") })),
        )
        assertTrue(failure.providerSpoke)
        assertTrue(failure.message!!, failure.message!!.contains("did not accept the session"))
        assertTrue(failure.message!!, failure.message!!.contains("API key not valid"))
    }

    @Test
    fun `quota is named as quota`() {
        val failure = failureOf(gemini(FakeSocket(onConnect = { fails(httpStatus = 429) })))
        assertTrue(failure.providerSpoke)
        assertTrue(failure.message!!, failure.message!!.contains("quota"))
    }

    // The exact close Gemini sends for a setup it cannot parse.
    @Test
    fun `a close in answer to the setup carries its reason to the user`() {
        val socket = FakeSocket(
            onSend = { serverCloses(1007, "Invalid JSON payload received. Unknown name \"x\" at 'setup': Cannot find field.") },
        )
        val failure = failureOf(gemini(socket))
        assertTrue(failure.providerSpoke)
        assertTrue(failure.message!!, failure.message!!.contains("Cannot find field"))
    }

    @Test
    fun `a network that simply fails is not blamed on the provider`() {
        val failure = failureOf(gemini(FakeSocket(onConnect = { fails() })))
        assertFalse(failure.providerSpoke)
        assertTrue(failure.message!!, failure.message!!.contains("was lost"))
    }

    // An unsupported language comes back as an error event and no acknowledgement at all.
    @Test
    fun `an error event explains a session that never became ready`() {
        val socket = FakeSocket(
            onSend = {
                serve("""{"type":"error","error":{"message":"Unsupported output language: xx."}}""")
                serverCloses(1000, "")
            },
        )
        val failure = failureOf(openAi(socket))
        assertTrue(failure.providerSpoke)
        assertTrue(failure.message!!, failure.message!!.contains("Unsupported output language"))
    }

    // What OpenAI did on 2026-10-06 with a credential it did not accept:
    // the connection was accepted, then one error event, then a close.
    @Test
    fun `a provider that accepts the connection only to say what is wrong is quoted`() {
        val socket = FakeSocket(
            onConnect = {
                accept()
                serve(
                    """{"type":"error","event_id":"event_EVqP5FJCzGh27cS1fNuXZ","error":{"type":"invalid_request_error",""" +
                        """"code":null,"message":"Invalid realtime token","param":null,"event_id":null}}""",
                )
                serverCloses(3000, "invalid_request_error")
            },
        )
        val failure = failureOf(openAi(socket))
        assertTrue(failure.providerSpoke)
        assertEquals("OpenAI said: Invalid realtime token", failure.message)
        assertTrue("nothing is sent to a socket that has hung up", socket.sent.isEmpty())
    }

    // "session.updated: returned when a translation session is updated … unless there is an error."
    @Test
    fun `an error in answer to the setup ends the wait instead of running out the clock`() {
        val socket = FakeSocket(
            onConnect = {
                accept()
                serve("""{"type":"session.created","session":{}}""")
            },
            onSend = { serve("""{"type":"error","error":{"message":"Unsupported output language: xx."}}""") },
        )
        val began = System.currentTimeMillis()
        val failure = failureOf(openAi(socket))

        assertTrue("answered in ${System.currentTimeMillis() - began} ms", System.currentTimeMillis() - began < 6_000)
        assertEquals("OpenAI said: Unsupported output language: xx.", failure.message)
        assertTrue("and the socket is not left open", socket.closedByApp)
    }

    @Test
    fun `a provider in trouble is worth another attempt, and a refusal is not`() {
        assertFalse(failureOf(gemini(FakeSocket(onConnect = { fails(httpStatus = 503) }))).providerSpoke)
        assertTrue(failureOf(gemini(FakeSocket(onConnect = { fails(httpStatus = 403) }))).providerSpoke)
    }

    @Test
    fun `OpenAI can be asked to finish before it is closed, and Gemini has no such request`() = runBlocking {
        val openAiSocket = FakeSocket(
            onSend = { if (it.contains("session.update")) serve("""{"type":"session.updated","session":{}}""") },
        )
        val link = openAi(openAiSocket)
        link.open("es")
        assertTrue(link.sayGoodbye())
        assertEquals("session.close", JSONObject(openAiSocket.sent.last()).getString("type"))
        assertFalse("it is not closed yet: its last words are still to come", openAiSocket.closedByApp)

        val geminiSocket = FakeSocket(onSend = { if (it.contains("\"setup\"")) serve("""{"setupComplete":{}}""") })
        val other = gemini(geminiSocket)
        other.open("es")
        assertFalse(other.sayGoodbye())
        assertEquals(1, geminiSocket.sent.size)
    }

    @Test
    fun `a secret echoed back by the provider never reaches the screen`() {
        val key = "AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY"
        val failure = failureOf(gemini(FakeSocket(onConnect = { fails(httpStatus = 400, body = "Bad token $key supplied") })))
        assertFalse(failure.message!!, failure.message!!.contains(key))
    }

    @Test
    fun `OpenAI can be re-aimed without a new socket, Gemini cannot`() = runBlocking {
        val openAiSocket = FakeSocket(onSend = { if (it.contains("session.update")) serve("""{"type":"session.updated","session":{}}""") })
        val link = openAi(openAiSocket)
        link.open("es")
        assertTrue(link.retarget("fr"))
        assertEquals("fr", link.targetWireLanguage)
        assertTrue(openAiSocket.sent.last().contains("\"fr\""))

        val geminiSocket = FakeSocket(onSend = { if (it.contains("\"setup\"")) serve("""{"setupComplete":{}}""") })
        val fixed = gemini(geminiSocket)
        fixed.open("es")
        assertFalse(fixed.retarget("fr"))
        assertEquals("es", fixed.targetWireLanguage)
    }

    @Test
    fun `closing asks OpenAI to finish first, and just closes Gemini`() = runBlocking {
        val openAiSocket = FakeSocket(onSend = { if (it.contains("session.update")) serve("""{"type":"session.updated","session":{}}""") })
        openAi(openAiSocket).apply { open("es") }.close()
        assertTrue(openAiSocket.sent.last().contains("session.close"))
        assertTrue(openAiSocket.closedByApp)

        val geminiSocket = FakeSocket(onSend = { if (it.contains("\"setup\"")) serve("""{"setupComplete":{}}""") })
        gemini(geminiSocket).apply { open("es") }.close()
        assertEquals(1, geminiSocket.sent.size)
        assertTrue(geminiSocket.closedByApp)
    }
}
