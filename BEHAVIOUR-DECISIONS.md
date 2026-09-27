# Why earslate is the way it is

Every entry here is a decision that looks arbitrary, wrong or removable until
you know what it cost. Each states the decision, the symptom that caused it,
what it was verified against, and what a user meets if someone regresses it.
The reason also sits in a comment next to the code; this file is the second
copy for people reading the product rather than the file.

---

## The session is shaped by translationConfig, never a prompt

**Decision.** `LiveSessionConfigFactory` configures a Gemini Live Translate
session with `generationConfig.translationConfig` (target language and echo)
and sends no `systemInstruction`.

**Symptom.** With a prompt, the model echoed the source language and lagged
by seconds instead of translating.

**Verified against.** `GeminiSessionSetupParityTest` and
`GeminiProtocolContractTest`.

**If regressed.** The other person's words come back late, in their own
language, or answered instead of translated.

## One flag builds both the token and the setup frame

**Decision.** Whether captions are on is one value, passed to the token the
app mints and to the session's first frame alike.

**Symptom.** The token locks the session configuration. A token minted with
transcription on and a setup frame without it described two different
sessions, so with captions on, the default, a fresh install could not connect.

**Verified against.** `GeminiSessionSetupParityTest`.

**If regressed.** No session starts for anyone who keeps captions on.

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

## Playback starts above one provider chunk

**Decision.** The jitter buffer's floor sits above one provider chunk, and the
buffer grows only when the network forces it.

**Symptom.** A 40 ms floor was under half a chunk, so on mobile data playback
underran on almost every utterance and never settled.

**Verified against.** `JitterBufferTest`.

**If regressed.** The translation stutters on a phone network.

## No ClassEve server in the path

**Decision.** The app mints each session on the phone from the person's own
provider key. There is no ClassEve backend, broker or account.

**Symptom.** The session broker that 0.4.0 removed put ClassEve between the
person and the provider, and a client still calling it met a 404 and could not
start a session.

**Verified against.** `LocalKeyBootstrapRepositoryTest` and
`GeminiAuthTokenShapeTest`.

**If regressed.** Keys or audio pass through ClassEve, which the privacy policy
says never happens.
