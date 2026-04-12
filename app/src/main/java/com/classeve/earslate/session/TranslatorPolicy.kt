package com.classeve.earslate.session

/**
 * Everything the translator runtime needs to configure a session, built from the
 * user's settings + the backend bootstrap response. Immutable — rebuilding the
 * session is how you change policy.
 *
 * Blueprint §10 / §24.
 */
data class TranslatorPolicy(
    val targetLanguage: TargetLanguage,
    val secondaryLanguage: TargetLanguage? = null,
    val mode: RuntimeMode,
    val captionsEnabled: Boolean,
    val voiceName: String?,
    val outputStyle: OutputStyle,
    val externalOnly: Boolean = false,
    val sessionPolicy: SessionPolicy,
)

data class TargetLanguage(
    val displayName: String,
    val bcp47: String,
) {
    companion object {
        val EnglishUS = TargetLanguage(displayName = "English", bcp47 = "en-US")
    }
}

enum class RuntimeMode { LISTEN, CONVERSATION, TRANSCRIPT }

enum class OutputStyle { NEUTRAL, FORMAL, CASUAL }

/**
 * Numeric knobs the backend can tune per-user / per-deployment without requiring
 * an app update. Defaults come from Blueprint §8.5 / §8.7.
 */
data class SessionPolicy(
    val enableCompression: Boolean = true,
    val enableResumption: Boolean = true,
    val playbackJitterStartupMs: Int = 120,
    val playbackJitterSteadyMs: Int = 60,
    val sendBatchMs: Int = 80,
    val vadTrailingSilenceMs: Int = 240,
    val vadPreRollMs: Int = 240,
    val vadMinUtteranceMs: Int = 120,
) {
    companion object {
        val Default = SessionPolicy()
    }
}
