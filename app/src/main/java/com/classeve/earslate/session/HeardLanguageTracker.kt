package com.classeve.earslate.session

/**
 * Works out, from the transcript of what the microphone heard, WHO is speaking
 * and — when it is the other person — in what.
 *
 * The microphone hears both people, so every transcript that comes back is
 * either them or us. Anything detected as [myLanguageBcp47] is us; everything
 * else is them, and whatever language it is in is what we should be speaking
 * back in.
 *
 * Starts at English because that is what the product promises when it has not
 * heard anything it recognises yet — never at "unknown", which would leave the
 * outbound direction with no target at all.
 *
 * The transcript arrives as fragments — a word or two at a time — and a
 * fragment is not enough to name a language: "que" is Spanish, French and
 * Portuguese. So the fragments are accumulated for the length of one turn and
 * detection runs on everything heard so far. The first version ran it per
 * fragment, which meant Latin-script languages were almost never recognised
 * at all, and a "confirm it twice" rule on top of that meant a second language
 * entering the conversation was never followed. Both are gone: the whole turn
 * is the evidence, and a confident answer is acted on.
 */
class HeardLanguageTracker(
    private val myLanguageBcp47: String,
    initial: String = TargetLanguage.EnglishUS.bcp47,
) {

    /** What the microphone said, once it was clear enough to say anything. */
    sealed interface Heard {
        /** The device owner, in their own language. */
        data object Me : Heard

        /**
         * The other person. [changed] is true when [bcp47] differs from the
         * language we were speaking back in until now — the signal to re-aim
         * the outbound direction.
         */
        data class Them(val bcp47: String, val changed: Boolean) : Heard
    }

    /** The language the other side is being spoken back in. */
    var current: String = initial
        private set

    private val turn = StringBuilder()

    /** True once at least one fragment of the current turn has arrived. */
    val turnStarted: Boolean get() = turn.isNotEmpty()

    /**
     * Feed one transcript fragment from the current turn.
     *
     * @return who was heard, or null while the turn is still too short or too
     *   ambiguous to say. Null is the common case early in a turn and means
     *   "carry on"; it is NOT a fallback signal.
     */
    fun observe(fragment: String): Heard? {
        turn.append(fragment)
        val detected = LanguageDetector.detect(turn.toString()) ?: return null
        if (sameLanguage(detected, myLanguageBcp47)) return Heard.Me
        val changed = !sameLanguage(detected, current)
        if (changed) current = detected
        return Heard.Them(detected, changed)
    }

    /** The speaker has finished. What comes next is a new utterance. */
    fun endTurn() {
        turn.setLength(0)
    }

    /** Regional variants are the same target on the wire, so treat them as equal. */
    private fun sameLanguage(a: String, b: String): Boolean =
        a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
}
