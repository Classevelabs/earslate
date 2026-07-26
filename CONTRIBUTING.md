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

## What makes a change easy to accept

**Say why, not what.** The diff shows what changed. The commit message and the
comments should explain why it needed to, and what you considered instead. If
the reason is subtle, that is exactly the reason to write it down.

**Cover behaviour, not lines.** A test that pins a real property — "one gap
counts as one underrun however often the loop polls it" — is worth ten that
assert getters. If you fix a bug, add the test that would have caught it.

**Leave the tree green.** `testDebugUnitTest`, `lintDebug` and `assembleDebug`
must all pass. Lint warnings that you have decided are acceptable should be
suppressed narrowly, at the site, with a comment saying why.

**No stubs.** Half-finished work with a TODO on it is harder to remove than to
never merge. Send the part that is done.

## Areas where care is needed

**Audio.** The playback path is latency-sensitive and easy to make worse by
accident. The jitter buffer deliberately does not reset on underrun, and
deliberately counts one underrun per gap rather than per poll — a conversation
is mostly silence, and per-poll counting ratchets the buffer to its ceiling
within a second. If you change the adaptation, `JitterBufferTest` should tell
you immediately.

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
Settings → Advanced → Diagnostics shows buffer health and latency; those
numbers are far more useful than "it stutters". Diagnostics stay on your
device, so paste what is relevant.

**Do not file security issues publicly** — see [SECURITY.md](SECURITY.md).

## Licence

Contributions are accepted under the Apache License 2.0, the same licence as
the project. By opening a pull request you confirm you have the right to submit
the work under it.
