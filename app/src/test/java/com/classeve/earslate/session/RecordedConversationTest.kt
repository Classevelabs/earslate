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
 * 2026-10-06 and 07, replayed through the app's own parser and engine at their
 * recorded timing.
 *
 * Each recording holds two sessions listening to one microphone, exactly as
 * the app runs them. The model was asked to stay silent when it heard its own
 * target language, and in these recordings it did not; nor did it always know
 * what language it was hearing. The assertions below measure that, and then
 * measure what reaches the listener all the same.
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

    /** What a session said that was nobody's translation, and how much of it was let through. */
    private fun Replay.repeats(): Pair<Int, Int> =
        emitted.filter { (at, leg, _) -> !belongs(at, leg) }.sumOf { it.third } to
            sink.played.filter { it.voiced && !belongs(it.atMs, it.leg) }.sumOf { it.ms }

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

    // The model names Norwegian "no"; the picker names it nb.
    @Test
    fun `Norwegian and English - the model's own name for the user's language is still theirs`() {
        val replay = replay("conversation-nb-en.jsonl")
        replay.assertNothingButTranslationIsHeard()
        replay.assertEveryUtteranceWasTranslated()
        assertEquals("the user's own language is never taken for the other person's", emptyList<String>(), replay.sink.heard)
    }

    // The model hears Malay as Indonesian, and then "translates" it into
    // Malay. No list says Indonesian is Malay: it is found out by what is said back.
    @Test
    fun `Malay and English - a language the model hears under another name is still the user's`() {
        val replay = replay("conversation-ms-en.jsonl")
        replay.assertNothingButTranslationIsHeard()
        replay.assertEveryUtteranceWasTranslated()
        assertEquals(emptyList<String>(), replay.sink.heard)
    }

    // The model names Filipino "tl". In this recording it did not repeat the
    // user's words, so what is measured is that every sentence was translated.
    @Test
    fun `Filipino and English - every sentence is translated for the other person`() {
        val replay = replay("conversation-fil-en.jsonl")
        replay.assertEveryUtteranceWasTranslated()
        val echoHeard = replay.sink.played.filter { it.voiced && !replay.belongs(it.atMs, it.leg) }.sumOf { it.ms }
        assertTrue("the user's own words played back: $echoHeard ms", echoHeard <= 500)
        assertEquals(emptyList<String>(), replay.sink.heard)
    }

    // A Punjabi speaker and an English speaker. The model wrote the Punjabi
    // down as Hindi, Gujarati and Japanese, and said five of its seven
    // sentences back in Punjabi. 0.6.0, replayed on this recording, translated
    // one of the seven for the other person, played five of them back to the
    // speaker, and twice took Hindi for the other person's language.
    @Test
    fun `Punjabi and English - every sentence is translated, whatever the model takes Punjabi for`() {
        val replay = replay("conversation-pa-en.jsonl")
        replay.assertEveryUtteranceWasTranslated()

        val (said, heard) = replay.repeats()
        assertTrue("the recording must contain the model's repeats for this to prove anything: $said ms", said >= 15_000)
        assertTrue("my own words that were played back to me: $heard of $said ms", heard <= 1_000)
        assertEquals("their language is English from start to finish", emptyList<String>(), replay.sink.heard)
    }

    // The same two people on another day, when the model knew Punjabi most of the time.
    @Test
    fun `Punjabi and English - and nothing is lost when the model does know it`() {
        val replay = replay("conversation-pa-en-2.jsonl")
        replay.assertEveryUtteranceWasTranslated()

        val (said, heard) = replay.repeats()
        assertTrue("my own words that were played back to me: $heard of $said ms", heard <= 1_000)
        assertEquals(emptyList<String>(), replay.sink.heard)
    }

    // English first, then somebody who speaks Chinese.
    @Test
    fun `Punjabi, English and then Chinese - their language becomes the one last spoken, from its first words`() {
        val replay = replay("conversation-pa-en-zh2.jsonl")
        assertEquals(listOf("zh"), replay.sink.heard)
        replay.assertEveryUtteranceWasTranslated()

        val chinese = replay.session.utterances.filter { it.language == "zh" }
        val alsoInEnglish = chinese.sumOf { replay.sink.heardMs(outbound, it.fromMs + 1_000, it.toMs + 4_500) }
        assertTrue("their Chinese was not also said in English: $alsoInEnglish ms", alsoInEnglish <= 500)
    }

    // Spanish with English words in it, and answers of two words.
    @Test
    fun `Spanish and English - mixed and short sentences are translated and nothing is said back`() {
        val replay = replay("conversation-es-en-mix.jsonl")
        replay.assertEveryUtteranceWasTranslated()
        val (said, heard) = replay.repeats()
        assertTrue("said back: $heard of $said ms", heard <= 500)
        assertEquals(emptyList<String>(), replay.sink.heard)
    }

    // Danish put into Norwegian shares two words in three with the Danish. It
    // is still a translation, and somebody who needs it must still hear it.
    @Test
    fun `Norwegian and Danish - a translation between two close languages is not taken for a repeat`() {
        val replay = replay("conversation-nb-da.jsonl")
        for (danish in replay.session.utterances.filter { it.language == "da" }) {
            val heard = replay.sink.heardMs(inbound, danish.fromMs + 1_000, danish.toMs + 4_500)
            assertTrue("${danish.clip}: $heard ms of Norwegian heard", heard >= 4_000)
        }
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

    // Somebody speaks Hindi, and later the Spanish speaker says two words
    // again. What I say next goes out in the language last heard: Spanish.
    @Test
    fun `their language follows whoever spoke last, and a single stray fragment moves nothing`() {
        val replay = replay("conversation-en-es.jsonl")
        assertEquals(listOf("hi", "es"), replay.sink.heard)
    }

    @Test
    fun `the speaking state follows the conversation instead of sticking`() {
        val replay = replay("conversation-en-es.jsonl")
        val changes = replay.sink.speaking
        assertTrue("went quiet and came back ${changes.size} times", changes.size >= 8)
        assertEquals("ends quiet", false, changes.last())
    }
}
