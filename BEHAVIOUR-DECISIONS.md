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

## A direction is silenced only when it is seen to be repeating

**Decision.** Both sessions hear the one microphone, and the one aimed at the
language being spoken has nothing to translate. `ConversationEngine` silences
a session on two kinds of evidence and no other:

- it names its own language as the one it hears. A session is believed about
  that language only, never about the other one;
- its words. A session whose words are nine in ten the words it heard is
  repeating; and of two sessions answering the same speech, where one shares
  almost nothing with what it heard (a third of its words at most), the one
  that shares more is the repeat.

When neither shows who is speaking, both may be heard.

**Symptom.** Until 0.7.0 a session was silenced whenever the language the
model named was "not mine". The name is not dependable. Recorded on 2026-10-06
and 07, the session aimed at English knew English every time, and wrote a
Punjabi speaker down as Hindi, Gujarati, Urdu, Vietnamese and Japanese; the
one aimed at Punjabi wrote the same speaker down as Hindi, and once as an
English sentence that was a translation of what had been said. In one recorded
conversation 0.6.0 translated one of the Punjabi speaker's seven sentences for
the other person and played five of them back to the speaker. The English
translation itself had been right all seven times, and thrown away. On the
same recording 0.7.0 translates seven of seven, and plays back two seconds of
one sentence begun 0.4 s after the other person stopped.

Judging both sessions by one shared verdict had earlier lost 13% of the wanted
translation in a recorded Hindi-English conversation: a contrary report from
one session silenced the other mid-sentence. That is why each is believed
about its own language only.

**Verified against.** `ConversationEngineTest`, `SpokenWordsTest`, and
`RecordedConversationTest` over ten recorded conversations;
`LiveGeminiSessionTest` against the real model.

**If regressed.** What you say is not translated for the other person, or you
hear yourself repeated.

**What it cannot do.** Between two languages as close as Punjabi and Hindi, or
Norwegian and Danish, the model often takes one for the other and translates
nothing: recorded, a Punjabi sentence said to a Hindi speaker produced no
Hindi at all. No rule here can make a translation the model did not produce.
And a sentence the model writes down in another language's own words, with
nothing to compare it against, cannot be told from a translation: before the
other person has been heard, it is let through.

## Each direction plays on a continuous lane of its own

**Decision.** Each session's audio goes to its own `PlayoutLane` and its own
audio track, and the phone mixes them. A lane starts with 160 ms in hand and
grows that cushion only when a block arrives too late to play. It gives the
delay back after fifteen clean seconds: by shortening silence while the stream
runs, and by half of what it grew at the next pause. A stream that opens with
more than the cushion already in hand is played at once.

**Symptom.** The buffer this replaced treated the stream as bursts that begin
and end. On a stream that never ends it waited for a second block before
playing the first and its delay only grew. Two directions sharing one queue
meant whichever spoke first owned the loudspeaker. A connection's first audio
often arrives as two or three blocks together (measured 2026-10-06), and
waiting out the cushion on top of that added delay for nothing.

**Verified against.** `PlayoutLaneTest`, including a recorded 50-second
session, and `AudioTeardownTest` on a device.

**If regressed.** The translation stutters, or falls further behind the
longer the conversation runs.

## On a loudspeaker the phone takes turns

**Decision.** When the translation comes out of a loudspeaker the microphone
can hear, `FloorControl` holds it until the person pauses, plays it in one
piece, and sends the provider silence only while it plays. In earbuds nothing
is held and the microphone never closes. `PlayoutDeck` keeps every lane and
the state of the floor together, so a direction that first speaks while the
phone is already talking is let through with the rest. In a room that never
sounds quiet, the translation is spoken once no more of it has arrived for
four seconds.

**Symptom.** The model starts speaking a few seconds into a sentence. The old
gate closed the microphone whenever the phone spoke, so half of what a person
said was never sent: in one recording 9.7 of 19.4 seconds were dropped, and
the model translated the fragments it had left. A lane made while the phone
was talking was made held and never released: nothing came out of it, the
phone never finished its turn, and the microphone stayed closed.

**Verified against.** `FloorControlTest`, `PlayoutDeckTest`, and
`SessionCoordinatorTest` on real lanes.

**If regressed.** On speaker, the translation is of half-heard sentences, the
phone translates its own voice, or it goes silent and stops listening.

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

