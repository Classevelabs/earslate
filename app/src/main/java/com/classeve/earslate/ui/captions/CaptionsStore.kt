package com.classeve.earslate.ui.captions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Rolling window of translated captions. Each direction of the conversation
 * builds its own line, so two people speaking close together do not end up in
 * one sentence; a line is committed when its speaker stops.
 */
class CaptionsStore(
    private val maxLines: Int = 48,
) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    /** Lines still being spoken, oldest first. */
    private val _pending = MutableStateFlow<List<String>>(emptyList())
    val pending: StateFlow<List<String>> = _pending.asStateFlow()

    private val builders = LinkedHashMap<Int, StringBuilder>()
    private val lock = Any()

    fun appendDelta(source: Int, text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            builders.getOrPut(source) { StringBuilder() }.append(text)
            publish()
        }
    }

    /** Throw away the line [source] was building; committed lines are kept. */
    fun discardPending(source: Int) {
        synchronized(lock) {
            if (builders.remove(source) != null) publish()
        }
    }

    fun commitLine(source: Int) {
        synchronized(lock) {
            val committed = builders.remove(source)?.toString()?.trim().orEmpty()
            if (committed.isNotEmpty()) _lines.value = (_lines.value + committed).takeLast(maxLines)
            publish()
        }
    }

    fun clear() {
        synchronized(lock) {
            builders.clear()
            _pending.value = emptyList()
            _lines.value = emptyList()
        }
    }

    private fun publish() {
        _pending.value = builders.values.map { it.toString().trimStart() }.filter { it.isNotEmpty() }
    }
}
