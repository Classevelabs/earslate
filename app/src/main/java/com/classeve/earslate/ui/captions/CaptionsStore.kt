package com.classeve.earslate.ui.captions

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whose words a caption carries. */
enum class CaptionSide {
    /** What the other person said, put into my language. */
    THEIRS,

    /** What I said, put into theirs. */
    MINE,
}

/**
 * One thing somebody said, translated. [live] while it is still being spoken:
 * its text grows, and its place in the conversation stays where it began.
 */
data class Caption(
    val id: Long,
    val side: CaptionSide,
    val text: String,
    val live: Boolean,
)

/**
 * The conversation so far, as translated captions in the order they were
 * begun. Each direction builds its own caption, so two people speaking close
 * together do not end up in one sentence; a caption is settled when its
 * speaker stops.
 */
class CaptionsStore(
    private val maxCaptions: Int = 48,
) {
    private class Entry(val id: Long, val side: CaptionSide) {
        val text = StringBuilder()
        var live = true
    }

    private val _captions = MutableStateFlow<List<Caption>>(emptyList())
    val captions: StateFlow<List<Caption>> = _captions.asStateFlow()

    private val entries = ArrayList<Entry>()
    private val building = HashMap<Int, Entry>()
    private var nextId = 0L
    private val lock = Any()

    /** What has been said to its end, oldest first. */
    fun settled(): List<String> = captions.value.filter { !it.live }.map { it.text }

    /** What is still being said, oldest first. */
    fun live(): List<String> = captions.value.filter { it.live }.map { it.text }

    fun appendDelta(source: Int, side: CaptionSide, text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            val entry = building.getOrPut(source) { Entry(nextId++, side).also { entries += it } }
            entry.text.append(text)
            publish()
        }
    }

    /** Throw away the caption [source] was building; settled ones are kept. */
    fun discardPending(source: Int) {
        synchronized(lock) {
            val entry = building.remove(source) ?: return
            entries.remove(entry)
            publish()
        }
    }

    fun commitLine(source: Int) {
        synchronized(lock) {
            val entry = building.remove(source) ?: return
            entry.live = false
            if (entry.text.isBlank()) entries.remove(entry)
            publish()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            building.clear()
            _captions.value = emptyList()
        }
    }

    private fun publish() {
        while (entries.size > maxCaptions) building.values.remove(entries.removeAt(0))
        _captions.value = entries.mapNotNull { entry ->
            val text = if (entry.live) entry.text.trimStart() else entry.text.trim()
            if (text.isEmpty()) null else Caption(entry.id, entry.side, text.toString(), entry.live)
        }
    }
}