## A credential's life is counted on the provider's clock

**Decision.** Every reply from a provider carries the provider's time.
`ProviderSessionMinter` works out how far the phone's clock is from it, asks
for a credential that ends thirty minutes from now by the provider's clock,
and asks once more when the first reply shows the phone was more than two
minutes out. The time a provider gives for a credential's end is read against
the provider's clock, never the phone's.

**Symptom.** Google gives out a credential whose end is already in the past
without complaint, and refuses one that ends more than twenty hours ahead
(observed 2026-10-06). A phone whose clock ran slow was given a credential
that could open nothing, and one whose clock ran a day fast was refused.

**Verified against.** `ProviderSessionMinterTest` and `SessionCoordinatorTest`.

**If regressed.** On a phone with the wrong time the app cannot connect.

## A connection is replaced before the provider closes it

**Decision.** Gemini warns about fifty seconds before it closes a connection,
a little under ten minutes after it opened. On that warning
`SessionCoordinator` opens the replacement, moves the microphone across at a
pause, and lets the old connection finish its sentence before closing it. The
old connection is sent silence until then. A replacement that will not open is
tried again, and one that died while it waited is never put to use.

**Symptom.** Every conversation stopped at ten minutes and started again from
nothing: seconds of silence, and the other person's language forgotten. A
connection that is simply sent nothing more never says its last word: three
rounds out of three against Gemini on 2026-10-06.

**Verified against.** `SessionCoordinatorTest`, and the ten-minute run in
`LiveGeminiSessionTest` against Gemini.

**If regressed.** A long conversation breaks off every ten minutes, or loses
the end of a sentence each time.

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

## One language can go by two names

**Decision.** Two language codes are the same language when their first parts
match, and `no`/`nb` and `tl`/`fil` are one language each
(`HeardLanguageTracker.sameLanguage`). The pickers, the first-run default and
the provider's own reports all go through that one comparison. A language the
model merely mistakes for another is not in that list: it is found out by
what the session says back.

**Symptom.** The translate model reports Norwegian as `no` and Filipino as
`tl`, while the pickers say `nb` and `fil`. Every language in the pickers was
spoken to it on 2026-10-06. The app took a Norwegian speaker for the other
person: in the recording, everything they said was played back to them, 10.5
of 10.5 seconds, and a Filipino speaker's sentences were not translated for
the other person at all. A Finnish phone, `fi`, was matched against `fil` on
first run and started in Filipino.

It also hears Malay as Indonesian. That used to be a third pair in the list,
which made an Indonesian speaker indistinguishable from the Malay user. Since
0.7.0 it is caught as any repeat is: the session aimed at Malay says the Malay
back word for word, 16 of 16 seconds in the recording, and none of it is heard.

**Verified against.** `RecordedConversationTest` over the three recordings,
`HeardLanguageTrackerTest`, `TargetLanguageTest` and `SettingsRepositoryTest`.

**If regressed.** People who speak those languages hear themselves repeated
and are not translated.

## What is wrong but still running is said, and tried again

**Decision.** A direction that cannot be set up, or a language the provider
cannot speak, is shown as a notice on the screen while everything else keeps
running. `SessionCoordinator.sync` compares what the person asked for with
what each connection was last told, tries again for as long as the session
lasts, and takes the notice away when it succeeds. Only what stops the session
is an error.

**Symptom.** A change of language that failed once was never tried again and
nothing said so. A change made while the app was still connecting, or just
before a reconnect, was lost. The person went on speaking and the other heard
nothing.

**Verified against.** `SessionCoordinatorTest`.

**If regressed.** A conversation that looks live and translates in one
direction only.

## A reconnect is given up only when the provider says no

**Decision.** While a session is being brought back, a failure to reach the
provider is one more attempt, not the end. The session ends when a provider
refuses the key, or after six attempts in a row.

**Symptom.** A phone that lost its network was told the session was over on
the first attempt that could not reach the provider, although the attempts
that remained would have found the network back.

**Verified against.** `SessionCoordinatorTest` and
`ProviderSessionMinterTest`.

**If regressed.** Any short gap in the network ends the conversation.

## A pasted key is cleaned before it is used

**Decision.** `KeyProvider.tidy` removes the spaces and invisible characters a
copy can pick up, and a key that still holds a character no key contains is
refused before it reaches the network library.

