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

    @Test
    fun `a socket with nothing to read is never timed out`() {
        assertEquals(0, client.readTimeoutMillis)
    }

    @Test
    fun `a dead socket is found by its ping`() {
        assertEquals(10_000, client.pingIntervalMillis)
    }
}
