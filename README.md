# earslate

Live speech translation for Android, running directly between your phone and
the AI provider you choose. No account, no subscription, and no server of ours
anywhere in the path.

Point it at a conversation, put an earbud in, and hear the other person in your
language while they are still speaking.

## How it works

You supply an API key for **Google Gemini** or **OpenAI**. earslate uses it,
once, over HTTPS, to mint a short-lived single-use session credential. The
phone then opens a WebSocket **straight to the provider** with that credential
and streams audio over it.

That means:

- **Your audio never reaches us.** There is nothing to reach — earslate has no
  backend. Not a proxy, not a broker, not a relay.
- **Your key never goes on the socket.** Only the short-lived credential does,
  so the long-lived key is never sitting on an open connection.
- **Your usage is yours.** Sessions are billed to your own provider account, at
  the provider's own rates. We never see them.

Gemini runs one leg per direction, so two people can talk normally; each leg
stays silent unless the speaker is using the other language. OpenAI's
translation endpoint has a single output language and no echo suppression, so
it runs single-leg by design.

## Getting a key

In the app: **Settings → API keys**, or the setup screen on first launch. It
walks you through it and opens the right console for you.

- Gemini — [Google AI Studio](https://aistudio.google.com/apikey). Keys start `AIza`.
- OpenAI — [API keys](https://platform.openai.com/api-keys). Keys start `sk-`.
  The account needs billing enabled or live translation is refused.

The key is checked against the provider before it is saved, so a wrong or
unfunded key fails at setup rather than in the middle of a conversation.

## Where your key is kept

Encrypted with an AES-256-GCM key that lives in the Android keystore and never
leaves it — in StrongBox where the device has a security chip. The app stores
only ciphertext; without that device's keystore it is meaningless.

It is excluded from cloud backup and from device-to-device transfer, never
logged, and never rendered back to the screen in full.

If you remove your device credentials, Android destroys the keystore key. The
saved key becomes unreadable; the next time earslate needs it, it says so and
asks you to enter it again — that is the platform behaving correctly, not data
loss.

## Build

Requires JDK 17 and an Android SDK. Copy `local.properties.example` to
`local.properties`, set `sdk.dir`, then:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

There is nothing else to configure: no API keys, no service URLs, no secrets. A
debug build is a complete, working app the moment you add your own key at
runtime. Release builds additionally need signing coordinates in
`local.properties` — `verifyReleaseSigning` fails closed without them.

## Architecture

- Kotlin, Jetpack Compose, single activity.
- `security/` — `KeyVault` (AndroidKeyStore AES-GCM) and `ProviderKeys` (which
  providers exist, and the checks that name common paste mistakes).
- `bootstrap/` — `ProviderSessionMinter` performs the credential exchange with
  Google or OpenAI; `LocalKeyBootstrapRepository` picks a provider and falls
  back to the second when the first refuses.
- `live/` — WebSocket transport and the provider wire protocols.
- `audio/` — capture at 16 kHz in 100 ms batches; playback through an adaptive
  jitter buffer that starts at 40 ms, buys latency only when the network forces
  it, and gives it back after a sustained clean run.
- `session/` — `SessionCoordinator` owns session lifecycle, the half-duplex mic
  gate on speaker routes, and reconnection.
- `ui/` — onboarding, key setup, main, settings, help, diagnostics.

No dependency-injection framework: `EarslateRuntime` is a plain holder of
process singletons.

## Privacy

No analytics SDK, no crash reporter, no advertising identifier, and no network
call to any ClassEve service — the app has no address for one. The only
outbound traffic is to the provider you chose.

An install-scoped random UUID is generated locally and sent, hashed, as
OpenAI's safety identifier. It attributes abuse signals to a device rather than
to your whole OpenAI account. It is not an account, identifies no person, and
is excluded from backup.

Diagnostics are opt-in and never leave the device.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports go to
**security@classeve.com** — please read [SECURITY.md](SECURITY.md) first.

## Licence

Apache-2.0. See [LICENSE](LICENSE).

Built by [ClassEve](https://classeve.com).

> **Official repository.** This is the only official repository for earslate.
> ClassEve's complete list of official accounts is at
> [classeve.com/official](https://classeve.com/official).
> The GitHub account `github.com/ClassEve` is an unrelated third party, not
> affiliated with ClassEve.
