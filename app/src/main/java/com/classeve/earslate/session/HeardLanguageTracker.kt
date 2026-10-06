package com.classeve.earslate.session

/**
 * Follows which language the other person speaks, so that what I say can be
 * sent back to them in it.
 *
 * The microphone hears both people. Anything in [myLanguage] is me; everything
 * else is them.
 */
class HeardLanguageTracker(
    private val myLanguage: String,
    initialTheirs: String,
) {

    sealed interface Heard {
        data object Me : Heard

        /** [changed] is true when [language] is not what I was being answered back in until now. */
        data class Them(val language: String, val changed: Boolean) : Heard
    }

    /** The language the other person is being answered in. */
    var current: String = initialTheirs
        private set

    private var heardThem = false
    private var candidate: String? = null

    fun report(language: String): Heard {
        if (sameLanguage(language, myLanguage)) {
            candidate = null
            return Heard.Me
        }
        if (heardThem && sameLanguage(language, current)) {
            candidate = null
            return Heard.Them(current, changed = false)
        }
        // The first language heard replaces a starting guess at once. Moving
        // off a language that WAS heard needs it said twice: one misheard
        // fragment must not send my next sentence out in the wrong language.
        if (heardThem && !sameLanguage(candidate.orEmpty(), language)) {
            candidate = language
            return Heard.Them(current, changed = false)
        }
        val changed = !sameLanguage(language, current)
        heardThem = true
        candidate = null
        current = language
        return Heard.Them(language, changed)
    }

    companion object {
        /** Regional variants are one language, and so are the names one language goes by. */
        fun sameLanguage(a: String, b: String): Boolean = a.isNotEmpty() && nameOf(a) == nameOf(b)

        private fun nameOf(tag: String): String =
            tag.trim().substringBefore('-').lowercase().let { OTHER_NAMES[it] ?: it }

        // The translate model reports Norwegian as "no" and Filipino as "tl", and
        // hears Malay as Indonesian. The pickers say nb, fil, ms and id.
        private val OTHER_NAMES = mapOf("no" to "nb", "tl" to "fil", "ms" to "id")
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
