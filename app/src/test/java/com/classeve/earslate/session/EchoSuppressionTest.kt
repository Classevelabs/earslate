package com.classeve.earslate.session

import com.classeve.earslate.session.HeardLanguageTracker.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the product lives or dies on: **never speak the language you just
 * heard.**
 *
 * Two production objects decide this between them, and both are exercised here:
 * [HeardLanguageTracker] names who is talking, and [LegTurnGate] turns that name
 * into a per-leg verdict — which is what mutes a translate leg, and therefore
 * what decides whether the user hears a translation or an echo of themselves.
 *
 * The failure the rule exists for: the decision was taken at the FIRST chunk of
 * a leg's output, which arrives before the transcript has said enough to name
 * the speaker. Unknown meant "allowed", so the wrong leg started an echo, took
 * ownership of the shared stream, and the real translation arriving two hundred
 * milliseconds later was refused and dropped. The user heard themselves and the
 * translation was thrown away.
 *
 * The failure THIS FILE exists for, which is a different one: it used to carry a
 * private `maySpeak(speaker, isPrimary)` of its own, commented "mirrors
 * SessionCoordinator.mayLegSpeak's role rule exactly", and every assertion below
 * ran against that copy. It never once called production code, so it would have
 * passed identically had the real rule been inverted, drifted, or deleted —
 * ENGINEERING-STANDARD §II.4, "passes identically whether the feature works or
 * is deleted". The rule now lives in [LegTurnGate] precisely so this file can
 * call it. Do not reintroduce a local copy; if the real rule is hard to reach
 * from here, move the rule, not the test.
 */
class EchoSuppressionTest {

    private companion object {
        /** The leg translating INTO my language — the one that listens. */
        const val PRIMARY = "en-US"

        /** The leg carrying my words into theirs. */
        const val OUTBOUND = "hi-IN"

        /** The production constant itself, never a copy of the number. */
        const val IDLE = SessionCoordinator.LEG_HANDOVER_IDLE_MS
    }

    private fun gate() = LegTurnGate(IDLE)

    // ---- the role rule ----------------------------------------------------

    @Test
    fun `while I speak, the leg aimed at my own language is silent`() {
        val t = HeardLanguageTracker("en-US")
        assertEquals(Heard.Me, t.observe("how are you doing today with that"))
        val g = gate()
        // The listening leg translates INTO English. I am speaking English.
        assertFalse(
            "the primary leg must not repeat my own words",
            g.decide(PRIMARY, PRIMARY, Speaker.ME, now = 1_000L).allowed,
        )
        // The outbound leg carries my words into theirs. It must.
        assertTrue(g.decide(OUTBOUND, PRIMARY, Speaker.ME, now = 1_000L).allowed)
    }

    @Test
    fun `while they speak, only the leg aimed at my language answers`() {
        val t = HeardLanguageTracker("en-US")
        val heard = t.observe("आप कैसे हैं आज")
        assertTrue(heard is Heard.Them)
        val g = gate()
        assertTrue(g.decide(PRIMARY, PRIMARY, Speaker.THEM, now = 1_000L).allowed)
        assertFalse(
            "the outbound leg must not repeat their own words",
            g.decide(OUTBOUND, PRIMARY, Speaker.THEM, now = 1_000L).allowed,
        )
    }

    /**
     * The bug: language codes reach the coordinator from saved settings, from
     * the provider's echo of the setup frame, and from the heard-language
     * detector, and those sources do not agree on case. A missed match does not
     * merely fail to suppress — it INVERTS the rule, because the listening leg
     * is then judged as the outbound one and speaks in exactly the moment it
     * must not.
     */
    @Test
    fun `the primary leg is recognised whatever case its code arrives in`() {
        val g = gate()
        assertFalse(
            "en-us IS the primary leg; it must not echo me",
            g.decide("en-us", PRIMARY, Speaker.ME, now = 1_000L).allowed,
        )
        // A new turn (past the idle window), now with them talking.
        assertTrue(g.decide("en-us", PRIMARY, Speaker.THEM, now = 1_000L + IDLE + 1).allowed)
    }

    // ---- guess versus knowledge -------------------------------------------

