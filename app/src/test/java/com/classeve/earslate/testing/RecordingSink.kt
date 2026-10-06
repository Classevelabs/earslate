package com.classeve.earslate.testing

import com.classeve.earslate.session.ConversationSink

/** Remembers everything the engine decided, so a test can ask what was actually heard. */
class RecordingSink : ConversationSink {

    class Played(val atMs: Long, val leg: Int, val ms: Int, val voiced: Boolean)

    var nowMs = 0L
    val played = ArrayList<Played>()
    val mutedQueued = ArrayList<Int>()
    val lines = ArrayList<Pair<Int, String>>()
    val discarded = ArrayList<Int>()
    val heard = ArrayList<String>()
    val speaking = ArrayList<Boolean>()
    private val pending = HashMap<Int, StringBuilder>()

    fun pending(leg: Int): String = pending[leg]?.toString().orEmpty()

    fun heardMs(leg: Int, fromMs: Long = 0, untilMs: Long = Long.MAX_VALUE): Int =
        played.filter { it.leg == leg && it.voiced && it.atMs >= fromMs && it.atMs < untilMs }.sumOf { it.ms }

    override fun play(leg: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean) {
        played += Played(nowMs, leg, pcm.size * 1000 / (sampleRateHz * 2), voiced)
    }

    override fun muteQueued(leg: Int) {
        mutedQueued += leg
    }

    override fun captionDelta(leg: Int, text: String) {
        pending.getOrPut(leg) { StringBuilder() }.append(text)
    }

    override fun captionCommit(leg: Int) {
        pending.remove(leg)?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += leg to it }
    }

    override fun captionDiscard(leg: Int) {
        if (pending.remove(leg) != null) discarded += leg
    }

    override fun speakingChanged(speaking: Boolean) {
        this.speaking += speaking
    }

    override fun theirLanguageHeard(languageCode: String) {
        heard += languageCode
    }
}
