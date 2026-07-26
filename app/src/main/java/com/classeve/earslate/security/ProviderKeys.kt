package com.classeve.earslate.security

import android.content.Context
import com.classeve.earslate.session.TranslationProvider

/**
 * Which providers the user has supplied a key for, and a deliberately shallow
 * check on what they pasted.
 *
 * **We do not validate key formats, and must not start.** An earlier version of
 * this screen refused anything that did not begin with `AIza` for Gemini or
 * `sk-` for OpenAI. Google then changed what its keys look like, and the app
 * rejected perfectly good keys with a confident, wrong error message — the user
 * could not get past setup at all. A provider can change its key format
 * whenever it likes, and a hardcoded allowlist turns that into an app that is
 * broken until it ships an update.
 *
 * So the only things rejected here are mistakes that are *definitely* mistakes
 * regardless of format: nothing pasted, a URL, a leftover "Bearer " prefix,
 * embedded whitespace. Everything else goes to the provider, because the
 * provider is the only thing that actually knows. [ProviderKeyVerifier] mints a
 * real session before any key is saved, so a bad key still fails at setup —
 * just with the provider's verdict instead of our guess.
 *
 * [prefix] survives only as a *hint*: it seeds the placeholder text and lets
 * [detect] guess which provider a pasted key belongs to. It never blocks.
 */
enum class KeyProvider(
    val provider: TranslationProvider,
    val displayName: String,
    /** Historical prefix. A display and detection hint only — never a gate. */
    val prefix: String,
    val consoleName: String,
    val consoleUrl: String,
) {
    GEMINI(
        provider = TranslationProvider.GEMINI,
        displayName = "Google Gemini",
        prefix = "AIza",
        consoleName = "Google AI Studio",
        consoleUrl = "https://aistudio.google.com/apikey",
    ),
    OPENAI(
        provider = TranslationProvider.OPENAI,
        displayName = "OpenAI",
        prefix = "sk-",
        consoleName = "the OpenAI dashboard",
        consoleUrl = "https://platform.openai.com/api-keys",
    );

    /** Storage name. Stable — changing it strands the user's saved key. */
    val vaultEntry: String get() = "api_key_${provider.wireValue}"

    val placeholder: String get() = "Paste your $displayName key"

    /**
     * Returns null when [candidate] is worth sending to the provider, or a
     * sentence naming a definite mistake when it is not.
     *
     * Only unambiguous problems are rejected. Anything that could conceivably
     * be a key — whatever it starts with, whatever length — is passed through
     * to live verification, because the provider decides, not us.
     */
    fun rejectionReason(candidate: String): String? {
        val key = candidate.trim()
        return when {
            key.isEmpty() ->
                "Paste your $displayName key first — it's the value $consoleName showed you."

            key.startsWith("http://", ignoreCase = true) ||
                key.startsWith("https://", ignoreCase = true) ->
                "That's a web address, not a key. Open it, then copy the key itself."

            key.startsWith("Bearer ", ignoreCase = true) ->
                "Remove the word “Bearer ” from the front — paste only the key."

            key.any { it.isWhitespace() } ->
                "That key has a space or line break in it. Copy it again without the surrounding text."

            // Nothing real is this short. Anything longer goes to the provider.
            key.length < 8 ->
                "That looks too short to be a key. Copy the whole value from $consoleName."

            else -> null
        }
    }

    fun isPlausible(candidate: String): Boolean = rejectionReason(candidate) == null

    /**
     * A non-blocking observation for the UI: true when the key looks like it
     * might belong to the *other* provider. The user is shown a note and can
     * ignore it — a hint, never a refusal.
     */
    fun looksLikeAnotherProvider(candidate: String): KeyProvider? {
        val key = candidate.trim()
        if (key.isEmpty() || key.startsWith(prefix)) return null
        return entries.firstOrNull { it != this && key.startsWith(it.prefix) }
    }

    companion object {
        fun forProvider(provider: TranslationProvider): KeyProvider? =
            entries.firstOrNull { it.provider == provider }

        /** Best guess at which provider a pasted key belongs to. */
        fun detect(candidate: String): KeyProvider? {
            val key = candidate.trim()
            return entries.firstOrNull { key.startsWith(it.prefix) }
        }
    }
}

/**
 * The user's provider keys. Thin, deliberately: it owns *which* keys exist and
 * nothing about how they are encrypted — that is [KeyVault]'s job.
 */
class ProviderKeyStore(context: Context) {

    private val vault = KeyVault(context)

    fun key(of: KeyProvider): String? = vault.get(of.vaultEntry)?.takeIf { it.isNotBlank() }

    fun has(of: KeyProvider): Boolean = key(of) != null

    fun save(of: KeyProvider, key: String) {
        vault.put(of.vaultEntry, key.trim())
    }

    fun forget(of: KeyProvider) {
        vault.remove(of.vaultEntry)
    }

    fun configured(): List<KeyProvider> = KeyProvider.entries.filter { has(it) }

    fun hasAnyKey(): Boolean = KeyProvider.entries.any { has(it) }

    /**
     * The provider a session should actually use, given what the user picked
     * and which keys exist. Returns null when nothing is usable.
     *
     * "Automatic" prefers Gemini because it is the only provider that runs both
     * translation directions; OpenAI's translation endpoint has a single output
     * language and no echo suppression, so it is one-directional by design.
     */
    fun resolve(preference: TranslationProvider): KeyProvider? {
        KeyProvider.forProvider(preference)?.let { explicit ->
            return explicit.takeIf { has(it) }
        }
        return KeyProvider.entries.firstOrNull { has(it) }
    }

    /**
     * True when the platform destroyed the encryption key, which happens when
     * device credentials are removed. Saved keys are gone and must be re-entered.
     */
    fun wasResetByKeystore(): Boolean = vault.wasResetByKeystore

    fun acknowledgeKeystoreReset() = vault.acknowledgeKeystoreReset()

    /** Masked form for display. Never render a whole key back to the screen. */
    fun masked(of: KeyProvider): String? {
        val key = key(of) ?: return null
        if (key.length <= 10) return of.prefix + "…"
        return key.take(6) + "…" + key.takeLast(4)
    }
}
