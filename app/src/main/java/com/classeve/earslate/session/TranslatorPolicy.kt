package com.classeve.earslate.session

/**
 * Everything the translator runtime needs to configure a session, built from the
 * user's settings and the session the app mints on the device. Immutable —
 * rebuilding the session is how you change policy.
 *
 * The product is a bidirectional conversation translator. It runs one translate
 * leg per direction:
 *   - a leg targeting [myLanguage] → the other person's speech, in my language
 *   - a second leg → my speech, in the other person's language
 * The translate model auto-detects each speaker's language; with echo OFF a leg
 * stays silent when the input is already its target, so the two legs never talk
 * over each other. When the two languages are the same it collapses to one leg.
 */
data class TranslatorPolicy(
    /** The device user's language. Incoming foreign speech is translated INTO this. */
    val myLanguage: TargetLanguage,
    /**
     * The other person's language — where MY speech is translated to.
     *
     * Null means Automatic: the session starts on English and re-aims the
     * outbound leg to whatever the other person is actually heard speaking. That
     * is the default and the no-setup path. But it has a hard limit — the app
     * cannot translate MY speech into a language it has not heard yet, so if I
     * speak first, my words have no correct target until the other person has
     * spoken. Setting this pins the outbound direction from the first frame, so
     * "me → them" works immediately and never depends on detection.
     */
    val otherLanguage: TargetLanguage? = null,
    /**
     * Always on. Not a user setting: the source transcript captions turn on is
     * what the automatic language detection reads, so it is part of the product,
     * not a toggle. It stays a field because a Gemini ephemeral token LOCKS the
     * session config and transcription is part of that config — the minter and
     * the setup frame must be built from one identical value. See
     * `GeminiSessionSetupParityTest`.
     */
    val captionsEnabled: Boolean = true,
    val provider: TranslationProvider = TranslationProvider.GEMINI,
)

/**
 * The translation backend. One entry today — the app talks only to Gemini Live
 * Translate. Kept as an enum so the socket protocol, minter, and bootstrap keep
 * a single typed seam if another backend is ever added; the wire value is what a
 * minted credential is tagged with in logs.
 */
enum class TranslationProvider(val wireValue: String, val displayName: String) {
    GEMINI("gemini", "Gemini"),
}

data class TargetLanguage(
    val displayName: String,
    val bcp47: String,
) {
    companion object {
        val EnglishUS = TargetLanguage(displayName = "English", bcp47 = "en-US")
    }
}
