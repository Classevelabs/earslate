package com.classeve.earslate.session

/**
 * Whose voice the microphone is currently carrying, worked out from the
 * listening leg's source transcript.
 *
 * This is the fact the leg-muting rule runs on. Each translate leg is
 * configured to stay silent when the input is already in its target language,
 * but that is a MODEL behaviour, not a guarantee, and on a preview model it
 * slips: the leg targeting your own language repeats your words back, or the
 * leg targeting theirs repeats theirs. Either way you hear the same language
 * you just heard, and the real translation — which arrived a moment later on
 * the other leg — is refused by ownership and lost. The app already knows who
 * is speaking; it should not need the model to agree.
 *
 * Reset to UNKNOWN when a new utterance begins, so a short reply that detection
 * cannot place ("yes") is not judged by the previous speaker.
 */
internal enum class Speaker { UNKNOWN, ME, THEM }

/**
 * The echo-suppression rule, and the per-leg turn bookkeeping it needs.
 *
 * **The rule the product lives or dies on: never speak the language you just
 * heard.** The leg that translates INTO my language has no business talking
 * while I am the one talking, and the leg that translates into theirs has no
 * business talking while they are. Anything either produces then is an echo of
 * the language just heard.
 *
 * Lifted out of [SessionCoordinator] so `EchoSuppressionTest` can pin the real
 * rule. It used to live inline in `mayLegSpeak`, reading four pieces of the
 * coordinator's mutable state plus `SystemClock.elapsedRealtime()` — none of
 * which a JVM unit test can reach or move, since building a coordinator needs
 * an `AudioManager` and the unit-test `android.jar` freezes that clock at 0. So
 * the rule was "tested" against a hand-written copy of itself living in the
 * test file, which would have passed identically had the real rule been
 * inverted, drifted, or deleted outright — the exact anti-pattern
 * ENGINEERING-STANDARD §II.4 forbids by name. Behaviour here is unchanged; only
 * its address is.
 *
 * Pure: no Android types, and no clock of its own — [decide] is TOLD what time
 * it is, which is what makes the [handoverIdleMs] turn boundary observable at
 * all.
 *
 * NOT internally synchronized. Every call site in [SessionCoordinator] already
 * holds `legLock`, and this object holds exactly the state that lock was
 * protecting; keep it that way rather than adding a second lock with an
 * acquisition order somebody has to remember.
 */
internal class LegTurnGate(private val handoverIdleMs: Long) {

    /**
     * Whether a leg may speak for its current output turn, and whether that was
     * decided from a KNOWN speaker.
     *
     * The distinction is the whole fix. A decision taken while the speaker was
     * still unknown is a guess, and guessing "yes" is what let the wrong leg
     * start an echo, take ownership of the shared stream, and lock out the real
     * translation arriving two hundred milliseconds later. A guess is therefore
     * re-examined on every chunk until the transcript resolves who is talking;
     * only then does it stick for the rest of the turn.
     */
    private class TurnDecision(val allowed: Boolean, val definite: Boolean)

    /** Per leg: when its last non-silent audio arrived. */
    private val lastAudioAt = HashMap<String, Long>()

    /** Per leg: the standing decision for the output turn it is producing. */
    private val turnAllowed = HashMap<String, TurnDecision>()

    /**
     * [allowed] is the verdict for this chunk. [newlyMuted] is true only on the
     * chunk that FIRST silences a leg's turn, so the caller logs that edge
     * rather than every chunk of an already-muted turn.
     */
    internal class Verdict(val allowed: Boolean, val newlyMuted: Boolean)

    /**
     * Whether [legCode] may speak for the output turn this chunk belongs to.
     *
     * Decided ONCE per turn, at the first non-silent chunk, from who was heard
     * speaking — then held for the rest of that turn (or until the leg goes
     * quiet for [handoverIdleMs]) so a translation already mid-flight is not
     * silenced because the next person started talking over it.
     *
     * The exception is a decision taken while the speaker was still UNKNOWN.
     * That is a guess, and it is judged again on every chunk, so the moment the
     * transcript resolves, a leg that was guessing goes quiet on its very next
     * chunk instead of finishing the echo.
     *
     * @param primaryCode the leg that translates INTO my language — the one
     *   that listens. Null before a session has started.
     * @param now a monotonic millisecond clock; the caller owns which one.
     */
    fun decide(legCode: String, primaryCode: String?, speaker: Speaker, now: Long): Verdict {
        val last = lastAudioAt[legCode] ?: 0L
        lastAudioAt[legCode] = now
        val newTurn = now - last > handoverIdleMs
        val standing = turnAllowed[legCode]
        if (!newTurn && standing != null && standing.definite) {
            return Verdict(standing.allowed, newlyMuted = false)
        }
        val allowed = mayRoleSpeak(legCode, primaryCode, speaker)
        turnAllowed[legCode] = TurnDecision(allowed, speaker != Speaker.UNKNOWN)
        return Verdict(allowed, newlyMuted = !allowed && standing?.allowed != false)
    }

    /**
     * Whether [legCode]'s standing decision was taken from a KNOWN speaker.
     *
     * Read by `claimLeg`: a leg that KNOWS it should be speaking takes the
     * shared stream from one that merely guessed. A leg with no standing
     * decision has not earned anything.
     */
    fun isDefinite(legCode: String): Boolean = turnAllowed[legCode]?.definite == true

    /**
     * The transcript has named the speaker. Drop every decision taken before we
     * knew, and keep the ones that were not guesses — a translation already
     * playing on a correctly-decided leg must not be re-judged and cut off.
     */
    fun discardGuesses() {
        turnAllowed.entries.removeAll { !it.value.definite }
    }

    /** A fresh utterance has begun; nothing decided about the old one applies. */
    fun clearDecisions() {
        turnAllowed.clear()
    }

    /** [legCode]'s turn ended. Its next audio is a new turn, to be judged afresh. */
    fun endTurn(legCode: String) {
        lastAudioAt.remove(legCode)
        turnAllowed.remove(legCode)
    }

    /** Session teardown: nothing survives into the next session. */
    fun reset() {
        lastAudioAt.clear()
        turnAllowed.clear()
    }

    companion object {
        /**
         * The invariant, stated as roles: **never speak the language you just
         * heard.** With nobody identified yet a leg is allowed to speak — but
         * on sufferance; see [decide].
         *
         * Case-insensitive on the leg code deliberately: language codes reach
         * the coordinator from saved settings, from the provider's own echo of
         * the setup frame, and from the heard-language detector, and those do
         * not agree on case. A missed match here does not merely fail to
         * suppress — it INVERTS the rule, because the listening leg would then
         * be judged as the outbound one.
         *
         * One definition, called from both places that need it. It was written
         * out twice — here, and again in `resolveSpeaker`'s re-check of the leg
         * already holding the stream — which is two copies of one idea, the
         * shape that lets a fix land in one of them and not the other.
         */
        fun mayRoleSpeak(legCode: String, primaryCode: String?, speaker: Speaker): Boolean {
            val isPrimary = legCode.equals(primaryCode, ignoreCase = true)
            return when (speaker) {
                Speaker.ME -> !isPrimary
                Speaker.THEM -> isPrimary
                Speaker.UNKNOWN -> true
            }
        }
    }
}
