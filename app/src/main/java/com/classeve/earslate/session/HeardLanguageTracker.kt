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
        /** Regional variants are one language on the wire. */
        fun sameLanguage(a: String, b: String): Boolean =
            a.isNotEmpty() && a.substringBefore('-').equals(b.substringBefore('-'), ignoreCase = true)
    }
}

/**
 * Names the language of a transcript from its words, for a provider that
 * gives words and no language. Judges the last few words only: after a change
 * of speaker the old language would otherwise outvote the new one.
 */
class RecentWords {
    private val words = ArrayDeque<String>()

    /** The language of what was just said, or null while there is too little to tell. */
    fun observe(fragment: String): String? {
        fragment.split(WHITESPACE).filterTo(words) { it.isNotEmpty() }
        while (words.size > WINDOW) words.removeFirst()
        return LanguageDetector.detect(words.joinToString(" "))
    }

    /** Nobody has spoken for a while; what comes next is judged on its own words. */
    fun clear() = words.clear()

    private companion object {
        const val WINDOW = 12
        val WHITESPACE = Regex("\\s+")
    }
}
