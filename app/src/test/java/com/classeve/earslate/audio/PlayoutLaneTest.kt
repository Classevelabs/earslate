package com.classeve.earslate.audio

import com.classeve.earslate.testing.RecordedSession
import com.classeve.earslate.testing.TestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lane is driven the way the phone drives it: a block arrives from the
 * network at its arrival time, and the audio thread pulls 20 ms every 20 ms.
 */
class PlayoutLaneTest {

    private val rate = 24_000
    private val frameMs = 20

    /** A lane plus the clock and the audio thread that drain it. */
    private inner class Player {
        val lane = PlayoutLane(rate)
        var now = 0L
        var voicedMs = 0
        var silentMs = 0
        var firstVoicedAtMs = -1L

        /** When each stretch of speech came out, as `[from, to)`. */
        val spoken = ArrayList<LongArray>()
        private val frame = ByteArray(TestAudio.bytesFor(frameMs, rate))

        fun arrive(ms: Int, voiced: Boolean = true) =
            lane.offer(if (voiced) TestAudio.tone(ms, rate) else TestAudio.silence(ms, rate), voiced, now)

        /** Play until [untilMs], with blocks arriving at the given times. */
        fun play(untilMs: Long, arrivals: Map<Long, List<Pair<Int, Boolean>>> = emptyMap()) {
            while (now < untilMs) {
                arrivals[now]?.forEach { (ms, voiced) -> arrive(ms, voiced) }
                if (lane.pull(frame, now)) {
                    voicedMs += frameMs
                    if (firstVoicedAtMs < 0) firstVoicedAtMs = now
                    val last = spoken.lastOrNull()
                    if (last != null && last[1] == now) last[1] = now + frameMs else spoken += longArrayOf(now, now + frameMs)
                } else {
                    silentMs += frameMs
                }
                now += frameMs
            }
        }
    }

    /**
     * Blocks of [blockMs] sent every [blockMs], each held up by the given
     * amount. They arrive in order, as they do on a socket: a block held up
     * holds up the ones behind it, and they land together.
     */
    private fun stream(
        count: Int,
        blockMs: Int = 250,
        voiced: (Int) -> Boolean = { true },
        lateMs: (Int) -> Int = { 0 },
    ): Map<Long, List<Pair<Int, Boolean>>> {
        var last = 0L
        return (0 until count).groupBy(
            keySelector = { i ->
                last = maxOf(last, (i.toLong() * blockMs + lateMs(i)) / frameMs * frameMs)
                last
            },
            valueTransform = { i -> blockMs to voiced(i) },
        )
    }

    @Test
    fun `speech starts after the start cushion and then never stops`() {
        val player = Player()
        player.play(untilMs = 6_000, arrivals = stream(20))

        assertEquals(PlayoutLane.START_CUSHION_MS.toLong(), player.firstVoicedAtMs)
        assertEquals("all five seconds came out", 5_000, player.voicedMs)
        assertEquals("in one piece, with no gap inside it", 1, player.spoken.size)
        assertEquals(0, player.lane.snapshot().underruns)
    }

    // The buffer this replaces waited for a second block before it would play the first.
    @Test
    fun `a sentence that starts inside a running stream is heard within the cushion`() {
        val player = Player()
        // Two seconds of the model's silence, then speech.
        player.play(untilMs = 6_000, arrivals = stream(20, voiced = { it >= 8 }))

        val speechArrivedAt = 8 * 250L
        val delay = player.firstVoicedAtMs - speechArrivedAt
        assertTrue("heard $delay ms after it arrived", delay in 0..PlayoutLane.START_CUSHION_MS)
        assertEquals(0, player.lane.snapshot().underruns)
    }

    @Test
    fun `blocks that arrive a little late are absorbed without a gap`() {
        val player = Player()
        player.play(untilMs = 8_000, arrivals = stream(24, lateMs = { if (it % 3 == 1) 120 else 0 }))

        assertEquals(1, player.spoken.size)
        assertEquals(0, player.lane.snapshot().underruns)
    }

    @Test
    fun `a block too late to play costs one short gap, and the cushion grows to cover the next`() {
        val player = Player()
        // A stream that carries its own silence, as Gemini's does. The sixth
        // block is 300 ms late: later than the cushion can hide.
        player.play(untilMs = 12_000, arrivals = stream(41, voiced = { it > 0 }, lateMs = { if (it == 5 || it == 21) 300 else 0 }))

        val snapshot = player.lane.snapshot()
        assertEquals("the second late block was absorbed", 1, snapshot.underruns)
        assertTrue("cushion grew: ${snapshot.cushionMs}", snapshot.cushionMs > PlayoutLane.START_CUSHION_MS)
        // Counted in 20 ms frames, so the frame the speech starts in and the one it ends in round up.
        assertTrue("nothing was lost: ${player.voicedMs} ms", player.voicedMs in 10_000..10_040)
        assertEquals("exactly one gap", 2, player.spoken.size)
        val gap = player.spoken[1][0] - player.spoken[0][1]
        assertTrue("the gap was $gap ms", gap in 100..260)
    }

