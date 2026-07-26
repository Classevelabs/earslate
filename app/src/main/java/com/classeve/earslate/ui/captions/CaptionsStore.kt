package com.classeve.earslate.ui.captions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rolling window of translated captions:
 *   - captions are optional
 *   - incremental but stable
 *   - never an unbounded chat log — we keep the last [maxLines] lines only
 *
 * The session coordinator pushes [appendDelta] as `LiveEvent.CaptionDelta`
 * events arrive and [commitLine] on `TurnComplete`.
 */
class CaptionsStore(
    private val maxLines: Int = 48,
) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val _pending = MutableStateFlow("")
    val pending: StateFlow<String> = _pending.asStateFlow()

    private val builder = StringBuilder()
    private val lock = Any()

    fun appendDelta(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            builder.append(text)
            _pending.value = builder.toString()
        }
    }

    fun commitLine() {
        synchronized(lock) {
            val committed = builder.toString().trim()
            builder.setLength(0)
            _pending.value = ""
            if (committed.isEmpty()) return
            _lines.value = (_lines.value + committed).takeLast(maxLines)
        }
    }

    fun clear() {
        synchronized(lock) {
            builder.setLength(0)
            _pending.value = ""
        }
        _lines.value = emptyList()
    }
}
