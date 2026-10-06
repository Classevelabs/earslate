# Why earslate is the way it is

Every entry here is a decision that looks arbitrary, wrong or removable until
you know what it cost. Each states the decision, the symptom that caused it,
what it was verified against, and what a user meets if someone regresses it.
The reason also sits in a comment next to the code; this file is the second
copy for people reading the product rather than the file.

---

## The session is shaped by translationConfig, never a prompt

**Decision.** `GeminiTranslationProtocol` configures a Gemini Live Translate
session with `generationConfig.translationConfig` (target language and echo)
and sends no `systemInstruction`. The wire model has no field for one.

**Symptom.** With a prompt, the model echoed the source language and lagged
by seconds instead of translating.

**Verified against.** `GeminiTranslationProtocolTest`.

**If regressed.** The other person's words come back late, in their own
language, or answered instead of translated.

## Nothing waits for the provider to say a turn ended

**Decision.** Where an utterance ends is read from the audio: a run of speech
is over when its session has been silent for 700 ms. No code waits for a
turn-complete or generation-complete event.

**Symptom.** The translate model sends one continuous stream of audio, silence
included, and never sends a turn event. Everything that waited for one never
happened: a caption line was never finished, the playback delay only ever
grew, the screen stayed on "translating", and the language detector judged the
whole conversation as one sentence.

**Verified against.** Recorded sessions with Gemini on 2026-10-06, replayed by
`RecordedConversationTest`; `ConversationEngineTest`.

**If regressed.** Captions run on as one endless line and the app feels stuck.

## A direction is heard only for speech in the other language

**Decision.** Both sessions hear the one microphone. `ConversationEngine` lets
the session that speaks my language be heard only when it reports hearing
theirs, and the session that speaks theirs only when it reports hearing mine.
Each session is judged by what that session itself reports, never by the
other's.

**Symptom.** Asked not to, the model still repeats speech that is already in
its target language, so people heard their own words back, and the repeat
could win the loudspeaker over the real translation. Judging both sessions by
one shared verdict lost 13% of the wanted translation in a recorded
Hindi-English conversation, because a single contrary report from one session
silenced the other mid-sentence.

**Verified against.** `ConversationEngineTest`, and `RecordedConversationTest`
over two recorded conversations.

**If regressed.** You hear yourself repeated, or part of a translation goes
missing.

## Each direction plays on a continuous lane of its own

**Decision.** Each session's audio goes to its own `PlayoutLane` and its own
audio track, and the phone mixes them. A lane starts with 160 ms in hand,
grows that cushion only when a block arrives too late to play, and gives the
delay back by shortening silence after a clean run.

**Symptom.** The buffer this replaced treated the stream as bursts that begin
and end. On a stream that never ends it waited for a second block before
playing the first and its delay only grew. Two directions sharing one queue
meant whichever spoke first owned the loudspeaker.

**Verified against.** `PlayoutLaneTest`, including a recorded 50-second
session, and `AudioTeardownTest` on a device.

**If regressed.** The translation stutters, or falls further behind the
longer the conversation runs.

## On a loudspeaker the phone takes turns

**Decision.** When the translation comes out of a loudspeaker the microphone
can hear, `FloorControl` holds it until the person pauses, plays it in one
piece, and sends the provider silence only while it plays. In earbuds nothing
is held and the microphone never closes.

**Symptom.** The model starts speaking a few seconds into a sentence. The old
gate closed the microphone whenever the phone spoke, so half of what a person
said was never sent: in one recording 9.7 of 19.4 seconds were dropped, and
the model translated the fragments it had left.

**Verified against.** `FloorControlTest` and `SessionCoordinatorTest`.

**If regressed.** On speaker, the translation is of half-heard sentences, or
the phone translates its own voice.

## One credential opens every connection in a conversation

**Decision.** The app trades the key for one credential that lasts thirty
minutes, has no use limit, and is tied to the translate model and nothing
else. Each connection states its own language in its first frame. The
credential is reused while it has twelve minutes left.

