package com.classeve.earslate.testing

import com.classeve.earslate.audio.AudioCaptureEngine
import com.classeve.earslate.audio.AudioPlaybackEngine
import com.classeve.earslate.audio.PlaybackSnapshot
import com.classeve.earslate.bootstrap.BootstrapException
import com.classeve.earslate.bootstrap.SessionCredential
import com.classeve.earslate.bootstrap.SessionCredentialSource
import com.classeve.earslate.session.AudioFocus
import com.classeve.earslate.session.TranslationProvider
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** A microphone the test speaks into. */
class FakeCapture : AudioCaptureEngine {
    @Volatile private var onFrame: ((ByteArray) -> Unit)? = null
    @Volatile private var onError: (() -> Unit)? = null
    @Volatile private var onTaken: ((Boolean) -> Unit)? = null
    @Volatile var sampleRateHz = 0
    @Volatile var frameMs = 0
    @Volatile var refuses = false
    val starts = AtomicInteger()
    val stops = AtomicInteger()

    override fun start(
        sampleRateHz: Int,
        frameMs: Int,
        onFrame: (ByteArray) -> Unit,
        onError: () -> Unit,
        onTaken: (Boolean) -> Unit,
    ): Boolean {
        if (refuses) return false
        this.sampleRateHz = sampleRateHz
        this.frameMs = frameMs
        this.onFrame = onFrame
        this.onError = onError
        this.onTaken = onTaken
        starts.incrementAndGet()
        return true
    }

    override fun stop() {
        if (onFrame != null) stops.incrementAndGet()
        onFrame = null
    }

    val listening: Boolean get() = onFrame != null

    fun hear(frame: ByteArray) {
        onFrame?.invoke(frame)
    }

    fun breaks() {
        onError?.invoke()
    }

    /** A phone call takes the microphone, or gives it back. */
    fun taken(byACall: Boolean) {
        onTaken?.invoke(byACall)
    }
}

/** A loudspeaker that remembers what it was given, and reports what the test tells it to. */
class FakePlayback : AudioPlaybackEngine {
    class Written(val lane: Int, val ms: Int, val voiced: Boolean)

    val written = CopyOnWriteArrayList<Written>()
    val retired = CopyOnWriteArrayList<Int>()
    val muted = CopyOnWriteArrayList<Int>()
    val releases = AtomicInteger()
    val holds = AtomicInteger()
    val stops = AtomicInteger()
    @Volatile var running = false
    @Volatile var takingTurns = false
    @Volatile var waitingMs = 0
    @Volatile var audible = false

    override fun start() {
        running = true
    }

    override fun write(lane: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean, begins: Boolean) {
        written += Written(lane, pcm.size * 1000 / (sampleRateHz * 2), voiced)
    }

    override fun muteQueued(lane: Int) {
        muted += lane
    }

    override fun retire(lane: Int) {
        retired += lane
    }

    override fun setConsecutive(enabled: Boolean) {
        takingTurns = enabled
    }

    override fun release() {
        releases.incrementAndGet()
    }

    override fun hold() {
        holds.incrementAndGet()
    }

    override fun snapshot() = PlaybackSnapshot(running, 0, waitingMs, audible, 0, 0, 0)

    override fun stop(graceful: Boolean) {
        running = false
        stops.incrementAndGet()
    }

    fun heardMs(lane: Int): Int = written.filter { it.lane == lane && it.voiced }.sumOf { it.ms }
}

/** Hands out one credential until it is discarded, counting how often the provider was asked. */
class FakeCredentials(private val provider: TranslationProvider = TranslationProvider.GEMINI) : SessionCredentialSource {
    val minted = AtomicInteger()
    @Volatile var failure: BootstrapException? = null
    @Volatile private var held: SessionCredential? = null

    override suspend fun credential(preference: TranslationProvider?): SessionCredential {
        failure?.let { throw it }
        return held ?: SessionCredential(
            provider = provider,
            secret = "secret-${minted.incrementAndGet()}",
            webSocketUrl = "wss://example.invalid",
            model = "model",
            expiresAtMs = Long.MAX_VALUE,
        ).also { held = it }
    }

    override fun discard(credential: SessionCredential) {
        if (held === credential) held = null
    }
}

class FakeFocus : AudioFocus {
    val held = AtomicInteger()
    override fun acquire() { held.incrementAndGet() }
    override fun release() { held.decrementAndGet() }
}