**Symptom.** A key copied with an invisible character in it made the network
library throw while building the request. The app stopped, and the library's
message quoted the key.

**Verified against.** `KeyProviderTest` and `ProviderSessionMinterTest`.

**If regressed.** The app closes on a pasted key, and the key appears in an
error.

## Settings made in 0.5.3 are still read

**Decision.** `SettingsRepository` reads the pair of languages 0.5.3 saved
under its own names, and stops reading them once the person chooses again.

**Symptom.** 0.5.3 kept "their language" and "fixed by hand" under names the
new settings do not use. An update would have put a pair chosen by hand back
to following, without a word.

**Verified against.** `SettingsRepositoryTest`, and the 0.6.0 build installed
over a set-up 0.5.3 on an emulator: the languages, the provider and both saved
keys were still there, and the saved key could still be read.

**If regressed.** After updating, the app translates into a language the
person did not choose.

## Nothing is said for me until they have been heard

**Decision.** What I say goes out in the language the other person was last
heard speaking, and in no other. Until one has been heard, or set by hand on
the main screen, no session is opened for what I say (`SessionCoordinator`).

**Symptom.** Until 0.7.0 that direction opened aimed at English. Somebody who
spoke first had their words said aloud in English to a person who might speak
anything, and the screen gave no sign of why.

**Verified against.** `SessionCoordinatorTest`, `HeardLanguageTrackerTest`,
and `LiveGeminiSessionTest`: Punjabi first, then an English speaker, then a
Chinese one, against the real model.

**If regressed.** The phone speaks a language nobody present has spoken.

## A language is theirs when it has been translated, not when it has been named

**Decision.** The language a session names is put on offer, and becomes the
other person's only when the session listening for them is seen translating
it: what it says shares at most a third of its words with what it heard, the
session for the other direction, if there is one, heard the same language,
and neither is saying it back. A language somebody begins to speak in is on
offer from its first words; in the middle of speech it must be named twice.

**Symptom.** The first language named was taken at once. In the recorded
Punjabi conversation that was Hindi, twice, for the Punjabi speaker's own
sentences: their next words would have gone out in Hindi to somebody speaking
English. Replayed through 0.7.0 the other person's language is English from
start to finish, and in the recording with a Chinese speaker it becomes
Chinese at their first translated words, with nothing of it said in English.

**Verified against.** `ConversationEngineTest`, `HeardLanguageTrackerTest`,
`RecordedConversationTest`, `LiveGeminiSessionTest`.

**If regressed.** What you say goes out in a language the model imagined.

**What it cannot do.** Somebody whose language is close to yours, Danish to
a Norwegian, is translated for you but their language is not taken up by
itself: its translation shares too many words with it to be told from your
own speech misheard. Their language can be set by hand.

## Captions are a conversation, each on its speaker's side

**Decision.** A caption carries whose it is, keeps the place it began in, and
has a name of its own (`CaptionsStore`). The screen shows what they said on
the left and what I said on the right; tapping a caption copies it, and COPY
ALL copies the conversation with who said each line.

**Symptom.** The captions were one column of lines, finished ones first and
unfinished ones after. Nothing showed which way a line had gone, a line moved
when it finished, and text could only be taken by selecting it by hand.

**Verified against.** `CaptionsStoreTest`, `CaptionRowsTest`, and
`CaptionsOnDeviceTest` on a device.

**If regressed.** The conversation cannot be followed by reading it.

## The newest caption stays on the screen until a hand moves it

**Decision.** The captions list keeps to its newest row, and while a session
runs the page keeps its foot, where the captions stand, in view. Each stops
when a finger drags it away and starts again when it comes to rest at its
end. Whether it is following is a fact that is kept (`CaptionFollow`), never
worked out from where the list happens to be.

**Symptom.** Whether to follow was read off the list's position each time a
caption arrived. Two captions arriving before the list had finished moving to
the first left it short of the end, which looked like somebody having
scrolled up, and it never moved again: on a device, forty lines in, the list
still showed the first six. The panel also stands at the foot of a page
taller than the screen, so its newest lines were below the screen's edge even
when the list had followed.

**Verified against.** `CaptionRowsTest`, and `CaptionsOnDeviceTest` on a
device.

**If regressed.** The captions stop at some line and the conversation goes on
unseen below it.

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