    // Gemini opens every stream with two or three blocks at once.
    @Test
    fun `a stream that opens with a burst is heard at once`() {
        val player = Player()
        player.arrive(250)
        player.arrive(250)
        player.play(untilMs = 1_000, arrivals = mapOf(260L to listOf(250 to true), 500L to listOf(250 to true)))

        assertEquals("no wait for a cushion that is already in hand", 0L, player.firstVoicedAtMs)
        assertEquals(1, player.spoken.size)
    }

    // Giving back the surplus a burst left behind used to be charged to the
    // cushion, which never held it: it read 60 ms with 300 ms queued.
    @Test
    fun `the cushion is not paid down for delay it never caused`() {
        val player = Player()
        // Three blocks at once, then a minute of a perfect stream that is mostly silence.
        player.arrive(250)
        player.arrive(250)
        player.play(untilMs = 60_000, arrivals = stream(240, voiced = { it < 6 || it % 40 < 4 }))

        val snapshot = player.lane.snapshot()
        assertEquals(0, snapshot.underruns)
        assertEquals("nothing was late, so nothing was learnt", PlayoutLane.START_CUSHION_MS, snapshot.cushionMs)
    }

    // OpenAI may send nothing at all between words. A gap is then a pause in
    // the speech, and treating each one as a late block only added delay.
    @Test
    fun `a provider that sends no silence is not taken for a late network when the speaker pauses`() {
        val player = Player()
        // Three seconds of speech with a 400 ms pause after every second block.
        val arrivals = (0 until 12).associate { i -> (i * 250L + (i / 2) * 400L) / frameMs * frameMs to listOf(250 to true) }
        player.play(untilMs = 8_000, arrivals = arrivals)

        val snapshot = player.lane.snapshot()
        assertEquals(0, snapshot.underruns)
        assertEquals(PlayoutLane.START_CUSHION_MS, snapshot.cushionMs)
        assertEquals("every word came out", 3_000, player.voicedMs)
    }

    @Test
    fun `a cushion grown by a late block is handed back at a later pause`() {
        val player = Player()
        // A stream with its own silence; one block 400 ms late.
        player.play(untilMs = 3_000, arrivals = stream(10, voiced = { it > 0 }, lateMs = { if (it == 4) 400 else 0 }))
        val grown = player.lane.snapshot().cushionMs
        assertTrue("grew to $grown ms", grown > 400)

        // Nothing arrives for twenty seconds, then a new sentence.
        player.play(untilMs = 23_000)
        player.arrive(250)
        val settled = player.lane.snapshot().cushionMs
        assertEquals(PlayoutLane.START_CUSHION_MS + (grown - PlayoutLane.START_CUSHION_MS) / 2, settled)
    }

    @Test
    fun `a cushion is not handed back while blocks are still arriving late`() {
        val player = Player()
        player.play(untilMs = 3_000, arrivals = stream(10, voiced = { it > 0 }, lateMs = { if (it == 4) 400 else 0 }))
        val grown = player.lane.snapshot().cushionMs

        // Only two seconds later: too soon to call the network steady.
        player.play(untilMs = 5_000)
        player.arrive(250)
        assertEquals(grown, player.lane.snapshot().cushionMs)
    }

