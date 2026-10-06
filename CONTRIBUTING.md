# Contributing to earslate

Thanks for looking. Small, well-argued changes are very welcome.

## Before you build

Requires JDK 17 and an Android SDK. Copy `local.properties.example` to
`local.properties` and set `sdk.dir`. Then:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

There is nothing else to configure — no keys, no service URLs, no accounts. If
a build asks you for a secret, that is a bug; please report it.

To actually run a translation you will need your own Gemini or OpenAI key,
entered in the app at runtime. It is billed to your account, so develop with a
key you are happy to spend a little on.

## Tests that need a key or a device

The command above needs neither. Three more sets of tests are skipped unless
you ask for them, and the ones that take a key spend a little on it.

**Against the real provider, from your computer.** Set
`EARSLATE_LIVE_GEMINI_KEY` and run `LiveGeminiSessionTest`: a two-way
conversation, a conversation on a loudspeaker with the microphone hearing
everything the loudspeaker says, and every language in the pickers.
`EARSLATE_LIVE_LONG=1` adds an eleven-minute run across the provider's own
disconnect. `EARSLATE_LIVE_AUDIO_DIR` keeps the synthesized speech between
runs. `EARSLATE_LIVE_OPENAI_KEY` runs `LiveOpenAiSessionTest`.

**On a device or emulator.** `./gradlew connectedDebugAndroidTest` runs the
audio engines against the real framework and the captions on a real screen.

**On a device, against the real provider.** Add
`-Pandroid.testInstrumentationRunnerArguments.geminiKey=…` and two more run:
a real session (`LiveSessionOnDeviceTest`) and the whole app through its own
screen, service and microphone (`WholeAppOnDeviceTest`). Both look for a
recording of Spanish speech pushed to the device; each says where.

A key given to a test is used from memory and is never stored.

## What makes a change easy to accept

**Say why, not what.** The diff shows what changed. The commit message and the
comments should explain why it needed to, and what you considered instead. If
the reason is subtle, that is exactly the reason to write it down.

**Cover behaviour, not lines.** A test that pins a real property — "a pause
between sentences is not counted as the network running late" — is worth ten
that assert getters. If you fix a bug, add the test that would have caught it.

**Leave the tree green.** `testDebugUnitTest`, `lintDebug` and `assembleDebug`
must all pass. Lint warnings that you have decided are acceptable should be
suppressed narrowly, at the site, with a comment saying why.

**No stubs.** Half-finished work with a TODO on it is harder to remove than to
never merge. Send the part that is done.

## Areas where care is needed

**Audio.** The playback path is latency-sensitive and easy to make worse by
accident. Each direction plays on a continuous lane of its own, with a small
cushion of queued audio. The cushion grows only when a block arrives too late
to play, and gives the delay back only after a sustained clean run: by
shortening silence, or at the next pause. A conversation is mostly silence, so
a lane that has simply run out of things to say must never be counted as a
late network. If you change the adaptation, `PlayoutLaneTest` should tell you
immediately. On a loudspeaker the lanes are held and let go together by
`PlayoutDeck`; a lane that joins late has to be let through with the rest,
and `PlayoutDeckTest` covers it.

**Who is heard.** Two sessions listen to one microphone, and
`ConversationEngine` decides which may speak. The providers do not mark where
an utterance ends, so nothing in the app may wait for them to. A change here
wants a recorded session replayed through it: `RecordedConversationTest` runs
real provider traffic through the engine. Compare languages with
`HeardLanguageTracker.sameLanguage`, never with `==`: the model has its own
names for some of them.

**Anything touching a key.** Keys must never be logged, never leave `KeyVault`
in plaintext beyond the moment of use, never be written to a file, and never be
shown in full in the UI. If a change makes a key more visible, it needs an
argument.

**Provider protocols.** Gemini and OpenAI speak different wire formats and
their APIs move. Protocol changes want a contract test alongside them so a
silent upstream change surfaces as a failing build rather than as a session
that connects and stays quiet.

**No backend.** earslate has no server and should acquire none. A change that
introduces a call to a ClassEve endpoint will not be merged — it breaks the
central promise of the app.

## Style

Match the file you are in. Kotlin official style; four spaces; a comment earns
its place by explaining a decision rather than narrating the next line.

## Reporting bugs

Say what you did, what happened, and what you expected. For audio problems,
say which headphones or speaker you used and how far away the other person
was; that tells us far more than "it stutters".

**Do not file security issues publicly** — see [SECURITY.md](SECURITY.md).

## License

Contributions are accepted under the Apache License 2.0, the same licence as
the project. By opening a pull request you confirm you have the right to submit
the work under it.
