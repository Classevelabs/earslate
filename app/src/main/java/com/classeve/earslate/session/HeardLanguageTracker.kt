package com.classeve.earslate.session

/**
 * Follows which language the other person speaks, so that what I say can be
 * sent back to them in it.
 *
 * The microphone hears both people. Anything in [myLanguage] is me; a language
 * that is neither mine nor the one they are known to speak is put on [offer],
 * and becomes theirs only when whoever is listening [accept]s it: the model's
 * name for a language is not proof that somebody new is speaking it.
 */
class HeardLanguageTracker(
    private val myLanguage: String,
    initialTheirs: String?,
) {

    /** [language] has been heard since [sinceMs] and may be what they speak now. */
    class Offer(val language: String, val sinceMs: Long)

    /** The language the other person is being answered in; null until one has been heard. */
    var current: String? = initialTheirs
        private set

    private var pending: Offer? = null
    private var heard = 0
    private var beganSpeech = false
    private var lastHeardAtMs = 0L

    // A language is on offer at once when it is the first of theirs, or when
    // somebody began to speak in it. In the middle of speech it has to be said
    // twice: one misheard fragment must not send my next sentence out in the
    // wrong language.
    val offer: Offer? get() = pending?.takeIf { current == null || beganSpeech || heard >= 2 }

    /**
     * The session listening for the other person heard [language].
     * @param beginsSpeech true for the first words after a silence.
     */
    fun heard(language: String, nowMs: Long, beginsSpeech: Boolean = false) {
        if (isMine(language) || isTheirs(language)) return withdraw()
        if (pending?.let { sameLanguage(it.language, language) } != true) {
            pending = Offer(language, nowMs)
            heard = 0
            beganSpeech = beginsSpeech
        }
        heard++
        lastHeardAtMs = nowMs
    }

    /** @return the language that is theirs from now on, or null when none was on offer. */
    fun accept(): String? {
        val language = offer?.language ?: return null
        withdraw()
        current = language
        return language
    }

    /** What was on offer turned out to be one of the languages already in use, misheard. */
    fun withdraw() {
        pending = null
        heard = 0
        beganSpeech = false
    }

    /** An offer nobody took up while its speech was being translated belongs to no one. */
    fun expire(nowMs: Long) {
        if (pending != null && nowMs - lastHeardAtMs > OFFER_TTL_MS) withdraw()
    }

    fun isMine(language: String) = sameLanguage(language, myLanguage)

    fun isTheirs(language: String) = current?.let { sameLanguage(language, it) } == true

    companion object {
        /** Longer than a translation takes to begin after the words it translates. */
        const val OFFER_TTL_MS = 6_000L

        /** Regional variants are one language, and so are the names one language goes by. */
        fun sameLanguage(a: String, b: String): Boolean = a.isNotEmpty() && nameOf(a) == nameOf(b)

        private fun nameOf(tag: String): String =
            tag.trim().substringBefore('-').lowercase().let { OTHER_NAMES[it] ?: it }

        // The translate model reports Norwegian as "no" and Filipino as "tl".
        // The pickers say nb and fil.
        private val OTHER_NAMES = mapOf("no" to "nb", "tl" to "fil")
    }
}

/**
 * Names the language of a transcript from its words, for a provider that
 * gives words and no language. Judges the last few words only: after a change
 * of speaker the old language would otherwise outvote the new one.
 */
class RecentWords {
    private val text = StringBuilder()

    /** The language of what was just said, or null while there is too little to tell. */
    fun observe(fragment: String): String? {
        // Joined exactly as it came: a provider may cut a word across two
        // fragments, and two halves of a word are not two words.
        text.append(fragment)
        val words = text.trim().split(WHITESPACE)
        if (words.size > WINDOW) {
            val midWord = !text.last().isWhitespace()
            text.setLength(0)
            text.append(words.takeLast(WINDOW).joinToString(" "))
            if (!midWord) text.append(' ')
        }
        return LanguageDetector.detect(text.toString().trim())
    }

    /** Nobody has spoken for a while; what comes next is judged on its own words. */
    fun clear() = text.setLength(0)

    private companion object {
        const val WINDOW = 12
        val WHITESPACE = Regex("\\s+")
    }
}
