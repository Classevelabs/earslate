package com.classeve.earslate.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for runtime state. UI reads via [state]; the
 * SessionCoordinator is the only writer (tests can also drive it directly).
 *
 * Plain StateFlow — no reactive framework. Matches Lven-Android's conventions.
 */
class RuntimeStateStore {
    private val _state = MutableStateFlow(RuntimeState.IDLE)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _lastError = MutableStateFlow<RuntimeError?>(null)
    val lastError: StateFlow<RuntimeError?> = _lastError.asStateFlow()

    /**
     * The language the other side is currently being heard in, once something
     * has actually been recognised. Null until then, and null is shown as
     * nothing rather than as "English" — claiming a detection we have not made
     * is worse than saying we are still listening.
     */
    private val _heardLanguage = MutableStateFlow<TargetLanguage?>(null)
    val heardLanguage: StateFlow<TargetLanguage?> = _heardLanguage.asStateFlow()

    fun set(next: RuntimeState) {
        _state.value = next
    }

    /**
     * True when the user corrected the language by hand, so the session has
     * stopped following what it hears. Shown so a correction is visibly
     * sticky rather than something that might silently revert.
     */
    private val _theirLanguagePinned = MutableStateFlow(false)
    val theirLanguagePinned: StateFlow<Boolean> = _theirLanguagePinned.asStateFlow()

    fun setHeardLanguage(language: TargetLanguage?) {
        _heardLanguage.value = language
    }

    fun setTheirLanguagePinned(pinned: Boolean) {
        _theirLanguagePinned.value = pinned
    }

    fun setError(error: RuntimeError?) {
        _lastError.value = error
    }

    fun clearError() {
        _lastError.value = null
    }
}

/**
 * User-facing runtime error. Populated by the coordinator when something
 * fails deterministically enough to surface in the UI. Transient socket blips
 * that the reconnect logic recovers from are not surfaced — only genuine
 * dead-end failures.
 */
data class RuntimeError(
    val kind: Kind,
    val message: String,
) {
    enum class Kind {
        BOOTSTRAP_FAILED,
        CONNECT_FAILED,
        PERMISSION_DENIED,

        /**
         * The provider accepted the session and then refused it — quota, an
         * invalid key, a model the account cannot reach. Distinct from
         * CONNECT_FAILED because the fix is on the user's provider account, not
         * on their network, and telling them the wrong one wastes their time.
         */
        PROVIDER_ERROR,
        UNKNOWN,
    }
}
