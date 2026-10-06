package com.classeve.earslate.session

import com.classeve.earslate.audio.Pcm
import com.classeve.earslate.live.LiveEvent
import com.classeve.earslate.live.TranslationLiveProtocols
import com.classeve.earslate.testing.RecordedSession
import com.classeve.earslate.testing.RecordingSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whole conversations, as Gemini Live Translate actually answered them on
 * 2026-10-06, replayed through the app's own parser and engine at their
 * recorded timing.
 *
 * Each recording holds two sessions listening to one microphone, exactly as
 * the app runs them. The model was asked to stay silent when it heard its own
 * target language, and in these recordings it did not: the assertions below
 * measure that, and then measure that none of it reaches the listener.
 */
class RecordedConversationTest {

    private val inbound = 1
    private val outbound = 2
    private val gemini = TranslationLiveProtocols.forProvider(TranslationProvider.GEMINI)

    private class Replay(
        val session: RecordedSession,
        val sink: RecordingSink,
        /** Speech each leg sent, whether or not it was allowed out: `(arrival, leg, ms)`. */
        val emitted: List<Triple<Long, Int, Int>>,
    )

    private fun replay(name: String): Replay {
        val session = RecordedSession.load(name)
        val sink = RecordingSink()
        val engine = ConversationEngine(session.mine, session.theirs, followsTheirLanguage = true, sink = sink)
        engine.legOpened(inbound, LegRole.INBOUND)
        engine.legOpened(outbound, LegRole.OUTBOUND)
        val emitted = ArrayList<Triple<Long, Int, Int>>()

        var clock = 0L
        fun tickUntil(at: Long) {
            while (clock + 50 <= at) {
                clock += 50
                sink.nowMs = clock
                engine.onTick(clock)
            }
        }
        for (frame in session.frames) {
            tickUntil(frame.atMs)
            sink.nowMs = frame.atMs
            val leg = if (frame.leg == session.mine) inbound else outbound
            for (event in gemini.parse(frame.json)) {
                if (event is LiveEvent.AudioChunk && !Pcm.isSilent(event.pcm)) {
                    emitted += Triple(frame.atMs, leg, event.pcm.size * 1000 / (event.sampleRateHz * 2))
                }
                engine.onEvent(leg, event, frame.atMs)
            }
        }
        tickUntil(session.endMs + 3_000)
        return Replay(session, sink, emitted)
    }

    /**
     * The leg that should be heard for [utterance]: the one that does NOT speak
     * the language that was just spoken.
     */
    private fun Replay.wanted(utterance: RecordedSession.Utterance): Int =
        if (utterance.language == session.mine) outbound else inbound

    /**
     * Whether speech from [leg] arriving at [atMs] can be the translation of
     * something that leg was meant to translate. The model answers between
     * about one and four seconds behind the speaker.
     */
    private fun Replay.belongs(atMs: Long, leg: Int): Boolean = session.utterances.any {
        wanted(it) == leg && atMs >= it.fromMs + 1_000 && atMs <= it.toMs + 4_500
    }

    private fun Replay.assertNothingButTranslationIsHeard() {
        val echoSent = emitted.filter { (at, leg, _) -> !belongs(at, leg) }.sumOf { it.third }
        val echoHeard = sink.played.filter { it.voiced && !belongs(it.atMs, it.leg) }.sumOf { it.ms }
        val wantedSent = emitted.filter { (at, leg, _) -> belongs(at, leg) }.sumOf { it.third }
        val wantedHeard = sink.played.filter { it.voiced && belongs(it.atMs, it.leg) }.sumOf { it.ms }

        assertTrue(
            "the recording must contain the model's echo for this to prove anything: $echoSent ms",
            echoSent >= 2_000,
        )
        assertTrue("echo that reached the listener: $echoHeard of $echoSent ms", echoHeard <= 500)
        assertTrue(
            "translation that reached the listener: $wantedHeard of $wantedSent ms",
            wantedHeard >= wantedSent * 95 / 100,
        )
    }

    private fun Replay.assertEveryUtteranceWasTranslated() {
        for (utterance in session.utterances) {
            val leg = wanted(utterance)
            val heard = sink.heardMs(leg, utterance.fromMs + 1_000, utterance.toMs + 4_500)
            val spoken = utterance.toMs - utterance.fromMs
            assertTrue(
                "${utterance.clip}: $heard ms of translation for $spoken ms of speech",
                heard >= minOf(spoken / 2, 2_000),
            )
        }
    }

    @Test
    fun `English and Spanish - only translations are heard, and all of them`() {
        val replay = replay("conversation-en-es.jsonl")
        replay.assertNothingButTranslationIsHeard()
        replay.assertEveryUtteranceWasTranslated()
    }

    @Test
    fun `Hindi and English - only translations are heard, and all of them`() {
        val replay = replay("conversation-hi-en.jsonl")
        replay.assertNothingButTranslationIsHeard()
        replay.assertEveryUtteranceWasTranslated()
    }

    // The app waited for the model to say a sentence had ended. It never does.
    @Test
    fun `captions are committed line by line, in the language each listener reads`() {
        val replay = replay("conversation-en-es.jsonl")
        val lines = replay.sink.lines

        assertTrue("lines committed: ${lines.size}", lines.size >= replay.session.utterances.size - 1)
        assertEquals("nothing is left half-written at the end", "", replay.sink.pending(inbound) + replay.sink.pending(outbound))
        assertTrue("no line runs on for the whole conversation", lines.all { it.second.length < 220 })

        val english = lines.filter { it.first == inbound }.joinToString(" ") { it.second }
        val spanish = lines.filter { it.first == outbound }.joinToString(" ") { it.second }
        assertTrue(english, english.contains("train station"))
        assertTrue(english, english.contains("restaurant"))
        assertTrue(spanish, spanish.contains("minutos"))
        assertTrue(spanish, spanish.contains("entrada"))
        // My own English must never appear as a caption of "their" speech.
        assertTrue(english, !english.contains("ten minutes") && !english.contains("10 minutes"))
    }

    @Test
    fun `a third language entering the conversation is followed, and a single stray fragment is not`() {
        val replay = replay("conversation-en-es.jsonl")
        assertEquals(listOf("hi"), replay.sink.heard)
    }

    @Test
    fun `the speaking state follows the conversation instead of sticking`() {
        val replay = replay("conversation-en-es.jsonl")
        val changes = replay.sink.speaking
        assertTrue("went quiet and came back ${changes.size} times", changes.size >= 8)
        assertEquals("ends quiet", false, changes.last())
    }
}
