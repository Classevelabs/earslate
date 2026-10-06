package com.classeve.earslate.testing

import com.classeve.earslate.security.SecretStore

/**
 * A key vault that holds one Gemini key in memory and stores nothing. The key
 * a run is given lives for the length of that run.
 */
class OneKey(private val key: String) : SecretStore {
    override fun contains(name: String) = name == ENTRY
    override fun get(name: String) = key.takeIf { name == ENTRY }
    override fun put(name: String, secret: String) = Unit
    override fun remove(name: String) = Unit
    override val wasResetByKeystore = false
    override fun acknowledgeKeystoreReset() = Unit

    private companion object {
        const val ENTRY = "api_key_gemini"
    }
}
