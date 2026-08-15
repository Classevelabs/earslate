package com.classeve.earslate.session

/**
 * Works out which language a transcript is in, from the text alone.
 *
 * The translate model auto-detects the source language internally but does not
 * tell us what it decided — it only hands back the transcript of what it heard.
 * That transcript is the only signal available for the thing the product needs
 * to know: which language the OTHER person is speaking, so our own speech can be
 * sent back in it without anyone choosing it from a list.
 *
 * Deliberately conservative. Returning null costs one utterance of a wrong
 * outbound language; returning a confident wrong answer costs a socket
 * teardown, a fresh credential on the user's key, and a reconnect — so anything
 * short of a clear winner is reported as "don't know" and the caller keeps what
 * it had.
 */
object LanguageDetector {

    /**
     * @return an app BCP-47 tag from [SupportedLanguages], or null when the text
     *   is too short or too ambiguous to call.
     */
    fun detect(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS) return null
        return byScript(trimmed) ?: byLatinVocabulary(trimmed)
    }

    /**
     * Non-Latin scripts settle the question outright — a run of Devanagari is
     * Hindi and no amount of vocabulary scoring will improve on that. Counted
     * rather than sampled at the first hit, because a single stray glyph (an
     * emoji-adjacent symbol, a borrowed name) must not outvote a whole sentence.
     */
    private fun byScript(text: String): String? {
        var latin = 0
        var cyrillic = 0
        var ukrainian = 0
        var devanagari = 0
        var bengali = 0
        var gurmukhi = 0
        var tamil = 0
        var telugu = 0
        var arabic = 0
        var hebrew = 0
        var greek = 0
        var thai = 0
        var hangul = 0
        var kana = 0
        var han = 0

        for (ch in text) {
            when (ch.code) {
                in 0x0041..0x024F -> latin++
                in 0x0370..0x03FF -> greek++
                in 0x0400..0x04FF -> {
                    cyrillic++
                    if (ch in UKRAINIAN_LETTERS) ukrainian++
                }
                in 0x0590..0x05FF -> hebrew++
                in 0x0600..0x06FF, in 0x0750..0x077F -> arabic++
                in 0x0900..0x097F -> devanagari++
                in 0x0980..0x09FF -> bengali++
                in 0x0A00..0x0A7F -> gurmukhi++
                in 0x0B80..0x0BFF -> tamil++
                in 0x0C00..0x0C7F -> telugu++
                in 0x0E00..0x0E7F -> thai++
                in 0x3040..0x30FF -> kana++
                in 0x3130..0x318F, in 0xAC00..0xD7AF -> hangul++
                in 0x4E00..0x9FFF -> han++
            }
        }

        // Japanese first: Japanese text is mostly han with kana mixed through it,
        // so testing han before kana would call every Japanese sentence Chinese.
        if (kana >= 2) return "ja-JP"
        if (hangul >= 2) return "ko-KR"
        if (han >= 2) return chineseScript(text)

        val script = listOf(
            devanagari to "hi-IN",
            bengali to "bn-IN",
            gurmukhi to "pa-IN",
            tamil to "ta-IN",
            telugu to "te-IN",
            arabic to "ar-SA",
            hebrew to "he-IL",
            greek to "el-GR",
            thai to "th-TH",
        ).maxBy { it.first }

        if (script.first >= 2 && script.first > latin) return script.second
        if (cyrillic >= 2 && cyrillic > latin) {
            return if (ukrainian > 0) "uk-UA" else "ru-RU"
        }
        return null
    }

    private fun chineseScript(text: String): String {
        var simplified = 0
        var traditional = 0
        for (ch in text) {
            if (ch in SIMPLIFIED_MARKERS) simplified++
            if (ch in TRADITIONAL_MARKERS) traditional++
        }
        return if (traditional > simplified) "zh-TW" else "zh-CN"
    }

    /**
     * Latin-script languages need vocabulary. Function words carry the signal:
     * they are short, frequent, and mostly unshared between languages, so a
     * couple of sentences is enough without shipping a model.
     *
     * Diacritics are worth more than a single word because a "ñ" or an "ș" is
     * near-exclusive, but they are still only weighted evidence — Spanish text
     * typed without accents must still resolve, and it does, on its stopwords.
     */
    private fun byLatinVocabulary(text: String): String? {
        val words = text.lowercase().split(NON_LETTER).filter { it.isNotEmpty() }
        if (words.size < MIN_WORDS) return null

        val scores = HashMap<String, Double>(LATIN_STOPWORDS.size)
        for (word in words) {
            for ((tag, stopwords) in LATIN_STOPWORDS) {
                if (word in stopwords) scores[tag] = (scores[tag] ?: 0.0) + 1.0
            }
        }
        for (ch in text.lowercase()) {
            val tag = DIACRITIC_HINTS[ch] ?: continue
            scores[tag] = (scores[tag] ?: 0.0) + DIACRITIC_WEIGHT
        }

        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return null
        if (best.value < MIN_SCORE) return null
        val runnerUp = ranked.getOrNull(1)?.value ?: 0.0
        // A margin, not a plurality. "de" and "la" appear in four of these
        // languages; without a gap the winner is decided by which list happens
        // to be longer.
        if (best.value - runnerUp < MIN_MARGIN) return null
        return best.key
    }

    private const val MIN_CHARS = 6
    private const val MIN_WORDS = 3
    private const val MIN_SCORE = 2.0
    private const val MIN_MARGIN = 1.0
    private const val DIACRITIC_WEIGHT = 1.5

    private val NON_LETTER = Regex("[^\\p{L}]+")

    private val UKRAINIAN_LETTERS = setOf('і', 'ї', 'є', 'ґ', 'І', 'Ї', 'Є', 'Ґ')

    private val SIMPLIFIED_MARKERS =
        "这为们说个时会来对么没东龙学样国湾实点电开关语译认识经过还发头买卖车马".toSet()
    private val TRADITIONAL_MARKERS =
        "這為們說個時會來對麼沒東龍學樣國灣實點電開關語譯認識經過還發頭買賣車馬".toSet()

    private val DIACRITIC_HINTS: Map<Char, String> = buildMap {
        for (c in "ñ¿¡") put(c, "es-ES")
        for (c in "œ") put(c, "fr-FR")
        for (c in "ß") put(c, "de-DE")
        for (c in "ąęłżźćń") put(c, "pl-PL")
        for (c in "ğışİ") put(c, "tr-TR")
        for (c in "ășțî") put(c, "ro-RO")
        for (c in "őű") put(c, "hu-HU")
        for (c in "řěůč") put(c, "cs-CZ")
        for (c in "øå") put(c, "nb-NO")
        for (c in "ơưạếồệ") put(c, "vi-VN")
        for (c in "ã") put(c, "pt-BR")
    }

    /**
     * Function words only. Content words drift with the subject; these do not.
     * Every list is written without accents as well as with, so a transcript
     * that loses its diacritics still lands.
     */
    private val LATIN_STOPWORDS: Map<String, Set<String>> = mapOf(
        "en-US" to setOf(
            "the", "and", "is", "are", "you", "that", "this", "with", "have", "was",
            "for", "not", "but", "what", "how", "they", "there", "would", "about", "just",
        ),
        "es-ES" to setOf(
            "que", "de", "no", "los", "las", "una", "por", "para", "con", "está",
            "esta", "pero", "como", "muy", "también", "tambien", "ustedes", "porque", "hola", "gracias",
        ),
        "fr-FR" to setOf(
            "les", "des", "une", "est", "pas", "vous", "nous", "pour", "avec", "mais",
            "dans", "être", "etre", "cette", "bonjour", "merci", "qui", "sont", "aussi", "beaucoup",
        ),
        "de-DE" to setOf(
            "und", "der", "die", "das", "ist", "nicht", "ein", "eine", "auch", "sich",
            "mit", "für", "fuer", "aber", "wir", "sie", "haben", "sind", "danke", "wenn",
        ),
        "it-IT" to setOf(
            "che", "non", "una", "per", "con", "sono", "come", "questo", "anche", "molto",
            "grazie", "ciao", "perché", "perche", "della", "gli", "essere", "quando", "adesso", "bene",
        ),
        "pt-BR" to setOf(
            "não", "nao", "uma", "para", "com", "você", "voce", "está", "esta", "isso",
            "obrigado", "muito", "mas", "porque", "então", "entao", "também", "tambem", "aqui", "agora",
        ),
        "nl-NL" to setOf(
            "het", "een", "van", "niet", "dat", "zijn", "maar", "ook", "voor", "met",
            "heb", "wij", "jij", "goed", "dank", "hoe", "waar", "omdat", "nog", "alleen",
        ),
        "pl-PL" to setOf(
            "nie", "jest", "sie", "się", "tak", "jak", "tego", "który", "ktory", "bardzo",
            "dziękuję", "dziekuje", "czy", "ale", "tylko", "wszystko", "teraz", "dobrze", "mam", "jestem",
        ),
        "tr-TR" to setOf(
            "bir", "bu", "ve", "için", "icin", "ama", "çok", "cok", "değil", "degil",
            "teşekkür", "tesekkur", "merhaba", "nasıl", "nasil", "evet", "hayır", "hayir", "ben", "sen",
        ),
        "vi-VN" to setOf(
            "không", "khong", "của", "cua", "được", "duoc", "một", "mot", "này", "nay",
            "người", "nguoi", "cảm", "cam", "bạn", "ban", "rất", "rat", "chúng", "chung",
        ),
        "id-ID" to setOf(
            "yang", "dan", "tidak", "untuk", "dengan", "ini", "itu", "saya", "kamu", "adalah",
            "sudah", "bisa", "juga", "terima", "kasih", "sangat", "kalau", "karena", "akan", "dari",
        ),
        "ms-MY" to setOf(
            "yang", "dan", "tidak", "untuk", "dengan", "ini", "itu", "saya", "awak", "adalah",
            "sudah", "boleh", "juga", "terima", "kasih", "sangat", "kalau", "kerana", "akan", "daripada",
        ),
        "fil-PH" to setOf(
            "ang", "ng", "sa", "mga", "ako", "ikaw", "hindi", "ito", "yung", "para",
            "salamat", "kasi", "naman", "talaga", "pero", "kung", "may", "wala", "ganun", "tayo",
        ),
        "sv-SE" to setOf(
            "och", "att", "det", "som", "inte", "för", "for", "med", "jag", "har",
            "tack", "men", "vad", "hur", "här", "har", "kan", "vill", "mycket", "också",
        ),
        "nb-NO" to setOf(
            "og", "det", "ikke", "som", "til", "med", "jeg", "har", "kan", "vil",
            "takk", "men", "hva", "hvordan", "her", "veldig", "også", "ogsa", "være", "vaere",
        ),
        "da-DK" to setOf(
            "og", "det", "ikke", "som", "til", "med", "jeg", "har", "kan", "vil",
            "tak", "men", "hvad", "hvordan", "her", "meget", "også", "ogsa", "være", "vaere",
        ),
        "fi-FI" to setOf(
            "ja", "on", "ei", "että", "etta", "se", "kuin", "mutta", "kiitos", "hyvä",
            "hyva", "minä", "mina", "sinä", "sina", "voi", "niin", "olen", "tämä", "tama",
        ),
        "cs-CZ" to setOf(
            "je", "na", "se", "že", "ze", "ale", "jak", "tak", "děkuji", "dekuji",
            "ano", "ne", "jsem", "jsou", "velmi", "protože", "protoze", "tady", "teď", "ted",
        ),
        "ro-RO" to setOf(
            "și", "si", "este", "nu", "care", "pentru", "cu", "din", "mulțumesc", "multumesc",
            "dar", "foarte", "aici", "acum", "sunt", "ceva", "cum", "când", "cand", "bine",
        ),
        "hu-HU" to setOf(
            "és", "es", "nem", "hogy", "egy", "van", "meg", "köszönöm", "koszonom", "igen",
            "csak", "már", "mar", "vagy", "mit", "hogyan", "itt", "most", "nagyon", "vagyok",
        ),
    )
}
