package com.classeve.earslate.session

import com.classeve.earslate.live.LiveEvent
import com.classeve.earslate.testing.RecordingSink
import com.classeve.earslate.testing.TestAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unless a test says otherwise: I speak English, they speak Spanish. The
 * inbound leg speaks English for them, the outbound leg speaks Spanish for me,
 * and both hear everything.
 *
 * The Punjabi, Hindi, Vietnamese and Japanese in here are what Gemini Live
 * Translate wrote down, on 2026-10-06 and 07, for a Punjabi speaker's own
 * sentences.
 */
class ConversationEngineTest {

    private val inbound = 1
    private val outbound = 2

    /** @param twoWay false while nobody has been heard: there is no direction for me yet. */
    private inner class Scene(
        follows: Boolean = true,
        theirs: String? = "es",
        mine: String = "en",
        twoWay: Boolean = theirs != null,
    ) {
        val sink = RecordingSink()
        val engine = ConversationEngine(mine, theirs, follows, sink)
        var now = 0L
        private val open = if (twoWay) listOf(inbound, outbound) else listOf(inbound)

        init {
            engine.legOpened(inbound, LegRole.INBOUND)
            if (twoWay) engine.legOpened(outbound, LegRole.OUTBOUND)
        }

        /** Let [ms] pass, ticking as the session does. */
        fun pass(ms: Int) {
            repeat(ms / 50) {
                now += 50
                sink.nowMs = now
                engine.onTick(now)
            }
        }

        /** Both legs hear the same microphone, so by default both report. */
        fun heard(text: String, language: String?, leg: Int? = null) {
            for (id in listOfNotNull(leg).ifEmpty { open }) {
                engine.onEvent(id, LiveEvent.SourceTranscript(text, language), now)
            }
        }

        /** [blocks] quarter-seconds of speech from [leg], arriving in real time. */
        fun speaks(leg: Int, blocks: Int = 4) = repeat(blocks) {
            engine.onEvent(leg, LiveEvent.AudioChunk(TestAudio.tone(250), 24_000), now)
            pass(250)
        }

        fun quiet(leg: Int, blocks: Int = 4) = repeat(blocks) {
            engine.onEvent(leg, LiveEvent.AudioChunk(TestAudio.silence(250), 24_000), now)
            pass(250)
        }

        fun caption(leg: Int, text: String) = engine.onEvent(leg, LiveEvent.CaptionDelta(text), now)
    }

    // ── never say again what was just said ──────────────────────────────

    @Test
    fun `when they speak, their translation is heard and the echo in their own language is not`() {
        val s = Scene()
        s.heard("Hola, buenos días", "es")
        s.speaks(inbound, 6)
        s.speaks(outbound, 6)

        assertEquals(1_500, s.sink.heardMs(inbound))
        assertEquals("the model repeating Spanish back", 0, s.sink.heardMs(outbound))
        assertEquals("the echo still occupies its lane, as silence", 12, s.sink.played.size)
    }

    @Test
    fun `when I speak, my translation is heard and my own words are not repeated to me`() {
        val s = Scene()
        s.heard("Sure, it is about ten minutes", "en")
        s.speaks(outbound, 6)
        s.speaks(inbound, 6)

        assertEquals(1_500, s.sink.heardMs(outbound))
        assertEquals(0, s.sink.heardMs(inbound))
    }

    // The model reports Norwegian as "no"; the user's language is saved as nb-NO.
    @Test
    fun `my language under the model's own name for it is still my language`() {
        val s = Scene(mine = "nb-NO", theirs = "en")
        s.heard("God dag, kan du si meg", "no")
        s.speaks(inbound, 4)
        s.speaks(outbound, 4)

        assertEquals("my own Norwegian repeated back to me", 0, s.sink.heardMs(inbound))
        assertEquals("my words in English, for them", 1_000, s.sink.heardMs(outbound))
        assertTrue("my language is not taken for theirs: ${s.sink.heard}", s.sink.heard.isEmpty())
    }

