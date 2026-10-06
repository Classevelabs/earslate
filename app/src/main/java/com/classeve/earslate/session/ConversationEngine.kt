package com.classeve.earslate.session

import com.classeve.earslate.audio.Pcm
import com.classeve.earslate.live.LiveEvent
import com.classeve.earslate.session.HeardLanguageTracker.Companion.sameLanguage

/** Whose voice a translate session believes it is hearing. */
enum class Speaker { UNKNOWN, ME, THEM }

/** INBOUND speaks my language for what they say; OUTBOUND speaks theirs for what I say. */
enum class LegRole { INBOUND, OUTBOUND }

/** What the engine decided, for the audio path and the screen to carry out. */
interface ConversationSink {
    /** Play [pcm] on the leg's own lane; [voiced] false means play silence of that length. */
    fun play(leg: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean)

    /** What is queued for [leg] should not be heard after all. */
    fun muteQueued(leg: Int)

    fun captionDelta(leg: Int, text: String)
    fun captionCommit(leg: Int)
    fun captionDiscard(leg: Int)

    /** True while a translation that may be heard is arriving. */
    fun speakingChanged(speaking: Boolean)

    /** The other person is speaking [languageCode], which is not what I was being answered in. */
    fun theirLanguageHeard(languageCode: String)
}

/**
 * Decides, for two translate sessions listening to one microphone, which of
 * them may be heard.
 *
 * **The rule: never speak the language you just heard.** The providers are
 * asked for this and do not reliably deliver it, so it is enforced here. Each
 * session is judged by what that session itself reports hearing, because what
 * it says next follows from its own hearing, not from the other's.
 *
 * Neither provider marks where an utterance ends, so that is read from the
 * audio: a run of speech ends when its session has been silent for
 * [RUN_END_MS].
 *
 * Pure, and not thread-safe: the owner calls it from one place at a time.
 */
