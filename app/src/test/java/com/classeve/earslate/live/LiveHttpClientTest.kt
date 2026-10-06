package com.classeve.earslate.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveHttpClientTest {

    private val client = OkHttpLiveSocketClient.newHttpClient()

    @Test
    fun `a frame is sent as it is written, not held back to fill a packet`() {
        client.socketFactory.createSocket().use { assertTrue(it.tcpNoDelay) }
    }

    // The library stops timing reads once a socket is open; this covers the
    // handshake, which used to be able to hang for ever.
    @Test
    fun `a handshake that gets no answer is given up on, and never sooner than a pong can arrive`() {
        assertEquals(20_000, client.readTimeoutMillis)
        assertTrue(client.readTimeoutMillis >= 2 * client.pingIntervalMillis)
    }

    @Test
    fun `a dead socket is found by its ping`() {
        assertEquals(10_000, client.pingIntervalMillis)
    }
}