**Symptom.** A credential good for one connection and sixty seconds had to be
made again for each direction and each reconnect, one after the other, each a
round trip before any audio. A credential that also fixed the session's
settings could not open a connection whose settings differed by one field, so
with captions on a fresh install could not connect.

**Verified against.** `ProviderSessionMinterTest`,
`LocalKeyBootstrapRepositoryTest`, and `LiveGeminiSessionTest` against Gemini.

**If regressed.** Starting takes seconds longer, and a reconnect can fail on
a spent credential.

## A connection is replaced before the provider closes it

**Decision.** Gemini warns about fifty seconds before it closes a connection,
a little under ten minutes after it opened. On that warning
`SessionCoordinator` opens the replacement, moves the microphone across at a
pause, and lets the old connection finish its sentence before closing it.

**Symptom.** Every conversation stopped at ten minutes and started again from
nothing: seconds of silence, and the other person's language forgotten.

**Verified against.** `SessionCoordinatorTest`.

**If regressed.** A long conversation breaks off every ten minutes.

## Silence from the provider is not a dead connection

**Decision.** No timer ends a session because nothing has arrived. A dead
connection is found by the WebSocket ping, sent every ten seconds, and by the
phone reporting that it has moved to another network.

**Symptom.** A watchdog that restarted after three seconds without audio tore
down a healthy session during a 3.4-second stall. A five-second ping closed a
working connection on a slow Wi-Fi link where one reply took 5.2 seconds.

**Verified against.** `SessionCoordinatorTest`; the timings were measured
against Gemini on 2026-10-06.

**If regressed.** On a weak network the session restarts itself in the middle
of a sentence.

## The provider's own words are shown when it refuses

**Decision.** When a provider refuses a key or ends a session, its own
message is shown beside ours, after `ProviderMessage` has removed anything
shaped like a key or a token.

**Symptom.** A message guessed from a status code told a person with an
unfunded account, a key without access to the model, and a mistyped key the
same thing, and none of them could tell what to fix.

**Verified against.** `ProviderMessageTest`, `ProviderSessionMinterTest` and
`ProviderLinkTest`.

**If regressed.** "That key did not work" with no reason, or a key printed on
the screen.

## Each provider is spoken to in its own protocol

**Decision.** Gemini and OpenAI each have a `TranslationLiveProtocol`: sample
rate, frame size, language codes, events and errors. The microphone is opened
at the provider's own rate. A language a provider cannot speak is said so
where the provider is chosen.

**Symptom.** OpenAI's translation service was driven as if it were its
conversation service: the app waited for events it never sends, treated every
error as the end of the session, resampled audio in the app, and reported a
language OpenAI does not offer as a bad key.

**Verified against.** `OpenAiTranslationProtocolTest` and
`GeminiTranslationProtocolTest`. The OpenAI protocol follows OpenAI's
published reference. Its two addresses, and its replies to a key and to a
credential it does not accept, were observed on 2026-10-06; a whole session
has not been run against OpenAI's service.

**If regressed.** A working key that appears not to work.

## The APK and the AAB carry different certificates

**Decision.** The direct-download APK is signed with the brand key; the AAB for
Google Play is signed with the Play upload key. `verifyReleaseIdentity` and
`verifyBundleIdentity` check each one after the build.

**Symptom.** An AAB signed with the brand key built cleanly and Play refused
the upload; before 0.4.4 the APK's certificate carried company details.

**Verified against.** The release gates in `build-logic/gates`, configured by
`classeveGates` in `app/build.gradle.kts`.

**If regressed.** Play refuses the update, or a download carries company
details in its certificate.

## No ClassEve server in the path

**Decision.** The app mints each session on the phone from the person's own
provider key. There is no ClassEve backend, broker or account.

**Symptom.** The session broker that 0.4.0 removed put ClassEve between the
person and the provider, and a client still calling it met a 404 and could not
start a session.

**Verified against.** `LocalKeyBootstrapRepositoryTest` and
`ProviderSessionMinterTest`.

**If regressed.** Keys or audio pass through ClassEve, which the privacy policy
says never happens.