class ConversationEngine(
    private var myLanguage: String,
    theirLanguage: String,
    var followsTheirLanguage: Boolean,
    private val sink: ConversationSink,
) {

    private class Run(var allowed: Boolean, var definite: Boolean, var lastVoicedAtMs: Long) {
        var silentMs = 0

        /** Set when the speaker changed under this run: it ends at its next pause, or here. */
        var staleUntilMs = NEVER
        val stale get() = staleUntilMs != NEVER
    }

    private class Leg(val role: LegRole) {
        var hears = Speaker.UNKNOWN

        /** True once this leg has sent silence: its pauses are then in the audio itself. */
        var sendsSilence = false
        var run: Run? = null
        val words = RecentWords()
        var lastSourceAtMs = NEVER
        val staged = StringBuilder()
        var stagedAtMs = 0L
    }

    private val legs = LinkedHashMap<Int, Leg>()
    private var following = HeardLanguageTracker(myLanguage, theirLanguage)
    private var speaking = false

    /** The language the other person is being answered in. */
    val theirLanguage: String get() = following.current

    fun legOpened(id: Int, role: LegRole) {
        legs[id] = Leg(role)
    }

    fun legClosed(id: Int) {
        val leg = legs.remove(id) ?: return
        leg.run?.let { finish(id, it) }
        announce()
    }

    /** Whose voice [id] believes it is hearing. */
    fun hears(id: Int): Speaker = legs[id]?.hears ?: Speaker.UNKNOWN

    /** True while [id] is in the middle of something that should be allowed to finish. */
    fun isMidRun(id: Int): Boolean = legs[id]?.run?.allowed == true

    /** The user changed a language by hand. Nothing decided before applies. */
    fun reconfigure(myLanguage: String, theirLanguage: String) {
        this.myLanguage = myLanguage
        following = HeardLanguageTracker(myLanguage, theirLanguage)
        for (leg in legs.values) {
            leg.hears = Speaker.UNKNOWN
            leg.words.clear()
        }
    }

    fun onEvent(legId: Int, event: LiveEvent, nowMs: Long) {
        val leg = legs[legId] ?: return
        when (event) {
            is LiveEvent.AudioChunk -> onAudio(legId, leg, event, nowMs)
            is LiveEvent.CaptionDelta -> onCaption(legId, leg, event.text, nowMs)
            is LiveEvent.SourceTranscript -> onSource(legId, leg, event, nowMs)
            else -> Unit
        }
    }

    private fun onAudio(legId: Int, leg: Leg, chunk: LiveEvent.AudioChunk, nowMs: Long) {
        leg.run?.let { if (it.stale && nowMs >= it.staleUntilMs) end(legId, leg) }

        if (Pcm.isSilent(chunk.pcm)) {
            leg.sendsSilence = true
            sink.play(legId, chunk.pcm, chunk.sampleRateHz, voiced = false)
            val run = leg.run ?: return
            run.silentMs += chunk.pcm.size * 1000 / (chunk.sampleRateHz * 2)
            if (run.silentMs >= (if (run.stale) STALE_RUN_END_MS else RUN_END_MS)) end(legId, leg)
            return
        }

        val run = leg.run ?: begin(legId, leg, nowMs)
        run.silentMs = 0
        run.lastVoicedAtMs = nowMs
        sink.play(legId, chunk.pcm, chunk.sampleRateHz, voiced = run.allowed)
    }

    private fun begin(legId: Int, leg: Leg, nowMs: Long): Run {
        val run = Run(
            allowed = mayRoleSpeak(leg.role, leg.hears),
            definite = leg.hears != Speaker.UNKNOWN,
            lastVoicedAtMs = nowMs,
        )
        leg.run = run
        // The text that arrived just ahead of this audio belongs to it.
        if (run.allowed && leg.staged.isNotEmpty()) sink.captionDelta(legId, leg.staged.toString())
        leg.staged.setLength(0)
        announce()
        return run
    }

    private fun end(legId: Int, leg: Leg) {
        val run = leg.run ?: return
        leg.run = null
        finish(legId, run)
        announce()
    }

    private fun finish(legId: Int, run: Run) {
        if (run.allowed) sink.captionCommit(legId) else sink.captionDiscard(legId)
    }

    private fun onCaption(legId: Int, leg: Leg, text: String, nowMs: Long) {
        val run = leg.run
        when {
            run != null && !run.stale -> if (run.allowed) sink.captionDelta(legId, text)
            // Between runs, or under a run the speaker has moved on from: this
            // text is for whatever the leg says next, if that is allowed.
            mayRoleSpeak(leg.role, leg.hears) -> {
                if (leg.staged.isEmpty()) leg.stagedAtMs = nowMs
                leg.staged.append(text)
            }
        }
    }

    private fun onSource(legId: Int, leg: Leg, heard: LiveEvent.SourceTranscript, nowMs: Long) {
        // An empty transcript only repeats the provider's last verdict.
        if (heard.text.isBlank()) return
        leg.lastSourceAtMs = nowMs
        val language = heard.languageCode ?: leg.words.observe(heard.text) ?: return
        hearing(legId, leg, if (sameLanguage(language, myLanguage)) Speaker.ME else Speaker.THEM, nowMs)

        // One leg's hearing is enough to follow their language; two would
        // count every fragment as its own second opinion.
        if (leg.role != LegRole.INBOUND) return
        val verdict = following.report(language)
        if (verdict is HeardLanguageTracker.Heard.Them && verdict.changed && followsTheirLanguage) {
            sink.theirLanguageHeard(verdict.language)
        }
    }

    private fun hearing(legId: Int, leg: Leg, next: Speaker, nowMs: Long) {
        if (leg.hears == next) return
        leg.hears = next
        val run = leg.run ?: return
        val allowedNow = mayRoleSpeak(leg.role, next)
        if (!run.definite) {
            // Started before this leg knew who was talking. Now it does.
            run.definite = true
            if (run.allowed && !allowedNow) {
                run.allowed = false
                sink.muteQueued(legId)
                sink.captionDiscard(legId)
            }
        } else if (run.allowed != allowedNow && !run.stale) {
            // What it is saying now belongs to the previous speaker and may
            // finish; what follows is judged again.
            run.staleUntilMs = nowMs + STALE_RUN_MAX_MS
        }
        announce()
    }

    /**
     * Ends what the audio alone cannot: an utterance from a provider that sends
     * nothing while silent. One that sends silence is left to say so itself —
     * a gap in its arrivals is the network, not the end of a sentence.
     */
    fun onTick(nowMs: Long) {
        for ((id, leg) in legs.entries.toList()) {
            val run = leg.run
            if (run != null) {
                val quietFor = nowMs - run.lastVoicedAtMs
                if (!leg.sendsSilence && quietFor >= RUN_END_MS + ARRIVAL_SLACK_MS ||
                    (run.stale && nowMs >= run.staleUntilMs && quietFor >= STALE_RUN_END_MS)
                ) {
                    end(id, leg)
                }
            } else if (leg.staged.isNotEmpty() && nowMs - leg.stagedAtMs >= STAGED_TEXT_TTL_MS) {
                leg.staged.setLength(0)
            }
            if (leg.lastSourceAtMs != NEVER && nowMs - leg.lastSourceAtMs >= INPUT_IDLE_MS) {
                leg.words.clear()
                leg.lastSourceAtMs = NEVER
            }
        }
    }

    private fun announce() {
        val now = legs.values.any { it.run?.allowed == true }
        if (now == speaking) return
        speaking = now
        sink.speakingChanged(now)
    }

    companion object {
        private const val NEVER = -1L

        /** Silence that ends an utterance: longer than a pause inside a sentence. */
        const val RUN_END_MS = 700

        /** After the speaker changes, the first real pause is where the old speaker's translation ends. */
        const val STALE_RUN_END_MS = 200

        /** …and it has ended by now even without one: translated audio trails its text by about a second. */
        const val STALE_RUN_MAX_MS = 1_500L

        private const val ARRIVAL_SLACK_MS = 300
        private const val STAGED_TEXT_TTL_MS = 2_500L
        private const val INPUT_IDLE_MS = 1_500L

        /** Never speak the language you just heard. With nobody identified yet, either may speak. */
        fun mayRoleSpeak(role: LegRole, hears: Speaker): Boolean = when (hears) {
            Speaker.ME -> role == LegRole.OUTBOUND
            Speaker.THEM -> role == LegRole.INBOUND
            Speaker.UNKNOWN -> true
        }
    }
}
