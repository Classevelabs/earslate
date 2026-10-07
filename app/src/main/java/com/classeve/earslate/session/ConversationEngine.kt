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
    /**
     * Play [pcm] on the leg's own lane; [voiced] false means play silence of
     * that length. [begins] marks the first of an utterance.
     */
    fun play(leg: Int, pcm: ByteArray, sampleRateHz: Int, voiced: Boolean, begins: Boolean)

    /** What is queued of the utterance [leg] is speaking should not be heard after all. */
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
 * Decides, for the translate sessions listening to one microphone, which of
 * them may be heard.
 *
 * **The rule: never say again what was just said.** A session aimed at the
 * language being spoken has nothing to translate, and the providers, asked to
 * stay silent then, do not reliably do so. Two things show that it is the one:
 *
 *  - It names its own language as the one it hears. A session is believed
 *    about that and nothing else: recorded on 2026-10-06 and 07, the one aimed
 *    at English knew English every time it heard it, and wrote a Punjabi
 *    speaker down as Hindi, Gujarati, Vietnamese and Japanese.
 *  - Its words. A session that says what it heard is repeating, whatever it
 *    took the language for; and of two that answer the same speech, where one
 *    is plainly translating, the one that shares more of its words with what
 *    it heard than a translation would is the repeat.
 *
 * When neither shows who is speaking, both directions may be heard: a sentence
 * said twice costs a moment, and a sentence held back is the conversation lost.
 *
 * Neither provider marks where an utterance ends, so that is read from the
 * audio: a run of speech ends when its session has been silent for
 * [RUN_END_MS].
 *
 * Pure, and not thread-safe: the owner calls it from one place at a time.
 */
