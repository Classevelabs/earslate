package com.classeve.earslate.security

import android.content.Context
import com.classeve.earslate.session.TranslationProvider

/**
 * Which providers the user has supplied a key for, and the rules for deciding
 * whether a pasted string is plausibly a key before we spend a network round
 * trip on it.
 *
 * Format checks exist to give an instant, specific answer to the most common
 * paste mistakes — the console URL instead of the key, the key with a "Bearer "
 * prefix still attached, a truncated copy. They are not a substitute for
 * verification: only [ProviderKeyVerifier] can tell you a key actually works,
 * and every key is verified against the provider before it is stored.
 */
enum class KeyProvider(
    val provider: TranslationProvider,
    val displayName: String,
    val prefix: String,
    /** Shortest credible length, used only to catch truncated pastes. */
    private val minLength: Int,
    val consoleName: String,
    val consoleUrl: String,
) {
    GEMINI(
        provider = TranslationProvider.GEMINI,
        displayName = "Google Gemini",
        prefix = "AIza",
        minLength = 35,
        consoleName = "Google AI Studio",
        consoleUrl = "https://aistudio.google.com/apikey",
    ),
    OPENAI(
        provider = TranslationProvider.OPENAI,
        displayName = "OpenAI",
        prefix = "sk-",
        minLength = 20,
        consoleName = "the OpenAI dashboard",
        consoleUrl = "https://platform.openai.com/api-keys",
    );

    /** Storage name. Stable — changing it strands the user's saved key. */
    val vaultEntry: String get() = "api_key_${provider.wireValue}"

    val placeholder: String get() = "$prefix…"

    /**
     * Returns null when [candidate] looks like a key of this kind, or a
     * sentence naming the specific problem when it does not. Written to be
     * read by someone who has just failed at this, so each message says what
     * to do next rather than restating the rule.
     */
    fun rejectionReason(candidate: String): String? {
        val key = candidate.trim()
        return when {
            key.isEmpty() ->
                "Paste your $displayName key first — it's the value $consoleName showed you."

            key.startsWith("http://") || key.startsWith("https://") ->
                "That's a web address, not a key. Open it, then copy the key itself."

            key.startsWith("Bearer ", ignoreCase = true) ->
                "Remove the word “Bearer ” from the front — paste only the key."

            key.any { it.isWhitespace() } ->
                "That key has a space or line break in it. Copy it again without the surrounding text."

            !key.startsWith(prefix) -> {
                val other = entries.firstOrNull { it != this && key.startsWith(it.prefix) }
                if (other != null) {
                    "That looks like ${other.displayName} key, not $displayName. " +
                        "Switch the provider above, or paste your $displayName key."
                } else {
                    "That doesn't look like a $displayName key. They start with “$prefix” — " +
                        "make sure you copied the key itself, not the page address."
                }
            }

            key.length < minLength ->
                "That key looks cut off. Copy the whole value from $consoleName."

            else -> null
        }
    }

    fun isPlausible(candidate: String): Boolean = rejectionReason(candidate) == null

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
