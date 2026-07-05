package com.classeve.earslate.session

/**
 * Everything the translator runtime needs to configure a session, built from the
 * user's settings + the backend bootstrap response. Immutable — rebuilding the
 * session is how you change policy.
 *
 * The product is ALWAYS a bidirectional conversation translator (there are no
 * "modes"). It runs one translate leg per direction:
 *   - a leg targeting [myLanguage]    → the other person's speech, in my language
 *   - a leg targeting [theirLanguage] → my speech, in the other person's language
 * The translate model auto-detects each speaker's language; with echo OFF a leg
 * stays silent when the input is already its target, so the two legs never talk
 * over each other. When the two languages are the same it collapses to one leg.
 */
data class TranslatorPolicy(
    /** The device user's language. Incoming foreign speech is translated INTO this. */
    val myLanguage: TargetLanguage,
    /** The other person's language. My speech is translated INTO this. Defaults to English. */
    val theirLanguage: TargetLanguage = TargetLanguage.EnglishUS,
    val captionsEnabled: Boolean = true,
    /**
     * Mute my own voice from translation. Off by default. On speaker routes the
     * runtime always half-duplex-gates the mic during playback regardless, to
     * stop the translated audio from being re-ingested.
     */
    val externalOnly: Boolean = false,
    val sessionPolicy: SessionPolicy = SessionPolicy.Default,
)

data class TargetLanguage(
    val displayName: String,
    val bcp47: String,
) {
    companion object {
        val EnglishUS = TargetLanguage(displayName = "English", bcp47 = "en-US")
    }
}

/**
 * Numeric knobs the backend can tune per-user / per-deployment without requiring
 * an app update.
 */
data class SessionPolicy(
    val playbackJitterStartupMs: Int = 60,
    val playbackJitterSteadyMs: Int = 40,
    val sendBatchMs: Int = 100,
) {
    companion object {
        val Default = SessionPolicy()
    }
}