    @Test
    fun `the model repeating its last verdict with no words does not change who is speaking`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.heard("", "en")
        s.heard("   ", "en")
        assertEquals(Speaker.THEM, s.engine.hears(inbound))
        assertEquals(Speaker.THEM, s.engine.hears(outbound))
    }

    // ── a language's name is believed only from the session that speaks it ──

    // Recorded: a Punjabi sentence, written down as Japanese by the session
    // aimed at English and as Hindi by the one aimed at Punjabi. Its English
    // translation was right, and used to be thrown away as "not my language".
    @Test
    fun `my words go out whatever the model takes my language for`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("हां जी। ये यहां से लगभग 10 मिनट की दूरी पर है।", "hi", leg = inbound)
        s.heard("ハンジえっと10分ぐらいの距離です。", "ja", leg = outbound)
        s.caption(outbound, "Hanji, uh, it's about a 10-minute walk from here.")
        s.speaks(outbound, 6)

        assertEquals(1_500, s.sink.heardMs(outbound))
        assertEquals("Hanji, uh, it's about a 10-minute walk from here.", s.sink.pending(outbound))
        assertEquals("nobody is known to be speaking", Speaker.UNKNOWN, s.engine.hears(outbound))
    }

    // Recorded: the session aimed at Punjabi wrote a Punjabi sentence down as
    // an English one, and called it English. It is not the one that knows English.
    @Test
    fun `the session that speaks my language is not believed when it names theirs`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("It's about 10 minutes from here. Go straight down this street.", "en", leg = inbound)
        s.heard("Hả? Ừ, tôi khoảng 10 phút đi tới thôi.", "vi", leg = outbound)
        s.caption(outbound, "Yeah, it's about 10 minutes away.")
        s.speaks(outbound, 4)

        assertEquals("what I said still goes out", 1_000, s.sink.heardMs(outbound))
        assertTrue(s.sink.heard.isEmpty())
    }

    @Test
    fun `a language neither of us is known to speak silences nobody`() {
        val s = Scene(follows = false)
        s.heard("नमस्ते", "hi")
        s.speaks(inbound, 4)
        s.speaks(outbound, 4)

        assertEquals("it may be them", 1_000, s.sink.heardMs(inbound))
        assertEquals("and it may be me, misheard", 1_000, s.sink.heardMs(outbound))
    }

    // They spoke; seconds later I answer, and only one session says what it heard.
    @Test
    fun `a session that has said nothing about the present speech is not still believed about the last`() {
        val s = Scene()
        s.heard("Muchas gracias", "es")
        s.pass(3_000)
        s.heard("You are welcome", "en", leg = inbound)
        s.speaks(outbound, 4)

        assertEquals("my reply is translated", 1_000, s.sink.heardMs(outbound))
    }

    // Recorded: one leg heard "नमस्ते" as Hindi, the other wrote it "Namaste" and called it English.
    @Test
    fun `when the two sessions disagree, neither is silenced on the other's word`() {
        val s = Scene()
        s.heard("Sí, hay uno muy bueno", "es")
        s.speaks(inbound, 2)
        // I begin to answer. The outbound leg hears English; the inbound leg mishears it as Spanish.
        s.heard("Okay", "en", leg = outbound)
        s.heard("Oké", "es", leg = inbound)
        s.speaks(outbound, 4)

        assertEquals("the leg that heard me translates me, whatever the other one thought", 1_000, s.sink.heardMs(outbound))
    }

    // ── the words themselves ────────────────────────────────────────────

    // Recorded: "ਹਾਂ ਜੀ, ਠੀਕ ਹੈ", heard as Hindi by both sessions. The one aimed
    // at Punjabi then "translated" it into Punjabi: my own words, back to me.
    @Test
    fun `my own words said back to me under another language's name are not heard, and my translation is`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("हां जी। ठीक है।", "hi")
        s.caption(inbound, "ਹਾਂ ਜੀ। ਠੀਕ ਹੈ।")
        s.caption(outbound, "Yes. Okay.")
        s.speaks(inbound, 6)
        s.speaks(outbound, 6)

        assertEquals("my own words", 0, s.sink.heardMs(inbound))
        assertEquals("", s.sink.pending(inbound))
        assertEquals("what they are in English", 1_500, s.sink.heardMs(outbound))
        assertEquals("Yes. Okay.", s.sink.pending(outbound))
        assertTrue("Hindi is nobody's language here: ${s.sink.heard}", s.sink.heard.isEmpty())
        assertEquals("en", s.engine.theirLanguage)
    }

    @Test
    fun `a repeat found out after it began to speak is cut off, and its caption thrown away`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("मेरा फोन चार्ज नहीं है।", "hi")
        s.caption(inbound, "ਮੇ")
        s.speaks(inbound, 2)
        assertEquals("half a word is no evidence", 500, s.sink.heardMs(inbound))
        assertEquals("ਮੇ", s.sink.pending(inbound))

        s.caption(inbound, "ਰਾ ਫੋਨ ਚਾਰਜ ਨਹੀਂ ਹੈ।")
        assertEquals(listOf(inbound), s.sink.mutedQueued)
        assertEquals(listOf(inbound), s.sink.discarded)
        s.speaks(inbound, 4)
        assertEquals("and nothing more of it is heard", 500, s.sink.heardMs(inbound))

        s.quiet(inbound, 3)
        assertTrue("nor written", s.sink.lines.isEmpty())
    }

    // Recorded: "God dag, kan du si meg…" in Norwegian, and its Swedish
    // translation, which begins with the same three words.
    @Test
    fun `a translation that begins with the words it heard is held back only until it differs, then shown whole`() {
        val s = Scene(mine = "sv-SE", theirs = "nb-NO")
        s.heard("God dag, kan du si meg hvor nærmeste togstasjon er?", "no")
        s.caption(inbound, "God dag, kan ")
        s.speaks(inbound, 2)
        assertEquals("those words were just said", 0, s.sink.heardMs(inbound))
        assertEquals("", s.sink.pending(inbound))

        s.caption(inbound, "du säga var närmaste tågstation ligger?")
        assertEquals("God dag, kan du säga var närmaste tågstation ligger?", s.sink.pending(inbound))
        s.speaks(inbound, 4)
        assertEquals(1_000, s.sink.heardMs(inbound))

        s.quiet(inbound, 3)
        assertEquals(listOf(inbound to "God dag, kan du säga var närmaste tågstation ligger?"), s.sink.lines)
    }

    // Recorded: a whole Punjabi sentence, written down in Hindi's own words, so
    // that only half of what came back matched it. The session aimed at English
    // was translating the same speech, and shared nothing with what it heard.
    @Test
    fun `beside a direction that is plainly translating, the one saying mostly what it heard is the repeat`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("हां जी। ये यहां से लगभग 10 मिनट की दूरी पर है।", "hi", leg = inbound)
        s.heard("ハンジえっと10分ぐらいの距離です。", "ja", leg = outbound)
        s.caption(inbound, "ਹਾਂ ਜੀ। ਇਹ ਇੱਥੋਂ ਲਗਭਗ 10 ਮਿੰਟ ਦੀ ਦੂਰੀ 'ਤੇ ਹੈ। ")
        s.speaks(inbound, 1)
        assertEquals("on its own it could be a translation", 250, s.sink.heardMs(inbound))

        s.caption(outbound, "Hanji, uh, it's about a 10-minute walk from here. ")
        s.speaks(outbound, 1)
        s.speaks(inbound, 4)
        s.speaks(outbound, 4)

        assertEquals("my own words are not said back to me", 250, s.sink.heardMs(inbound))
        assertEquals(listOf(inbound), s.sink.mutedQueued)
        assertEquals("", s.sink.pending(inbound))
        assertEquals("and my translation is heard whole", 1_250, s.sink.heardMs(outbound))
        assertTrue("Hindi is nobody's language: ${s.sink.heard}", s.sink.heard.isEmpty())
    }

    // My own long translation was still being spoken when somebody answered
    // in Hindi, whose Punjabi shares half its words with it.
    @Test
    fun `a direction is not outdone by one still finishing what was said before`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ਜੀ। ਤੁਹਾਡਾ ਕੀ ਹਾਲ ਹੈ?", "pa")
        s.caption(outbound, "Hello. How are you? I called you yesterday ")
        s.speaks(outbound, 12)
        s.heard(" जरूर। यह यहां से लगभग 10 मिनट की दूरी पर है।", "hi")
        s.caption(inbound, "ਜ਼ਰੂਰ। ਇਹ ਇੱਥੋਂ ਲਗਭਗ 10 ਮਿੰਟ ਦੀ ਦੂਰੀ 'ਤੇ ਹੈ। ")
        s.speaks(inbound, 4)

        assertEquals("what they said is translated for me", 1_000, s.sink.heardMs(inbound))
    }

    // While a connection is being replaced there are two for one direction:
    // the old one finishing its sentence, the new one starting the next.
    @Test
    fun `a direction is not outdone by the connection it is replacing`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        val replacement = 3
        s.engine.legOpened(replacement, LegRole.INBOUND)
        s.heard("Sure. It is about ten minutes from here.", "en", leg = inbound)
        s.caption(inbound, "ਹਾਂਜੀ। ਇਹ ਲਗਭਗ ਦਸ ਮਿੰਟ ਦੂਰ ਹੈ। ")
        s.speaks(inbound, 2)
        s.heard("जरूर। यह यहां से लगभग 10 मिनट की दूरी पर है।", "hi", leg = replacement)
        s.caption(replacement, "ਜ਼ਰੂਰ। ਇਹ ਇੱਥੋਂ ਲਗਭਗ 10 ਮਿੰਟ ਦੀ ਦੂਰੀ 'ਤੇ ਹੈ। ")
        s.speaks(replacement, 4)

        assertEquals("the new connection's translation is heard", 1_000, s.sink.heardMs(replacement))
    }

    // I talk on for ten seconds. My translation began long before the other
    // direction started to say my words back, and both answer the same speech.
    @Test
    fun `a repeat that starts late in a long sentence is still outdone by its translation`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ਜੀ। ਤੁਹਾਡਾ ਕੀ ਹਾਲ ਹੈ?", "pa")
        s.caption(outbound, "Hello. How are you? ")
        s.speaks(outbound, 6)
        s.heard(" ये यहां से लगभग 10 मिनट की दूरी पर है।", "pa", leg = outbound)
        s.heard(" ये यहां से लगभग 10 मिनट की दूरी पर है।", "hi", leg = inbound)
        s.speaks(outbound, 6)
        s.caption(outbound, "It is about ten minutes from here. ")
        s.caption(inbound, "ਇਹ ਇੱਥੋਂ ਲਗਭਗ 10 ਮਿੰਟ ਦੀ ਦੂਰੀ 'ਤੇ ਹੈ। ")
        s.speaks(inbound, 4)
        s.speaks(outbound, 2)

        assertEquals("my own words are not said back to me", 0, s.sink.heardMs(inbound))
        assertEquals("and all of my translation is heard", 3_500, s.sink.heardMs(outbound))
    }

    // Recorded: Norwegian said back with one word in eight respelled.
    @Test
    fun `a repeat is still a repeat when a word in it is respelled`() {
        val s = Scene(mine = "nb-NO", theirs = "en")
        s.heard("God dag, kan du si meg hvor nærmeste togstasjon er?", "da")
        s.caption(inbound, "God dag, kan du ")
        s.speaks(inbound, 1)
        s.caption(inbound, "si meg hvor neste ")
        s.speaks(inbound, 3)

        assertEquals(0, s.sink.heardMs(inbound))
        assertEquals("", s.sink.pending(inbound))
    }

    // A third person, speaking a language that is neither of ours: both directions really are translating.
    @Test
    fun `when both directions are plainly translating, neither is taken for a repeat`() {
        val s = Scene(follows = false)
        s.heard("नमस्ते, क्या आप मुझे बता सकते हैं", "hi")
        s.caption(inbound, "Hello, can you tell me ")
        s.caption(outbound, "Hola, ¿me puede decir ")
        s.speaks(inbound, 4)
        s.speaks(outbound, 4)

        assertEquals(1_000, s.sink.heardMs(inbound))
        assertEquals(1_000, s.sink.heardMs(outbound))
        assertTrue(s.sink.mutedQueued.isEmpty())
    }

    // My "ਠੀਕ ਹੈ, ਧੰਨਵਾਦ" was still being said in English when they answered in the same words.
    @Test
    fun `a translation still being spoken is not cut off by what somebody says next`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("ठीक है, धन्यवाद।", "hi")
        s.caption(outbound, "Okay, thank ")
        s.speaks(outbound, 10)
        s.heard("Okay, thank you.", "en")
        s.caption(outbound, "you.")
        s.speaks(outbound, 2)

        assertEquals("neither as a repeat of their words, nor as their echo", 3_000, s.sink.heardMs(outbound))
        assertTrue(s.sink.mutedQueued.isEmpty())
    }

    // What they said two turns ago is not what I am repeating now.
    @Test
    fun `a translation is measured against the speech it answers, not against the conversation`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 2)
        s.quiet(inbound, 4)
        s.pass(2_000)
        // I greet them back. In Spanish that is the very word they used.
        s.heard("Hello", "en")
        s.caption(outbound, "Hola.")
        s.speaks(outbound, 2)

        assertEquals(500, s.sink.heardMs(outbound))
        assertEquals("Hola.", s.sink.pending(outbound))
    }

    // Recorded: French "Bonjour, je cherche" came back as "नमस्ते, मैं", the second
    // word still unfinished, and the decision waited 1.2 s for the next piece
    // of text while the wrong direction spoke.
    @Test
    fun `a word still being written already counts as one that was not heard`() {
        val s = Scene(mine = "hi-IN", theirs = "en")
        s.heard("Okay.", "en")
        s.pass(3_000)
        s.heard(" Bonjour, je cherche la", "fr")
        s.caption(inbound, " नमस्ते, मैं")
        s.speaks(inbound, 1)

        assertEquals("French is theirs from the second word of its translation", listOf("fr"), s.sink.heard)
    }

    @Test
    fun `a repeat is not let go because its next word is only half written`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("हां जी। ठीक है।", "hi")
        s.caption(inbound, "ਹਾਂ ਜੀ। ")
        s.speaks(inbound, 1)
        s.caption(inbound, "ਠੀ")
        s.speaks(inbound, 2)

        assertEquals(0, s.sink.heardMs(inbound))
        assertEquals("", s.sink.pending(inbound))
    }

    @Test
    fun `the words a session heard may arrive after the first of what it says`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.caption(inbound, "ਹਾਂ ਜੀ। ਠੀਕ ਹੈ। ")
        s.speaks(inbound, 1)
        assertEquals(250, s.sink.heardMs(inbound))

        s.heard("हां जी। ठीक है।", "hi")
        s.speaks(inbound, 3)
        assertEquals("from then on it is known for a repeat", 250, s.sink.heardMs(inbound))
        assertEquals(listOf(inbound), s.sink.mutedQueued)
    }

    // ── before anyone is identified ─────────────────────────────────────

    @Test
    fun `speech that starts before the speaker is known may be heard, and is cut off the moment it turns out to be an echo`() {
        val s = Scene()
        s.caption(outbound, "Hola")
        s.speaks(outbound, 2)
        assertEquals("allowed on sufferance", 500, s.sink.heardMs(outbound))
        assertEquals("Hola", s.sink.pending(outbound))

        s.heard("Hola, ¿cómo estás?", "es")
        assertEquals("what it had queued is silenced", listOf(outbound), s.sink.mutedQueued)
        assertEquals("and its caption is thrown away", listOf(outbound), s.sink.discarded)

        s.speaks(outbound, 4)
        assertEquals("and nothing more of it is heard", 500, s.sink.heardMs(outbound))
    }

    @Test
    fun `a guess that turns out right simply carries on`() {
        val s = Scene()
        s.speaks(inbound, 2)
        s.heard("Hola", "es")
        s.speaks(inbound, 4)

        assertEquals(1_500, s.sink.heardMs(inbound))
        assertTrue(s.sink.mutedQueued.isEmpty())
    }

    // ── a change of speaker ─────────────────────────────────────────────

    // In the recording, the reply's echo began 260 ms after the translation before it ended.
    @Test
    fun `when I answer at once, the end of their translation still plays, and my echo after it does not`() {
        val s = Scene()
        s.heard("Necesito llegar antes de las cinco", "es")
        s.speaks(inbound, 4)
        // I start talking; the tail of their translation is still coming.
        s.heard("Sure, it is", "en")
        s.speaks(inbound, 3)
        val tail = s.sink.heardMs(inbound)
        assertEquals("their last words are not cut off by my first", 1_750, tail)

        s.quiet(inbound, 1)
        s.speaks(inbound, 6)
        assertEquals("what follows the pause is my own English, and is silenced", tail, s.sink.heardMs(inbound))
    }

    @Test
    fun `their echo ends at the first pause, and my translation after it is heard`() {
        val s = Scene()
        s.heard("Muchas gracias", "es")
        s.speaks(outbound, 3)
        assertEquals(0, s.sink.heardMs(outbound))

        s.heard("You are welcome", "en")
        s.speaks(outbound, 1)
        s.quiet(outbound, 1)
        s.speaks(outbound, 4)
        assertEquals("the translation of what I said", 1_000, s.sink.heardMs(outbound))
    }

    // Without a pause to mark the boundary, a silenced leg must not stay silenced for a whole sentence.
    @Test
    fun `with no pause at all, the old decision still lapses`() {
        val s = Scene()
        s.heard("Muchas gracias", "es")
        s.speaks(outbound, 2)
        s.heard("You are welcome", "en")
        val flippedAt = s.now
        s.speaks(outbound, 12)

        val firstHeard = s.sink.played.first { it.leg == outbound && it.voiced }.atMs
        assertTrue(
            "heard again ${firstHeard - flippedAt} ms after I began",
            firstHeard - flippedAt in 1_250..ConversationEngine.STALE_RUN_MAX_MS,
        )
    }

    @Test
    fun `a pause inside one person's sentence changes nothing`() {
        val s = Scene()
        s.heard("Hola, buenos días", "es")
        s.speaks(inbound, 3)
        s.quiet(inbound, 2)
        s.speaks(inbound, 3)
        assertEquals(1_500, s.sink.heardMs(inbound))
    }

    // What is queued of a repeat is silenced; the sentence queued before it is somebody's translation.
    @Test
    fun `the first block of every utterance is marked as its beginning`() {
        val s = Scene()
        s.heard("Hola, buenos días", "es")
        s.speaks(inbound, 2)
        s.quiet(inbound, 4)
        s.speaks(inbound, 2)

        assertEquals(
            listOf(true, false, false, false, false, false, true, false),
            s.sink.played.filter { it.leg == inbound }.map { it.begins },
        )
    }

    // ── captions ────────────────────────────────────────────────────────

    @Test
    fun `text that arrives just ahead of its audio is shown when the audio starts`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.caption(inbound, "Hello,")
        assertEquals("", s.sink.pending(inbound))
        s.speaks(inbound, 1)
        assertEquals("Hello,", s.sink.pending(inbound))
        s.caption(inbound, " good morning.")
        assertEquals("Hello, good morning.", s.sink.pending(inbound))
    }

    // Neither model says when a sentence ends, so nothing was ever committed.
    @Test
    fun `a line is committed when its speaker has been silent for long enough`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 2)
        s.caption(inbound, "Hello, good morning.")
        s.quiet(inbound, 2)
        assertTrue("a short pause does not end the line", s.sink.lines.isEmpty())
        s.quiet(inbound, 1)
        assertEquals(listOf(inbound to "Hello, good morning."), s.sink.lines)
    }

    @Test
    fun `an echo's text is never shown`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.caption(outbound, "Hola")
        s.speaks(outbound, 2)
        s.caption(outbound, ", buenos días")
        s.quiet(outbound, 4)

        assertEquals("", s.sink.pending(outbound))
        assertTrue(s.sink.lines.isEmpty())
    }

    @Test
    fun `the two directions build separate lines`() {
        val s = Scene()
        s.heard("Buenos días", "es")
        s.speaks(inbound, 1)
        s.caption(inbound, "Good morning.")
        s.heard(" Hi there", "en")
        s.caption(outbound, "Hola.")
        s.quiet(inbound, 1)
        s.speaks(outbound, 1)
        s.quiet(inbound, 3)
        s.quiet(outbound, 3)

        assertEquals(listOf(inbound to "Good morning.", outbound to "Hola."), s.sink.lines)
    }

    @Test
    fun `after the speaker changes, a leg's late text is for what it says next, not the line it is finishing`() {
        val s = Scene()
        s.heard("Gracias", "es")
        s.speaks(inbound, 2)
        s.caption(inbound, "Thank you.")
        s.heard("You are welcome", "en")
        // The inbound leg now transcribes my English; that is echo text.
        s.caption(inbound, " You are welcome.")
        s.quiet(inbound, 1)

        assertEquals(listOf(inbound to "Thank you."), s.sink.lines)
        assertEquals("", s.sink.pending(inbound))
    }

    @Test
    fun `text that no audio ever follows is dropped`() {
        val s = Scene()
        s.caption(inbound, "Hello")
        s.pass(3_000)
        s.heard("Hola", "es")
        s.speaks(inbound, 1)
        assertEquals("", s.sink.pending(inbound))
    }

    // ── a provider that sends nothing while silent ──────────────────────

    @Test
    fun `an utterance ends by the clock when no silence is sent`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 2)
        s.caption(inbound, "Hello.")
        // The last block arrived 250 ms ago; the utterance ends a second after it.
        s.pass(700)
        assertTrue(s.sink.lines.isEmpty())
        s.pass(100)
        assertEquals(listOf(inbound to "Hello."), s.sink.lines)
    }

    // Recorded live: nothing at all arrived for 3.4 s in the middle of a sentence.
    @Test
    fun `a stall in the arrivals of a provider that sends its own silence does not end the utterance`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.quiet(inbound, 1)
        s.speaks(inbound, 2)
        s.caption(inbound, "Can you tell me where")
        s.pass(3_400)
        s.caption(inbound, " the station is?")
        s.speaks(inbound, 2)
        assertTrue("the sentence was not cut in two", s.sink.lines.isEmpty())
        s.quiet(inbound, 3)
        assertEquals(listOf(inbound to "Can you tell me where the station is?"), s.sink.lines)
    }

    // A provider that pads a sentence with a moment of silence and then sends
    // nothing would otherwise never be seen to finish it.
    @Test
    fun `a provider that sent some silence and then stopped has still finished its sentence`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 2)
        s.caption(inbound, "Hello.")
        s.quiet(inbound, 1)
        s.pass(ConversationEngine.STALLED_RUN_END_MS.toInt() - 600)
        assertTrue("not yet", s.sink.lines.isEmpty())
        s.pass(700)

        assertEquals(listOf(inbound to "Hello."), s.sink.lines)
        assertEquals("and the state is quiet again", false, s.sink.speaking.last())
    }

    @Test
    fun `with words but no language code, each leg works out the speaker from the words it heard`() {
        val s = Scene()
        s.heard("que", null)
        assertEquals("one word is not enough yet", Speaker.UNKNOWN, s.engine.hears(inbound))
        s.heard(" tal como está usted", null)
        assertEquals(Speaker.THEM, s.engine.hears(inbound))
        assertEquals(Speaker.THEM, s.engine.hears(outbound))
        s.pass(2_000)
        s.heard("yes that is what they have and there was just about", null)
        assertEquals(Speaker.ME, s.engine.hears(inbound))
    }

    // ── their language ──────────────────────────────────────────────────

    // What I say goes out in their language and no other. Sending it out in
    // English until then was speaking a language nobody present had spoken.
    @Test
    fun `before anyone else has been heard there is no language of theirs, and nothing is said for me`() {
        val s = Scene(mine = "pa-IN", theirs = null)
        s.heard("ਸਤਿ ਸ੍ਰੀ ਅਕਾਲ ਜੀ। ਤੁਹਾਡਾ ਕੀ ਹਾਲ ਹੈ?", "pa")
        // The moment of sound the model sometimes makes on hearing its own language.
        s.speaks(inbound, 2)
        s.quiet(inbound, 3)

        assertNull(s.engine.theirLanguage)
        assertEquals(0, s.sink.heardMs(inbound))
        assertTrue(s.sink.heard.isEmpty())
        assertTrue(s.sink.lines.isEmpty())
    }

    @Test
    fun `the first language heard is theirs once it has been translated for me`() {
        val s = Scene(mine = "pa-IN", theirs = null)
        s.heard("Sure. It is about 10 minutes from here.", "en")
        assertTrue("its name alone is not enough", s.sink.heard.isEmpty())

        s.caption(inbound, "ਹਾਂਜੀ। ਇਹ ਇੱਥੋਂ ਲਗਭਗ 10 ਮਿੰਟ ")
        s.speaks(inbound, 1)
        assertEquals(listOf("en"), s.sink.heard)
        assertEquals("en", s.engine.theirLanguage)
        assertEquals(250, s.sink.heardMs(inbound))
    }

    // "Hello." comes back as one word; it is plainly a translation only once it is all there is.
    @Test
    fun `a first language heard in a word or two is theirs when its translation is over`() {
        val s = Scene(mine = "pa-IN", theirs = null)
        s.heard("Hello.", "en")
        s.caption(inbound, "ਹੈਲੋ।")
        s.speaks(inbound, 2)
        assertTrue(s.sink.heard.isEmpty())
        s.quiet(inbound, 3)
        assertEquals(listOf("en"), s.sink.heard)
    }

    // Recorded: my own Punjabi, taken for Hindi and "translated" back. Had the
    // name been believed, my next sentence would have gone out in Hindi.
    @Test
    fun `my own language under another name is never taken for theirs`() {
        val s = Scene(mine = "pa-IN", theirs = null)
        s.heard("हां जी। ठीक है।", "hi")
        s.caption(inbound, "ਹਾਂ ਜੀ। ਠੀਕ ਹੈ।")
        s.speaks(inbound, 4)
        s.quiet(inbound, 3)

        assertTrue(s.sink.heard.isEmpty())
        assertNull(s.engine.theirLanguage)
        assertEquals(0, s.sink.heardMs(inbound))
    }

    // Recorded: the same sentence in a longer form, written down in Hindi's
    // own words. Half of what came back was what had been said: not a repeat
    // that can be proved, and not a new language either.
    @Test
    fun `a language is not theirs while what it is turned into shares half its words`() {
        val s = Scene(mine = "pa-IN", theirs = null)
        s.heard("हां जी। ये यहां से लगभग 10 मिनट की दूरी पर है।", "hi")
        s.caption(inbound, "ਹਾਂ ਜੀ। ਇਹ ਇੱਥੋਂ ਲਗਭਗ 10 ਮਿੰਟ ਦੀ ਦੂਰੀ 'ਤੇ ਹੈ। ")
        s.speaks(inbound, 6)
        s.quiet(inbound, 3)

        assertTrue(s.sink.heard.isEmpty())
        assertNull(s.engine.theirLanguage)
        assertEquals("it cannot be told from a translation, so it is let through", 1_500, s.sink.heardMs(inbound))
    }

    // "Now somebody speaks Chinese": what I say next goes out in Chinese.
    @Test
    fun `somebody who begins to speak another language is followed from their first translated words`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("Okay, thank you.", "en")
        s.pass(3_000)
        s.heard("你好,请问", "zh")
        assertTrue("its name alone is not enough", s.sink.heard.isEmpty())

        s.caption(inbound, "ਹੈਲੋ, ਮੈਂ ਪੁੱਛਣਾ ਚਾਹੁੰਦਾ ਹਾਂ ")
        s.caption(outbound, "Hello, may I ask ")
        s.speaks(inbound, 1)
        assertEquals(listOf("zh"), s.sink.heard)
        assertEquals("zh", s.engine.theirLanguage)

        s.speaks(outbound, 4)
        assertEquals("their Chinese is not also put into English", 0, s.sink.heardMs(outbound))
        assertEquals(Speaker.THEM, s.engine.hears(outbound))
    }

    @Test
    fun `in the middle of speech a new language has to be said twice before it can be theirs`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("Okay, thank you.", "en")
        s.heard(" 你好,请问", "zh")
        s.caption(inbound, "ਹੈਲੋ, ਮੈਂ ਪੁੱਛਣਾ ਚਾਹੁੰਦਾ ਹਾਂ ")
        s.speaks(inbound, 2)
        assertTrue("once could be one misheard fragment", s.sink.heard.isEmpty())

        s.heard("最近的地铁站在哪里?", "zh")
        assertEquals(listOf("zh"), s.sink.heard)
    }

    // They are still being translated when two fragments are called Chinese.
    // The translation under way began before them, and proves nothing about them.
    @Test
    fun `the translation of what was said before does not vouch for a language named after it`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("Okay, thank you.", "en")
        s.caption(inbound, "ਠੀਕ ਹੈ, ਧੰਨਵਾਦ। ")
        s.speaks(inbound, 2)
        s.heard(" 你好", "zh")
        s.heard("请问", "zh")

        assertTrue(s.sink.heard.isEmpty())
        assertEquals("en", s.engine.theirLanguage)
    }

    // Recorded: a Punjabi sentence heard as Hindi by one session and as Japanese by the other.
    @Test
    fun `a language the two sessions cannot agree on is nobody's`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("हां जी। ये यहां से", "hi", leg = inbound)
        s.heard("ハンジえっと", "ja", leg = outbound)
        s.caption(inbound, "Hello there, ")
        s.speaks(inbound, 2)
        s.heard(" लगभग 10 मिनट", "hi", leg = inbound)
        s.heard("10分ぐらい", "ja", leg = outbound)

        assertTrue(s.sink.heard.isEmpty())
        assertEquals("en", s.engine.theirLanguage)
    }

    // The same thing from the other side: I speak English, they speak Punjabi,
    // and the session aimed at Punjabi says their own words back to them.
    @Test
    fun `their own language under another name does not become a new one`() {
        val s = Scene(mine = "en", theirs = "pa-IN")
        s.heard("हां जी। ठीक है।", "hi")
        s.heard(" मेरा फोन चार्ज नहीं है।", "hi")
        s.caption(outbound, "ਹਾਂ ਜੀ। ਠੀਕ ਹੈ। ")
        s.caption(inbound, "Yes. Okay. My phone ")
        s.speaks(outbound, 4)
        s.speaks(inbound, 4)

        assertEquals("their words are not said back to them", 0, s.sink.heardMs(outbound))
        assertEquals("and I hear what they said", 1_000, s.sink.heardMs(inbound))
        assertTrue(s.sink.heard.isEmpty())
        assertEquals("pa-IN", s.engine.theirLanguage)
    }

    @Test
    fun `an offer that came with speech nobody translated is forgotten`() {
        val s = Scene(mine = "pa-IN", theirs = null)
        s.heard("Hm.", "en")
        s.pass(HeardLanguageTracker.OFFER_TTL_MS.toInt() + 100)
        // Something else entirely is translated later.
        s.caption(inbound, "ਕੁਝ ਹੋਰ ਗੱਲ ")
        s.speaks(inbound, 2)

        assertTrue(s.sink.heard.isEmpty())
    }

    @Test
    fun `a provider that sends no text is taken at its word once it has translated for a while`() {
        val s = Scene(mine = "pa-IN", theirs = null)
        s.heard("Sure. It is about 10 minutes from here.", "en")
        s.speaks(inbound, 5)
        assertTrue(s.sink.heard.isEmpty())
        s.speaks(inbound, 2)
        assertEquals(listOf("en"), s.sink.heard)
    }

    @Test
    fun `a pinned language is never moved by what is heard`() {
        val s = Scene(follows = false)
        s.heard("नमस्ते", "hi")
        s.caption(inbound, "Hello, could you ")
        s.speaks(inbound, 2)
        s.heard("क्या आप", "hi")
        s.heard("बता सकते हैं", "hi")

        assertTrue(s.sink.heard.isEmpty())
        assertEquals("the language fixed by hand has not drifted", "es", s.engine.theirLanguage)
    }

    @Test
    fun `going back to following starts from the language that was fixed`() {
        val s = Scene(follows = false)
        s.heard("नमस्ते", "hi")
        s.engine.followsTheirLanguage = true
        s.heard("क्या आप", "hi")
        assertTrue("once is not enough to leave it", s.sink.heard.isEmpty())

        s.heard("बता सकते हैं", "hi")
        s.caption(inbound, "Hello, could you tell me ")
        s.speaks(inbound, 1)
        assertEquals(listOf("hi"), s.sink.heard)
    }

    @Test
    fun `changing a language by hand forgets who was speaking`() {
        val s = Scene()
        s.heard("Hola", "es")
        assertEquals(Speaker.THEM, s.engine.hears(inbound))
        s.engine.reconfigure("en", null)

        assertEquals(Speaker.UNKNOWN, s.engine.hears(inbound))
        assertNull(s.engine.theirLanguage)
    }

    // ── state ───────────────────────────────────────────────────────────

    @Test
    fun `speaking is announced when a translation that may be heard starts, and when it ends`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(outbound, 2)
        assertTrue("an echo is not speaking", s.sink.speaking.isEmpty())
        s.speaks(inbound, 2)
        assertEquals(listOf(true), s.sink.speaking)
        s.quiet(inbound, 3)
        assertEquals(listOf(true, false), s.sink.speaking)
    }

    @Test
    fun `a repeat is not speaking either`() {
        val s = Scene(mine = "pa-IN", theirs = "en")
        s.heard("हां जी। ठीक है।", "hi")
        s.caption(inbound, "ਹਾਂ ਜੀ। ਠੀਕ ਹੈ।")
        s.speaks(inbound, 2)

        assertTrue(s.sink.speaking.isEmpty())
        assertFalse(s.engine.isMidRun(inbound))
    }

    @Test
    fun `a leg that closes mid-sentence commits what it had`() {
        val s = Scene()
        s.heard("Hola", "es")
        s.speaks(inbound, 1)
        s.caption(inbound, "Hello")
        assertTrue(s.engine.isMidRun(inbound))
        s.engine.legClosed(inbound)

        assertEquals(listOf(inbound to "Hello"), s.sink.lines)
        assertFalse(s.engine.isMidRun(inbound))
        assertEquals(listOf(true, false), s.sink.speaking)
    }

    @Test
    fun `the rule itself`() {
        assertTrue(ConversationEngine.mayRoleSpeak(LegRole.INBOUND, Speaker.THEM))
        assertFalse(ConversationEngine.mayRoleSpeak(LegRole.INBOUND, Speaker.ME))
        assertTrue(ConversationEngine.mayRoleSpeak(LegRole.OUTBOUND, Speaker.ME))
        assertFalse(ConversationEngine.mayRoleSpeak(LegRole.OUTBOUND, Speaker.THEM))
        assertTrue(ConversationEngine.mayRoleSpeak(LegRole.INBOUND, Speaker.UNKNOWN))
        assertTrue(ConversationEngine.mayRoleSpeak(LegRole.OUTBOUND, Speaker.UNKNOWN))
    }
}
