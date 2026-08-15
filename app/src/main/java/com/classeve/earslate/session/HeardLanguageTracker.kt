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
 */
class HeardLanguageTracker(
    private val myLanguageBcp47: String,
    initial: String = TargetLanguage.EnglishUS.bcp47,
) {

    var current: String = initial
        private set

    /** True until a foreign language has actually been heard. */
    private var settled = false
    private var candidate: String? = null

    /**
     * @return the new language when it has changed, null otherwise. A null is
     *   the common case and means "carry on"; it is NOT a fallback signal.
     */
    fun observe(transcript: String): String? {
        val detected = LanguageDetector.detect(transcript) ?: return null
        if (sameLanguage(detected, myLanguageBcp47)) return null
        if (sameLanguage(detected, current)) {
            settled = true
            candidate = null
            return null
        }

        // The first foreign speaker switches us immediately — waiting for a
        // second utterance would mean their opening sentence comes back in the
        // wrong language, which is the whole complaint. After that, changing
        // costs a socket teardown and a fresh credential, so a second agreeing
        // utterance is required before we pay for it.
        if (!settled) {
            settled = true
            candidate = null
            current = detected
            return detected
        }
        if (candidate != detected) {
            candidate = detected
            return null
        }
        candidate = null
        current = detected
        return detected
    }

    /** Regional variants are the same target on the wire, so treat them as equal. */
    private fun sameLanguage(a: String, b: String): Boolean =
        a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
}
