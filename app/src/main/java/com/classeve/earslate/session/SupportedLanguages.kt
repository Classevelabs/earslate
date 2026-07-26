package com.classeve.earslate.session

/**
 * Languages offered in the pickers. Not a gate — the translate models handle
 * more than this; the list is the curated shortlist shown in the UI, ordered by
 * rough global demand.
 *
 * Entries are app-level BCP-47 tags. They are normalised to the provider's
 * `targetLanguageCode` form by `LiveSessionConfigFactory.translateCodeFor`
 * before they go on the wire — do not send these tags to a provider directly.
 */
val SupportedLanguages: List<TargetLanguage> = listOf(
    TargetLanguage("English", "en-US"),
    TargetLanguage("English (UK)", "en-GB"),
    TargetLanguage("Español", "es-ES"),
    TargetLanguage("Français", "fr-FR"),
    TargetLanguage("Deutsch", "de-DE"),
    TargetLanguage("Italiano", "it-IT"),
    TargetLanguage("Português (BR)", "pt-BR"),
    TargetLanguage("Nederlands", "nl-NL"),
    TargetLanguage("Polski", "pl-PL"),
    TargetLanguage("Русский", "ru-RU"),
    TargetLanguage("Українська", "uk-UA"),
    TargetLanguage("Türkçe", "tr-TR"),
    TargetLanguage("العربية", "ar-SA"),
    TargetLanguage("עברית", "he-IL"),
    TargetLanguage("हिन्दी", "hi-IN"),
    TargetLanguage("বাংলা", "bn-IN"),
    TargetLanguage("தமிழ்", "ta-IN"),
    TargetLanguage("తెలుగు", "te-IN"),
    TargetLanguage("ਪੰਜਾਬੀ", "pa-IN"),
    TargetLanguage("中文 (简体)", "zh-CN"),
    TargetLanguage("中文 (繁體)", "zh-TW"),
    TargetLanguage("日本語", "ja-JP"),
    TargetLanguage("한국어", "ko-KR"),
    TargetLanguage("Tiếng Việt", "vi-VN"),
    TargetLanguage("ภาษาไทย", "th-TH"),
    TargetLanguage("Bahasa Indonesia", "id-ID"),
    TargetLanguage("Bahasa Melayu", "ms-MY"),
    TargetLanguage("Filipino", "fil-PH"),
    TargetLanguage("Svenska", "sv-SE"),
    TargetLanguage("Norsk", "nb-NO"),
    TargetLanguage("Dansk", "da-DK"),
    TargetLanguage("Suomi", "fi-FI"),
    TargetLanguage("Čeština", "cs-CZ"),
    TargetLanguage("Ελληνικά", "el-GR"),
    TargetLanguage("Română", "ro-RO"),
    TargetLanguage("Magyar", "hu-HU"),
)
