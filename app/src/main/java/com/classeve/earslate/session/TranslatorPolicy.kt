package com.classeve.earslate.session

import java.util.Locale

/**
 * What a session is built from. Immutable.
 *
 * The product is a two-way conversation translator, run as one translate
 * session per direction: one speaks [myLanguage] for what the other person
 * says, the other speaks their language for what I say.
 */
data class TranslatorPolicy(
    /** The device user's language. Everything else heard is translated INTO this. */
    val myLanguage: TargetLanguage,
    /**
     * The other person's language, or null to learn it by listening. Null
     * starts on English and follows whatever the other person is heard
     * speaking; a value aims "me → them" from the first word.
     */
    val otherLanguage: TargetLanguage? = null,
    /** The provider the user chose, or null to use whichever one has a key. */
    val provider: TranslationProvider? = null,
)

enum class TranslationProvider(val wireValue: String, val displayName: String) {
    GEMINI("gemini", "Gemini"),
    OPENAI("openai", "OpenAI");

    companion object {
        /** Null for anything else, including the retired "auto". */
        fun fromWireValue(value: String?): TranslationProvider? =
            entries.firstOrNull { it.wireValue == value }
    }
}

data class TargetLanguage(
    val displayName: String,
    val bcp47: String,
) {
    companion object {
        val EnglishUS = TargetLanguage(displayName = "English", bcp47 = "en-US")

        /**
         * The language behind [code], which may be one the pickers do not list:
         * the other person can speak anything the model understands.
         */
        fun forCode(code: String): TargetLanguage {
            val primary = code.trim().substringBefore('-')
            (SupportedLanguages.firstOrNull { it.bcp47.substringBefore('-').equals(primary, ignoreCase = true) }
                ?: SupportedLanguages.firstOrNull { HeardLanguageTracker.sameLanguage(it.bcp47, code) })
                ?.let { return it }
            val locale = Locale.forLanguageTag(code)
            val name = locale.getDisplayLanguage(locale).ifBlank { code }
            return TargetLanguage(name.replaceFirstChar { it.titlecase(locale) }, code)
        }
    }
}
