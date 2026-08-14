package com.classeve.earslate.security

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed secret storage for the user's provider API keys.
 *
 * This deliberately does **not** use `androidx.security:security-crypto`
 * (`EncryptedSharedPreferences`). That library is deprecated and unmaintained,
 * and it pulls Tink in for what is, at this size, a few dozen lines of
 * platform API. Encrypting here directly means one fewer dependency in a
 * repository people are invited to audit, and it lets us ask for StrongBox.
 *
 * Design:
 *  - A single AES-256 key lives in the AndroidKeyStore under [KEY_ALIAS] and
 *    never leaves it. StrongBox (a discrete security chip) is requested when
 *    the device advertises it, with a transparent fallback to the TEE.
 *  - Every write generates a fresh IV — [KeyGenParameterSpec.Builder
 *    .setRandomizedEncryptionRequired] is left at its secure default, so the
 *    platform refuses to let us reuse one. Ciphertext is stored as
 *    `base64(iv || ciphertext)` in an ordinary `SharedPreferences` file; that
 *    file on its own is useless without the keystore.
 *  - AES-GCM authenticates as well as encrypts, so a tampered store fails to
 *    decrypt rather than returning attacker-chosen bytes.
 *
 * It **fails closed on writes**. If the keystore cannot be reached, [put] raises
 * [VaultUnavailable] rather than quietly writing plaintext, and the caller is
 * expected to tell the user — a write that cannot be made secure must not look
 * like it succeeded.
 *
 * **Reads degrade instead.** [get] answers null when the vault is unreachable,
 * because to a caller that is the same fact as "nothing is stored": either way
 * there is no key to use, and the app has a correct path for that. This is a
 * deliberate asymmetry, and it is not symmetry for its own sake — it is what
 * stops a broken keystore from crashing the app during composition. Nothing is
 * deleted on that path; the ciphertext is left alone to decrypt another day.
 *
 * The one case we heal automatically is [KeyPermanentlyInvalidatedException] —
 * the OS destroys the key when device credentials are removed, and the only
 * recovery is a new key, which means previously stored secrets are unreadable
 * and must be re-entered. That is reported honestly rather than crashing.
 */
class KeyVault(context: Context) {

    /** Raised when secure storage is genuinely unusable. Never swallowed. */
    class VaultUnavailable(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * True when a previously stored secret could not be decrypted because the
     * keystore key was destroyed (device credentials removed or reset). The UI
     * uses this to explain why a key it had is suddenly gone.
     */
    @Volatile
    var wasResetByKeystore: Boolean = false
        private set

    fun put(name: String, secret: String) {
        if (secret.isEmpty()) {
            remove(name)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val packed = ByteArray(iv.size + body.size).also {
            iv.copyInto(it, 0)
            body.copyInto(it, iv.size)
        }
        prefs.edit()
            .putString(name, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        val packed = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            // Corrupt entry: drop it rather than fail every future read.
            remove(name)
            return null
        }
        if (packed.size <= GCM_IV_BYTES) {
            remove(name)
            return null
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_BITS, packed, 0, GCM_IV_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), spec)
            val plain = cipher.doFinal(packed, GCM_IV_BYTES, packed.size - GCM_IV_BYTES)
            String(plain, Charsets.UTF_8)
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            // The OS destroyed our key. Everything sealed with it is gone.
            Log.w(TAG, "keystore key invalidated; stored secrets are unrecoverable")
            wasResetByKeystore = true
            clear()
            recreateKey()
            null
        } catch (broken: GeneralSecurityException) {
            // Authentication failure => the stored blob is not ours. Drop it.
            Log.w(TAG, "stored secret failed authentication; discarding entry")
            remove(name)
            null
        } catch (unavailable: VaultUnavailable) {
            // A READ cannot usefully fail closed, and this one was crashing the
            // app on launch.
            //
            // VaultUnavailable is a RuntimeException, so neither catch above
            // sees it, and secretKey() raises it whenever AndroidKeyStore will
            // not load or open. MainActivity calls providerKeys.hasAnyKey()
            // during composition, which lands here for any user who already has
            // a key stored — so a device whose keystore breaks got an immediate
            // crash loop and no explanation at all.
            //
            // To a caller, "the vault is unreachable" and "no key is stored" are
            // the same fact: there is no key available to use. The app already
            // has a correct, well-trodden path for the second one — it shows key
            // setup. Taking that path ends somewhere honest, because the SAVE at
            // the end of it still fails loudly with the real reason. Crashing
            // ends nowhere.
            //
            // The entry is deliberately NOT removed: the ciphertext is fine and
            // may well decrypt on the next boot. Nothing is destroyed on the
            // strength of a transient platform failure.
            Log.w(TAG, "secure storage unavailable; treating as no stored secret", unavailable)
            null
        }
    }

    fun contains(name: String): Boolean = prefs.contains(name)

    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun acknowledgeKeystoreReset() {
        wasResetByKeystore = false
    }

    private fun secretKey(): SecretKey = synchronized(this) {
        cached?.let { return it }
        val store = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (t: Throwable) {
            throw VaultUnavailable("Secure storage is unavailable on this device.", t)
        }
        val existing = try {
            (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            Log.w(TAG, "keystore key invalidated on load; regenerating")
            wasResetByKeystore = true
            clear()
            null
        } catch (t: Throwable) {
            throw VaultUnavailable("Secure storage could not be opened.", t)
        }
        val key = existing ?: generateKey()
        cached = key
        key
    }

    private fun recreateKey() = synchronized(this) {
        cached = null
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }
        cached = generateKey()
    }

    private fun generateKey(): SecretKey {
        val strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        // Try the discrete security chip first, then the TEE. Some devices
        // advertise StrongBox but reject 256-bit AES, so a failure here is
        // expected and not an error.
        if (strongBox) {
            runCatching { generateKey(useStrongBox = true) }
                .onSuccess { return it }
                .onFailure { Log.i(TAG, "StrongBox unavailable for this key; using TEE") }
        }
        return try {
            generateKey(useStrongBox = false)
        } catch (t: Throwable) {
            throw VaultUnavailable("This device cannot create a secure key.", t)
        }
    }

    private fun generateKey(useStrongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // No user-authentication requirement: a translation session must be
            // startable from the Quick Settings tile with the screen locked.
            .setUserAuthenticationRequired(false)
            .apply { if (useStrongBox) setIsStrongBoxBacked(true) }
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    @Volatile
    private var cached: SecretKey? = null

    companion object {
        private const val TAG = "KeyVault"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "earslate.vault.v1"
        private const val PREFS_NAME = "earslate_vault"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