    /**
     * The re-examination rule, which is the whole fix. A verdict reached while
     * the speaker was still UNKNOWN is a guess; it must be judged again on
     * every chunk until the transcript resolves.
     *
     * The bug this catches: if a guess were allowed to stick for the turn — as
     * it would if `definite` were dropped, or hardcoded true — a leg that
     * started speaking on "nobody identified yet" would finish the echo even
     * after the transcript named the speaker. That is the original defect.
     */
    @Test
    fun `a verdict reached before the speaker was known is judged again`() {
        val g = gate()
        // First chunk: the transcript has not said enough. Allowed, on sufferance.
        assertTrue(g.decide(PRIMARY, PRIMARY, Speaker.UNKNOWN, now = 1_000L).allowed)
        assertFalse("a guess is not a decision", g.isDefinite(PRIMARY))
        // 100 ms later — the SAME turn — the transcript names me.
        assertFalse(
            "the guess must be revisited the moment the speaker is known",
            g.decide(PRIMARY, PRIMARY, Speaker.ME, now = 1_100L).allowed,
        )
        assertTrue(g.isDefinite(PRIMARY))
    }

    /**
     * The other half of the same rule, and the reason it is not simply
     * "re-decide every chunk".
     *
     * The bug this catches: judging afresh on every chunk cuts a translation
     * off mid-sentence the instant the other person starts talking over it —
     * the user hears half an answer.
     */
    @Test
    fun `a verdict reached from a known speaker is held for the rest of the turn`() {
        val g = gate()
        assertTrue(g.decide(PRIMARY, PRIMARY, Speaker.THEM, now = 1_000L).allowed)
        // I start talking over the translation that is still playing.
        assertTrue(
            "a translation already in flight is not cut off",
            g.decide(PRIMARY, PRIMARY, Speaker.ME, now = 1_100L).allowed,
        )
        assertTrue(g.decide(PRIMARY, PRIMARY, Speaker.ME, now = 1_200L).allowed)
    }

    /**
     * `claimLeg` lets a leg that KNOWS it should be speaking take the shared
     * stream from one that merely guessed. This is the input that rule reads.
     *
     * The bug this catches: if a guess reported itself as definite, it would
     * evict the leg carrying the real translation — the exact ownership
     * lock-out the whole mechanism exists to prevent.
     */
    @Test
    fun `only a verdict from a known speaker counts as definite`() {
        val g = gate()
        g.decide(OUTBOUND, PRIMARY, Speaker.UNKNOWN, now = 1_000L)
        assertFalse("a guess must never evict anybody", g.isDefinite(OUTBOUND))
        g.decide(OUTBOUND, PRIMARY, Speaker.ME, now = 1_100L)
        assertTrue("knowing who is talking is what earns the stream", g.isDefinite(OUTBOUND))
        assertFalse("a leg nobody has heard from has earned nothing", g.isDefinite("fr-FR"))
    }

    /**
     * The bug this catches: inverting the predicate — discarding the real
     * decisions and keeping the guesses — would re-judge the leg that is
     * correctly mid-translation and silence it.
     */
    @Test
    fun `resolving the speaker drops the guesses and keeps the real decisions`() {
        val g = gate()
        assertTrue(g.decide(PRIMARY, PRIMARY, Speaker.THEM, now = 1_000L).allowed)
        g.decide(OUTBOUND, PRIMARY, Speaker.UNKNOWN, now = 1_000L)

        g.discardGuesses()

        assertFalse("the guess is gone", g.isDefinite(OUTBOUND))
        assertTrue("a decision from a known speaker survives", g.isDefinite(PRIMARY))
        // ...and is still HELD: had it been discarded, this chunk would be
        // re-judged under ME and the primary leg would fall silent mid-sentence.
        assertTrue(g.decide(PRIMARY, PRIMARY, Speaker.ME, now = 1_100L).allowed)
    }

    // ---- the turn boundary -------------------------------------------------