    // A block of varying length may end half way through a sample.
    @Test
    fun `half a sample at the end of one block is joined to the start of the next`() {
        val lane = PlayoutLane(rate)
        lane.offer(byteArrayOf(1, 2, 3), voiced = true, nowMs = 0)
        lane.offer(byteArrayOf(4, 5, 6), voiced = true, nowMs = 0)
        lane.offer(ByteArray(2_000) { 9 }, voiced = true, nowMs = 0)

        val out = ByteArray(8)
        assertTrue(lane.pull(out, nowMs = 500))
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6, 9, 9), out.toList())
    }

    // A provider that sends nothing while it has nothing to say.
    @Test
    fun `a pause between sentences is not counted as the network running late`() {
        val player = Player()
        val second = stream(8).mapKeys { it.key + 4_000 }
        player.play(untilMs = 8_000, arrivals = stream(8) + second)

        val snapshot = player.lane.snapshot()
        assertEquals(0, snapshot.underruns)
        assertEquals("the cushion did not grow", PlayoutLane.START_CUSHION_MS, snapshot.cushionMs)
        assertEquals("both sentences came out whole", 4_000, player.voicedMs)
        assertEquals(2, player.spoken.size)
        assertEquals(4_000L + PlayoutLane.START_CUSHION_MS, player.spoken[1][0])
    }

    @Test
    fun `the cushion never grows past its ceiling`() {
        val player = Player()
        player.play(untilMs = 60_000, arrivals = stream(200, voiced = { it > 0 }, lateMs = { if (it % 4 == 3) 550 else 0 }))
        assertTrue("it did grow", player.lane.snapshot().cushionMs > PlayoutLane.START_CUSHION_MS)
        assertTrue(player.lane.snapshot().cushionMs <= PlayoutLane.MAX_CUSHION_MS)
    }

    @Test
    fun `delay bought by a bad moment is given back, by skipping silence, once the network is steady`() {
        val player = Player()
        // One very late block early on, then two minutes of a perfect stream that is mostly silence.
        player.play(
            untilMs = 120_000,
            arrivals = stream(470, voiced = { it in 1..8 || (it >= 40 && it % 40 < 4) }, lateMs = { if (it == 4) 420 else 0 }),
        )
        val snapshot = player.lane.snapshot()
        assertEquals("the bad moment happened", 1, snapshot.underruns)
        assertTrue("cushion settled back to ${snapshot.cushionMs} ms", snapshot.cushionMs <= PlayoutLane.START_CUSHION_MS)
        assertTrue("and never below the floor", snapshot.cushionMs >= PlayoutLane.MIN_CUSHION_MS)
        assertEquals("speech is never what gets skipped", 0, snapshot.droppedMs)
    }

    @Test
    fun `a burst after a stall is caught up by shortening pauses, not by dropping speech`() {
        val player = Player()
        // Three seconds arrive at once: one of speech, then two of silence.
        player.arrive(250, voiced = false)
        player.play(untilMs = 1_000)
        repeat(4) { player.arrive(250, voiced = true) }
        repeat(8) { player.arrive(250, voiced = false) }
        player.play(untilMs = 4_000)

        assertEquals("the whole second of speech was heard", 1_000, player.voicedMs)
        val snapshot = player.lane.snapshot()
        assertEquals(0, snapshot.droppedMs)
        assertTrue("no longer seconds behind: ${snapshot.queuedMs} ms queued", snapshot.queuedMs < 400)
    }

    @Test
    fun `speech queued by a wrong guess is silenced without disturbing the timing`() {
        val player = Player()
        player.play(untilMs = 400, arrivals = stream(2))
        val heardBefore = player.voicedMs
        repeat(4) { player.arrive(250) }
        val queued = player.lane.snapshot().queuedMs
        player.lane.muteQueued()

        assertEquals(0, player.lane.snapshot().queuedVoicedMs)
        assertEquals("the lane is exactly as long as before", queued, player.lane.snapshot().queuedMs)
        player.play(untilMs = 3_000)
        assertEquals(heardBefore, player.voicedMs)
    }

    // A translation waiting for its turn on the loudspeaker, and behind it the
    // first words of something that turns out to be a repeat. Only the repeat goes.
    @Test
    fun `silencing what is queued of one utterance leaves the sentence waiting ahead of it`() {
        val player = Player()
        player.lane.setConsecutive(true, player.now)
        player.lane.offer(TestAudio.tone(250, rate), voiced = true, nowMs = player.now, begins = true)
        repeat(3) { player.arrive(250) }
        player.lane.offer(TestAudio.tone(250, rate), voiced = true, nowMs = player.now, begins = true)
        player.arrive(250)
        assertEquals(1_500, player.lane.snapshot().queuedVoicedMs)

        player.lane.muteQueued()
        assertEquals("the second of translation is still there", 1_000, player.lane.snapshot().queuedVoicedMs)
        player.lane.release(player.now)
        player.play(untilMs = 3_000)
        assertEquals("and is heard whole", 1_000, player.voicedMs)
    }

    // ── a loudspeaker both people share ─────────────────────────────────

    @Test
    fun `held speech is not played until it is released, then comes out whole`() {
        val player = Player()
        player.lane.setConsecutive(true, 0)
        // Six seconds of translation arrive while the person is still talking.
        player.play(untilMs = 6_000, arrivals = stream(24, voiced = { it in 4..19 }))
        assertEquals("nothing is spoken over the person talking", 0, player.voicedMs)
        assertEquals(4_000, player.lane.snapshot().queuedVoicedMs)

        player.lane.release(player.now)
        player.play(untilMs = 11_000)
        assertEquals(4_000, player.voicedMs)
        assertEquals("in one piece", 1, player.spoken.size)
    }

    @Test
    fun `the silence around held speech is cut to a short pause`() {
        val player = Player()
        player.lane.setConsecutive(true, 0)
        player.arrive(250)
        repeat(12) { player.arrive(250, voiced = false) }
        player.arrive(250)

        assertEquals(500, player.lane.snapshot().queuedVoicedMs)
        assertEquals(500 + PlayoutLane.HELD_PAUSE_MS, player.lane.snapshot().queuedMs)
    }

    // The first block to arrive after the release used to delete every pause
    // between the sentences that had been held.
    @Test
    fun `the pauses between held sentences survive what arrives after the release`() {
        val player = Player()
        player.lane.setConsecutive(true, 0)
        // Two sentences with seconds of the model's silence between them.
        player.play(untilMs = 7_000, arrivals = stream(28, voiced = { it in 0..7 || it in 20..27 }))
        player.lane.release(player.now)
        // The stream goes on arriving, silent, while they play.
        player.play(untilMs = 13_000, arrivals = stream(52, voiced = { false }).filterKeys { it >= 7_000 })

        assertEquals(4_000, player.voicedMs)
        assertEquals("two sentences, not one run-on", 2, player.spoken.size)
        assertEquals(PlayoutLane.HELD_PAUSE_MS.toLong(), player.spoken[1][0] - player.spoken[0][1])
    }

    @Test
    fun `the tail of a translation that is still arriving plays on behind what was held`() {
        val player = Player()
        player.lane.setConsecutive(true, 0)
        player.play(untilMs = 2_000, arrivals = stream(8))
        player.lane.release(player.now)
        // The rest keeps arriving at real time while the first part plays.
        player.play(untilMs = 9_000, arrivals = stream(16).filterKeys { it >= 2_000 })

        assertEquals(4_000, player.voicedMs)
        assertEquals(1, player.spoken.size)
        assertEquals(0, player.lane.snapshot().underruns)
    }

    @Test
    fun `going back to holding keeps what arrives next for the next release`() {
        val player = Player()
        player.lane.setConsecutive(true, 0)
        player.arrive(500)
        player.lane.release(0)
        player.play(untilMs = 1_500)
        player.lane.hold()
        val heard = player.voicedMs
        player.arrive(500)
        player.play(untilMs = 3_000)

        assertEquals(500, heard)
        assertEquals("the second sentence waits", heard, player.voicedMs)
        assertTrue(player.lane.snapshot().held)
    }

    @Test
    fun `earbuds going in mid-sentence lets held speech out at once`() {
        val player = Player()
        player.lane.setConsecutive(true, 0)
        player.arrive(1_000)
        player.lane.setConsecutive(false, 0)
        player.play(untilMs = 2_000)

        assertEquals(1_000, player.voicedMs)
        assertFalse(player.lane.snapshot().held)
    }

    // ── the real thing ──────────────────────────────────────────────────

    @Test
    fun `a recorded 50-second session plays with no gap inside any sentence`() {
        val session = RecordedSession.load("conversation-en-es.jsonl")
        val player = Player()
        val arrivals = session.frames.filter { it.leg == "en" && it.json.contains("inlineData") }
        var next = 0
        val frame = ByteArray(TestAudio.bytesFor(frameMs, rate))
        var voicedIn = 0
        var voicedOut = 0
        while (player.now < session.endMs + 2_000) {
            while (next < arrivals.size && arrivals[next].atMs <= player.now) {
                val peak = Regex("\"data\":\"([A-Za-z0-9+/=]+)\"").find(arrivals[next].json)!!.groupValues[1]
                val pcm = java.util.Base64.getDecoder().decode(peak)
                val voiced = !Pcm.isSilent(pcm)
                if (voiced) voicedIn += 250
                player.lane.offer(pcm, voiced, player.now)
                next++
            }
            if (player.lane.pull(frame, player.now)) voicedOut += frameMs
            player.now += frameMs
        }
        val snapshot = player.lane.snapshot()
        assertEquals("the real arrival pattern never ran the lane dry", 0, snapshot.underruns)
        assertEquals(0, snapshot.droppedMs)
        // Counted in 20 ms frames, so each sentence's first and last frame round up.
        assertTrue("speech in $voicedIn ms, out $voicedOut ms", voicedOut in voicedIn..voicedIn + 200)
    }
}
