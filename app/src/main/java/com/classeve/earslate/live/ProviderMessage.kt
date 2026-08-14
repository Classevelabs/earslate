package com.classeve.earslate.live

/**
 * Makes a provider's own error text safe to put in front of the user.
 *
 * The provider's verdict is the single most useful thing we can say when a
 * session dies — "You exceeded your current quota" tells someone exactly what
 * to do, where "Lost connection" sends them to reboot their router. So the text
 * is worth surfacing. It is also the one string in the system we did not write
 * and cannot predict.
 *
 * `ProviderSessionMinter` resolves that tension by never showing provider text
 * at all, and gives the reason in its own KDoc: the message "occasionally
 * echoes the key back". That is the correct instinct and the wrong trade —
 * discarding the message throws away the diagnosis to avoid the leak. This
 * removes the leak and keeps the diagnosis.
 *
 * The rule is deliberately about SHAPE, not about known key formats. An
 * allowlist of prefixes (`sk-`, `AIza`) is a losing game: providers change
 * their formats — earslate already shipped a release that rejected valid keys
 * for exactly that reason — and the minted session credentials this app also
 * handles look like nothing in particular. What every secret does have in
 * common is that it is a long unbroken run of key characters, and human prose
 * is not: the longest words in ordinary English error text are well under 20
 * characters, and anything that long without a space or punctuation is a token,
 * an id, or a key. Redact by that and format changes cannot outrun it.
 */
object ProviderMessage {

    /** Candidate runs: long, and drawn only from the alphabet secrets use. */
    private val LONG_RUN = Regex("[A-Za-z0-9_\\-]{20,}")

    /**
     * Length alone is not the discriminator, and the first version of this
     * proved it by redacting "internationalisation" — exactly 20 characters —
     * out of a real sentence. Caught by its own test.
     *
     * What separates a secret from a word is SHAPE. Prose words are alphabetic
     * and either lowercase or capitalised; keys carry digits, underscores, or
     * capitals in the middle. So a long run is redacted when it looks unlike a
     * word, and left alone when it looks like one:
     *
     *   internationalisation                     kept — all lower, no digits
     *   AIzaSyD-9tSrke72PouQMnMX-a7eZSW0jkFMBWY  redacted — digits, mid-caps
     *   sk-proj-4eC39HqLyjWDarjtT1zdp7dc         redacted — digits, mid-caps
     *
     * The 32-character backstop covers the case this reasoning misses: an
     * all-lowercase opaque token. No English word reaches 32.
     */
    private fun looksLikeSecret(run: String): Boolean {
        if (run.length >= 32) return true
        if (run.any { it.isDigit() } || run.contains('_')) return true
        val head = run.first()
        return run.drop(1).any { it.isUpperCase() } && (head.isLowerCase() || run.drop(1).any { it.isLowerCase() })
    }

    /**
     * Anything after these markers is an argument echo, not a sentence, and is
     * where providers paste back what they were given.
     */
    private val ECHO_MARKERS = listOf("key=", "access_token=", "Bearer ", "Authorization:")

    /** Long enough to carry a real diagnosis, short enough for one banner. */
    private const val MAX_LENGTH = 180

    /**
     * Returns text safe to display, or null when nothing useful survives —
     * in which case the caller should use its own wording rather than show an
     * empty banner.
     */
    fun sanitize(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        var cleaned = text
        for (marker in ECHO_MARKERS) {
            val at = cleaned.indexOf(marker, ignoreCase = true)
            if (at >= 0) cleaned = cleaned.substring(0, at).trimEnd().trimEnd(',', ';', '(', '[')
        }
        cleaned = LONG_RUN.replace(cleaned) { match ->
            if (looksLikeSecret(match.value)) "…" else match.value
        }

        // A message that was ONLY a token carries no diagnosis once redacted.
        val letters = cleaned.count { it.isLetter() }
        if (letters < 8) return null

        if (cleaned.length > MAX_LENGTH) {
            cleaned = cleaned.take(MAX_LENGTH).trimEnd().trimEnd(',', '.', ';') + "…"
        }
        return cleaned.trim().ifEmpty { null }
    }
}
