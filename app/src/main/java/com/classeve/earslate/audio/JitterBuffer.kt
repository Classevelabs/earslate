package com.classeve.earslate.audio

/**
 * Fixed-threshold startup jitter buffer. The startup threshold is supplied by
 * the caller (AndroidAudioPlaybackEngine uses 60 ms of audio at the active rate).
 *
 * Simpler than an adaptive jitter buffer: accumulate [startupBytes] worth of audio
 * before playback begins, then feed chunks in order. On underrun the buffer
 * resets and waits to refill. Good enough for v1 — the adaptive version lives in
 * task 11 polish.
 */
class JitterBuffer(
    private val startupBytes: Int,
) {
    private val queue = ArrayDeque<ByteArray>()
    private var accumulated = 0
    private var draining = false
    private val lock = Any()

    fun enqueue(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        synchronized(lock) {
            queue.addLast(chunk)
            accumulated += chunk.size
            if (!draining && accumulated >= startupBytes) {
                draining = true
            }
        }
    }

    /** Returns the next chunk to play, or null if we are still buffering / empty. */
    fun drain(): ByteArray? = synchronized(lock) {
        if (!draining) return null
        val next = queue.removeFirstOrNull()
        if (next == null) {
            // Underrun — reset and wait for the next fill
            draining = false
            accumulated = 0
            return null
        }
        accumulated -= next.size
        next
    }

    fun clear() = synchronized(lock) {
        queue.clear()
        accumulated = 0
        draining = false
    }

    val pendingBytes: Int get() = synchronized(lock) { accumulated }
}
