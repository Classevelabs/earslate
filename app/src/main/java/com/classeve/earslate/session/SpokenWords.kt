package com.classeve.earslate.session

import java.text.Normalizer

/**
 * The words of a transcript, in a form that can be compared with another
 * transcript of the same speech.
 *
 * A translate session that takes my Punjabi for Hindi "translates" it into
 * Punjabi: it says my own words back, and writes what it heard in the other
 * script. Whether it is repeating can only be read from the words, so case,
 * accents and vowel marks are dropped, and the nine Indic scripts from
 * Devanagari to Malayalam, which Unicode lays out letter for letter in the
 * same order, are read as one.
 */
object SpokenWords {

    /**
     * Text as it stands while it is still arriving in pieces: the words that
     * are finished, and a last one that may still be growing.
     */
    class Split(val whole: List<String>, val unfinished: String?)

    fun of(text: CharSequence): List<String> = split(text).let { it.whole + listOfNotNull(it.unfinished) }

    fun split(text: CharSequence): Split {
        val words = ArrayList<String>()
        val word = StringBuilder()
        var open = false
        val letters = Normalizer.normalize(text, Normalizer.Form.NFKD)
        var at = 0
        while (at < letters.length) {
            val point = letters.codePointAt(at)
            at += Character.charCount(point)
            when (Character.getType(point).toByte()) {
                Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> Unit
                else -> if (Character.isLetterOrDigit(point)) {
                    word.appendCodePoint(if (point in INDIC) FOLDED + (point and 0x7F) else Character.toLowerCase(point))
                    open = true
                } else {
                    if (word.isNotEmpty()) words += word.toString()
                    word.setLength(0)
                    open = false
                }
            }
        }
        return Split(words, word.toString().takeIf { open })
    }

    private val INDIC = 0x0900..0x0D7F

    // A private-use block: no real letter can be mistaken for a folded one.
    private const val FOLDED = 0xE000
}

/**
 * What a session has written down of the speech it hears, each piece with the
 * moment it arrived.
 */
class HeardSpeech {
    private val pieces = ArrayDeque<Pair<Long, String>>()

    fun add(fragment: String, nowMs: Long) {
        pieces.addLast(nowMs to fragment)
        while (pieces.first().first < nowMs - KEPT_MS) pieces.removeFirst()
    }

    /** The words heard from [sinceMs] on. */
    fun wordsSince(sinceMs: Long): Set<String> {
        val text = StringBuilder()
        // Joined as they came: a provider may cut a word across two fragments.
        for ((at, fragment) in pieces) if (at >= sinceMs) text.append(fragment)
        return SpokenWords.of(text).toHashSet()
    }

    fun clear() = pieces.clear()

    private companion object {
        /** Longer than any one utterance is answered for. */
        const val KEPT_MS = 60_000L
    }
}
