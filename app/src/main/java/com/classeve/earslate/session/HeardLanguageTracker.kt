package com.classeve.earslate.session

/**
 * Follows the language the OTHER side of the conversation is speaking.
 *
 * The microphone hears both people, so every transcript that comes back is
 * either them or us. Anything detected as [myLanguageBcp47] is us and is
 * ignored; everything else is what we should be speaking back in.
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

    var current: String = initial
        private set

    private val turn = StringBuilder()

    /**
     * Feed one transcript fragment from the current turn.
     *
     * @return the new language when it has changed, null otherwise. A null is
     *   the common case and means "carry on"; it is NOT a fallback signal.
     */
    fun observe(fragment: String): String? {
        turn.append(fragment)
        val detected = LanguageDetector.detect(turn.toString()) ?: return null
        if (sameLanguage(detected, myLanguageBcp47)) return null
        if (sameLanguage(detected, current)) return null
        current = detected
        return detected
    }

    /** The speaker has finished. What comes next is a new utterance. */
    fun endTurn() {
        turn.setLength(0)
    }

    /** Regional variants are the same target on the wire, so treat them as equal. */
    private fun sameLanguage(a: String, b: String): Boolean =
        a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
}
