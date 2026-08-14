package com.classeve.earslate.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Single source of truth for runtime state. UI reads via [state]; the
 * SessionCoordinator is the only writer (tests can also drive it directly).
 *
 * Plain StateFlow — no reactive framework. Matches Lven-Android's conventions.
 */
class RuntimeStateStore {
    private val _state = MutableStateFlow(RuntimeState.IDLE)
    val state: StateFlow<RuntimeState> = _state.asStateFlow()

    private val _metrics = MutableStateFlow(RuntimeSnapshot())
    val metrics: StateFlow<RuntimeSnapshot> = _metrics.asStateFlow()

    private val _lastError = MutableStateFlow<RuntimeError?>(null)
    val lastError: StateFlow<RuntimeError?> = _lastError.asStateFlow()

    fun set(next: RuntimeState) {
        _state.value = next
    }

    fun updateMetrics(block: (RuntimeSnapshot) -> RuntimeSnapshot) {
        _metrics.update(block)
    }

    fun setError(error: RuntimeError?) {
        _lastError.value = error
    }

    fun clearError() {
        _lastError.value = null
    }
}

/**
 * Lightweight per-session snapshot exposed to the diagnostics screen. Never
 * serialized, never sent to a server.
 *
 * Only fields the coordinator actually writes belong here — a diagnostics row
 * wired to a counter nothing increments reads as "0 problems" when the truth is
 * "not measured".
 */
data class RuntimeSnapshot(
    val reconnectCount: Int = 0,
    val timeToFirstAudioMs: Long? = null,
)

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