class ConversationEngine(
    private var myLanguage: String,
    theirLanguage: String?,
    var followsTheirLanguage: Boolean,
    private val sink: ConversationSink,
) {

    private class Run(var allowed: Boolean, var definite: Boolean, val beganAtMs: Long) {
        var lastVoicedAtMs = beganAtMs
        var silentMs = 0
        var played = false

        /** Set when the speaker changed under this run: it ends at its next pause, or here. */
        var staleUntilMs = NEVER
        val stale get() = staleUntilMs != NEVER

        /** All it has said, shown or not, and when it began to say it. */
        val said = StringBuilder()
        var saidSinceMs = NEVER
        var words = 0
        var wordsHeard = 0

        /** True while what it says is what was just said to it. */
        var repeating = false

        /** Found to be the repeat beside a direction that was plainly translating. */
        var outdone = false
        var over = false

        val audible get() = allowed && !repeating

        /** Few of its words are words it heard, and at least two are not: another language. */
        val plain get() = wordsHeard * 100 <= words * OTHER_LANGUAGE_PERCENT && words - wordsHeard >= OTHER_LANGUAGE_WORDS

        /** More of its words are words it heard than a translation shares with its source. */
        val closeToHeard get() = words >= 2 && wordsHeard * 100 > words * OTHER_LANGUAGE_PERCENT

        /** True once what it says is plainly in another language than what it heard. */
        fun translates(nowMs: Long): Boolean = when {
            !audible -> false
            // A provider that sends no text can only be taken at its word.
            said.isEmpty() -> nowMs - beganAtMs >= TEXTLESS_MS
            // A translation of a word or two is plain only when it is all there is.
            else -> plain || (over && words > 0 && wordsHeard == 0)
        }
    }

    private class Leg(val role: LegRole) {
        /** The language it last reported hearing, and when. */
        var language: String? = null
        var languageAtMs = NEVER
        var hears = Speaker.UNKNOWN

        /** True once this leg has sent silence: its pauses are then in the audio itself. */
        var sendsSilence = false
        var run: Run? = null
        val words = RecentWords()
        val speech = HeardSpeech()
        var heardAtMs = NEVER
        var lastSourceAtMs = NEVER
        val staged = StringBuilder()
        var stagedAtMs = 0L
    }

    private val legs = LinkedHashMap<Int, Leg>()
    private var following = HeardLanguageTracker(myLanguage, theirLanguage)
    private var speaking = false

    /** The language the other person is being answered in; null until one has been heard. */
    val theirLanguage: String? get() = following.current

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
    fun isMidRun(id: Int): Boolean = legs[id]?.run?.audible == true

    /** The user changed a language by hand. Nothing decided before applies. */
    fun reconfigure(myLanguage: String, theirLanguage: String?) {
        this.myLanguage = myLanguage
        following = HeardLanguageTracker(myLanguage, theirLanguage)
        for (leg in legs.values) {
            leg.language = null
            leg.languageAtMs = NEVER
            leg.heardAtMs = NEVER
            leg.hears = Speaker.UNKNOWN
            leg.words.clear()
            leg.speech.clear()
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

    // ── what it says ────────────────────────────────────────────────────

    private fun onAudio(legId: Int, leg: Leg, chunk: LiveEvent.AudioChunk, nowMs: Long) {
        leg.run?.let { if (it.stale && nowMs >= it.staleUntilMs) end(legId, leg, nowMs) }

        if (Pcm.isSilent(chunk.pcm)) {
            leg.sendsSilence = true
            sink.play(legId, chunk.pcm, chunk.sampleRateHz, voiced = false, begins = false)
            val run = leg.run ?: return
            run.silentMs += chunk.pcm.size * 1000 / (chunk.sampleRateHz * 2)
            if (run.silentMs >= (if (run.stale) STALE_RUN_END_MS else RUN_END_MS)) end(legId, leg, nowMs)
            return
        }

        val run = leg.run ?: begin(legId, leg, nowMs)
        run.silentMs = 0
        run.lastVoicedAtMs = nowMs
        sink.play(legId, chunk.pcm, chunk.sampleRateHz, voiced = run.audible, begins = !run.played)
        run.played = true
    }

    private fun begin(legId: Int, leg: Leg, nowMs: Long): Run {
        val run = Run(
            allowed = mayRoleSpeak(leg.role, leg.hears),
            definite = leg.hears != Speaker.UNKNOWN,
            beganAtMs = nowMs,
        )
        leg.run = run
        // The text that arrived just ahead of this audio belongs to it.
        if (run.allowed && leg.staged.isNotEmpty()) measure(legId, leg, run, leg.stagedAtMs, leg.staged.toString())
        leg.staged.setLength(0)
        announce()
        consider(nowMs)
        return run
    }

    private fun end(legId: Int, leg: Leg, nowMs: Long) {
        val run = leg.run ?: return
        // A translation of a word or two is only known for one when it is over.
        run.over = true
        consider(nowMs)
        leg.run = null
        finish(legId, run)
        announce()
    }

    private fun finish(legId: Int, run: Run) {
        if (run.audible) sink.captionCommit(legId) else sink.captionDiscard(legId)
    }

    private fun onCaption(legId: Int, leg: Leg, text: String, nowMs: Long) {
        val run = leg.run
        when {
            run != null && !run.stale -> if (run.allowed) {
                measure(legId, leg, run, nowMs, text)
                consider(nowMs)
            }
            // Between runs, or under a run the speaker has moved on from: this
            // text is for whatever the leg says next, if that is allowed.
            mayRoleSpeak(leg.role, leg.hears) -> {
                if (leg.staged.isEmpty()) leg.stagedAtMs = nowMs
                leg.staged.append(text)
            }
        }
    }

    /**
     * [run] has said [added], or its leg has heard more. Measures the one
     * against the other again, and carries out any change of mind about who
     * is repeating.
     */
    private fun measure(legId: Int, leg: Leg, run: Run, nowMs: Long, added: String = "") {
        val shown = run.audible
        if (added.isNotEmpty() && run.said.isEmpty()) run.saidSinceMs = nowMs
        run.said.append(added)
        if (run.saidSinceMs != NEVER) {
            val said = SpokenWords.split(run.said)
            // The speech it is answering is what it heard from just before it began.
            val heard = leg.speech.wordsSince(run.saidSinceMs - ANSWERS_WITHIN_MS)
            // A last word still being written cannot yet be one that was
            // heard, but it is already one that was not once nothing heard
            // begins the way it does.
            val begun = said.unfinished?.takeIf { piece -> heard.none { it.startsWith(piece) } }
            run.words = said.whole.size + if (begun == null) 0 else 1
            run.wordsHeard = said.whole.count { it in heard }
        }
        weighRepeats()
        // Shown whole by the change of mind, if there was one; otherwise word by word.
        if (shown && run.audible && added.isNotEmpty()) sink.captionDelta(legId, added)
    }

    /** Decides afresh which of the runs being spoken is a repeat, and carries out each change. */
    private fun weighRepeats() {
        var changed = false
        for ((id, leg) in legs.entries.toList()) {
            val run = leg.run?.takeIf { it.allowed && !it.stale } ?: continue
            val was = run.repeating
            run.repeating = repeats(leg, run)
            if (run.repeating == was) continue
            changed = true
            if (run.repeating) {
                if (run.played) sink.muteQueued(id)
                sink.captionDiscard(id)
            } else {
                // It went on to say what nobody had said: a translation after all, shown whole.
                sink.captionDelta(id, run.said.toString())
            }
        }
        if (changed) announce()
    }

    private fun repeats(leg: Leg, run: Run): Boolean {
        if (run.words == 0) return false
        // On its own account: nearly every word it says is a word it heard.
        // Easier to go on being taken for a repeat than to be taken for one:
        // the model respells a word here and there as it says a sentence back.
        val share = if (run.repeating && !run.outdone) STILL_REPEATING_PERCENT else REPEATING_PERCENT
        if (run.wordsHeard * 100 >= run.words * share) return true
        // Or beside the other direction. Both answer the same speech and only
        // one of them can be translating it: where that one is plain to see,
        // this one, too close to what it heard, is not it.
        if (run.plain) {
            run.outdone = false
            return false
        }
        if (run.outdone) return true
        run.outdone = run.closeToHeard && legs.values.any { other ->
            // One that is finishing for the last speaker answers other speech.
            other.role != leg.role && other.run?.let { it.allowed && !it.stale && it.plain } == true
        }
        return run.outdone
    }

    // ── what it hears ───────────────────────────────────────────────────

    private fun onSource(legId: Int, leg: Leg, heard: LiveEvent.SourceTranscript, nowMs: Long) {
        // An empty transcript only repeats the provider's last verdict.
        if (heard.text.isBlank()) return
        leg.lastSourceAtMs = nowMs
        val language = heard.languageCode ?: leg.words.observe(heard.text)
        val beginsSpeech = leg.heardAtMs == NEVER || nowMs - leg.heardAtMs > SAME_SPEECH_MS
        // A reply can follow so closely that only the language heard shows
        // where it began.
        val turn = beginsSpeech || (language != null && leg.language?.let { !sameLanguage(it, language) } == true)
        leg.heardAtMs = nowMs
        leg.run?.let { run ->
            // Somebody starting to speak while it already had speech to answer
            // is the next turn. What it is saying may finish; what it says
            // after that answers the new speech, and is judged as that.
            val answering = run.saidSinceMs != NEVER && leg.speech.wordsSince(run.saidSinceMs - ANSWERS_WITHIN_MS).isNotEmpty()
            if (turn && answering && !run.stale) run.staleUntilMs = nowMs + STALE_RUN_MAX_MS
        }
        leg.speech.add(heard.text, nowMs)
        // The words a session heard can arrive after the first of what it says.
        leg.run?.let { if (it.allowed && !it.stale) measure(legId, leg, it, nowMs) }

        if (language != null) {
            leg.language = language
            leg.languageAtMs = nowMs
            // One leg's hearing is enough to follow their language; two would
            // count every fragment as its own second opinion. A language fixed by
            // hand is not followed at all, or it would drift while it looked fixed.
            when {
                leg.role == LegRole.INBOUND -> if (followsTheirLanguage) following.heard(language, nowMs, beginsSpeech)
                following.isMine(language) || following.isTheirs(language) -> following.withdraw()
            }
            rejudge(nowMs)
        }
        consider(nowMs)
    }

    /**
     * Whose voice [leg] is taken to be hearing. Each direction is believed
     * about its own language only: mine when the one that speaks my language
     * hears it, theirs when the one that speaks theirs does.
     */
    private fun judged(leg: Leg): Speaker {
        val newest = legs.values.maxOfOrNull { it.languageAtMs } ?: NEVER
        fun hearsItsOwn(role: LegRole, own: (String) -> Boolean) = legs.values.any {
            // Only what it said about the speech going on now: one that has
            // not reported on that at all is still describing the last speaker.
            it.role == role && it.languageAtMs != NEVER && newest - it.languageAtMs <= SAME_SPEECH_MS &&
                it.language?.let(own) == true
        }
        val mine = hearsItsOwn(LegRole.INBOUND, following::isMine)
        val theirs = hearsItsOwn(LegRole.OUTBOUND, following::isTheirs)
        return when (leg.role) {
            LegRole.INBOUND -> if (mine) Speaker.ME else if (theirs) Speaker.THEM else Speaker.UNKNOWN
            LegRole.OUTBOUND -> if (theirs) Speaker.THEM else if (mine) Speaker.ME else Speaker.UNKNOWN
        }
    }

    private fun rejudge(nowMs: Long) {
        for ((id, leg) in legs.entries.toList()) hearing(id, leg, judged(leg), nowMs)
    }

    private fun hearing(legId: Int, leg: Leg, next: Speaker, nowMs: Long) {
        if (leg.hears == next) return
        leg.hears = next
        val run = leg.run ?: return
        // One already finishing for the last speaker is left to finish.
        if (run.stale) return
        val allowedNow = mayRoleSpeak(leg.role, next)
        if (!run.definite) {
            // Started before anyone knew who was talking. Once that is known, it is settled.
            run.definite = next != Speaker.UNKNOWN
            if (run.allowed && !allowedNow) {
                val wasAudible = run.audible
                run.allowed = false
                if (wasAudible) {
                    sink.muteQueued(legId)
                    sink.captionDiscard(legId)
                }
            }
        } else if (run.allowed != allowedNow) {
            // What it is saying now belongs to the previous speaker and may
            // finish; what follows is judged again.
            run.staleUntilMs = nowMs + STALE_RUN_MAX_MS
        }
        announce()
    }

    /**
     * Takes the language on offer for theirs once it has been seen translated
     * for me — and never when a direction is only saying it back, which shows
     * it was one of our own two languages under another name.
     */
    private fun consider(nowMs: Long) {
        val offer = following.offer ?: return
        val answering = legs.values.filter { it.run?.let { run -> run.beganAtMs >= offer.sinceMs } == true }
        if (answering.any { it.run?.repeating == true }) return following.withdraw()
        // Two sessions that cannot agree what a language is have not recognised it.
        val outbound = legs.values.filter { it.role == LegRole.OUTBOUND }
        if (outbound.isNotEmpty() && outbound.none { sameLanguage(it.language.orEmpty(), offer.language) }) return
        if (answering.none { it.role == LegRole.INBOUND && it.run?.translates(nowMs) == true }) return
        val language = following.accept() ?: return
        sink.theirLanguageHeard(language)
        rejudge(nowMs)
    }

    /**
     * Ends what the audio alone cannot: an utterance from a provider that sends
     * nothing while silent. One that sends silence is given far longer — a gap
     * in its arrivals is usually the network, not the end of a sentence — but
     * not for ever, or one that stopped sending it would never finish.
     */
    fun onTick(nowMs: Long) {
        for ((id, leg) in legs.entries.toList()) {
            val run = leg.run
            if (run != null) {
                val quietFor = nowMs - run.lastVoicedAtMs
                val limit = if (leg.sendsSilence) STALLED_RUN_END_MS else (RUN_END_MS + ARRIVAL_SLACK_MS).toLong()
                if (quietFor >= limit || (run.stale && nowMs >= run.staleUntilMs && quietFor >= STALE_RUN_END_MS)) {
                    end(id, leg, nowMs)
                }
            } else if (leg.staged.isNotEmpty() && nowMs - leg.stagedAtMs >= STAGED_TEXT_TTL_MS) {
                leg.staged.setLength(0)
            }
            if (leg.lastSourceAtMs != NEVER && nowMs - leg.lastSourceAtMs >= INPUT_IDLE_MS) {
                leg.words.clear()
                leg.lastSourceAtMs = NEVER
            }
        }
        following.expire(nowMs)
        consider(nowMs)
    }

    private fun announce() {
        val now = legs.values.any { it.run?.audible == true }
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

        /** Longer than any stall a living connection has shown; the ping gives up on a dead one soon after. */
        const val STALLED_RUN_END_MS = 5_000L
        private const val STAGED_TEXT_TTL_MS = 2_500L
        private const val INPUT_IDLE_MS = 1_500L

        /** Fragments of one utterance arrived at most 1.2 s apart in 166 recorded gaps. */
        private const val SAME_SPEECH_MS = 2_000L

        // In 86 recorded starts a session began to speak 104 to 453 ms after
        // the fragment it was answering, or one fragment (about a second)
        // later; whatever the last speaker said was 3.8 s back or more.
        private const val ANSWERS_WITHIN_MS = 1_500L

        // Of 57 recorded sessions, one saying back what it heard never had
        // fewer than 87 in 100 of its words from the speech itself, once past
        // its first; one translating between languages as close as Danish and
        // Norwegian never had more than 86, after its first few.
        private const val REPEATING_PERCENT = 90
        private const val STILL_REPEATING_PERCENT = 80

        // What is said is plainly in another language than what was heard
        // when they share almost no words: 21 in 100 at most between unrelated
        // languages. My own Punjabi, taken for Hindi and "translated" back
        // into Punjabi, shared 48 in 100 and more.
        private const val OTHER_LANGUAGE_PERCENT = 35
        private const val OTHER_LANGUAGE_WORDS = 2
        private const val TEXTLESS_MS = 1_500L

        /** Never say again what was just said. With nobody identified yet, either may speak. */
        fun mayRoleSpeak(role: LegRole, hears: Speaker): Boolean = when (hears) {
            Speaker.ME -> role == LegRole.OUTBOUND
            Speaker.THEM -> role == LegRole.INBOUND
            Speaker.UNKNOWN -> true
        }
    }
}