    /**
     * A leg's turn ends when it goes quiet for [SessionCoordinator.LEG_HANDOVER_IDLE_MS].
     * The comparison is strictly greater, so the window itself is still one turn.
     *
     * The bug this catches: an off-by-one or a `>=` here ends turns early,
     * which re-opens the mid-sentence cut-off; a window read in the wrong unit
     * (seconds for millis) never ends them at all, and the first verdict of a
     * session would hold for the rest of it.
     */
    @Test
    fun `the turn survives exactly the idle window and ends one millisecond past it`() {
        val g = gate()
        var now = 1_000L
        assertTrue(g.decide(PRIMARY, PRIMARY, Speaker.THEM, now).allowed)

        now += IDLE
        assertTrue(
            "at exactly the idle window this is still the same turn",
            g.decide(PRIMARY, PRIMARY, Speaker.ME, now).allowed,
        )

        now += IDLE + 1
        assertFalse(
            "one millisecond past the window is a new turn, judged afresh",
            g.decide(PRIMARY, PRIMARY, Speaker.ME, now).allowed,
        )
    }

    /**
     * The bug this catches: the provider's `turnComplete` clears the leg, but if
     * it did not, permission earned while THEY were talking would carry into the
     * leg's next turn and echo ME — inside the idle window, where nothing else
     * would re-judge it.
     */
    @Test
    fun `a leg's turn end means the next one inherits no permission`() {
        val g = gate()
        assertTrue(g.decide(PRIMARY, PRIMARY, Speaker.THEM, now = 1_000L).allowed)
        assertTrue(g.isDefinite(PRIMARY))

        g.endTurn(PRIMARY)

        assertFalse(g.isDefinite(PRIMARY))
        assertFalse(
            "a new turn must not inherit the last one's permission",
            g.decide(PRIMARY, PRIMARY, Speaker.ME, now = 1_100L).allowed,
        )
    }

    /**
     * The bug this catches: session teardown leaves a leg's verdict behind and
     * the NEXT session's first chunk plays on a decision taken about a
     * conversation that has ended.
     */
    @Test
    fun `teardown leaves nothing behind for the next session`() {
        val g = gate()
        g.decide(PRIMARY, PRIMARY, Speaker.THEM, now = 1_000L)
        g.decide(OUTBOUND, PRIMARY, Speaker.THEM, now = 1_000L)

        g.reset()

        assertFalse(g.isDefinite(PRIMARY))
        assertFalse(g.isDefinite(OUTBOUND))
        // Inside the idle window, so only a cleared clock makes this a new turn.
        assertFalse(
            "the previous session's permission must not survive",
            g.decide(PRIMARY, PRIMARY, Speaker.ME, now = 1_100L).allowed,
        )
    }

    // ---- who is talking: the tracker feeding the rule ----------------------

    /**
     * The verdict taken before the speaker is known is a GUESS, and the
     * coordinator only lets a guess stand until the transcript resolves. This
     * pins the input side of that: an opening fragment genuinely does not name
     * the speaker, so anything decided on it must be revisited.
     */
    @Test
    fun `an opening fragment does not name the speaker`() {
        val t = HeardLanguageTracker("en-US")
        assertNull("one word cannot decide who is talking", t.observe("hola"))
        // ...and the very next fragments do.
        assertNull(t.observe(" que"))
        val heard = t.observe(" tal estas hoy")
        assertTrue(heard is Heard.Them)
        assertEquals("es-ES", (heard as Heard.Them).bcp47)
    }

    /**
     * My language is English and nothing has been heard from anyone else yet.
     * There is no other language to translate INTO, so the correct output is
     * nothing at all — never English back at me.
     */
    @Test
    fun `english speaker with nobody else heard yet is still never echoed`() {
        val t = HeardLanguageTracker("en-US")
        assertEquals(Heard.Me, t.observe("this is me talking to myself here"))
        assertEquals("en-US", t.current)
        // The only leg that exists targets English, and it is the primary one.
        assertFalse(gate().decide(PRIMARY, PRIMARY, Speaker.ME, now = 1_000L).allowed)
    }

    /**
     * A correction has to survive the room. If the user says "they are speaking
     * Spanish", the next Hindi-looking fragment must not silently undo it —
     * that is what pinning is for, and the tracker still reports what it heard
     * so the muting stays correct either way.
     */
    @Test
    fun `the tracker still names the speaker even when its language is pinned`() {
        val t = HeardLanguageTracker("en-US", initial = "es-ES")
        val heard = t.observe("आप कैसे हैं आज")
        assertTrue(heard is Heard.Them)
        // It reports the change; whether the session ACTS on it is the
        // coordinator's decision, gated on followsTheirLanguage.
        assertTrue((heard as Heard.Them).changed)
    }
}
