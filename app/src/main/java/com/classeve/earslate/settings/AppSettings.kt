package com.classeve.earslate.settings

import com.classeve.earslate.session.OutputStyle
import com.classeve.earslate.session.RuntimeMode
import com.classeve.earslate.session.SessionPolicy
import com.classeve.earslate.session.TargetLanguage
import com.classeve.earslate.session.TranslatorPolicy

/**
 * SUPERSEDED by [SettingsRepository] for persisted user settings.
 * Kept only for [defaultPolicy] which serves as a fallback when
 * the DataStore has not yet emitted.
 */
object AppSettings {

    val defaultPolicy: TranslatorPolicy = TranslatorPolicy(
        targetLanguage = TargetLanguage.EnglishUS,
        mode = RuntimeMode.LISTEN,
        captionsEnabled = true,
        voiceName = null,
        outputStyle = OutputStyle.NEUTRAL,
        sessionPolicy = SessionPolicy.Default,
    )
}
