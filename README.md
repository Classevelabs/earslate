# earslate

Live speech translation for Android, running directly between your phone and
the AI provider you choose. No account, no subscription, and no server of ours
anywhere in the path.

Point it at a conversation, put an earbud in, and hear the other person in your
language while they are still speaking.

## How it works

You supply an API key for **Google Gemini** or **OpenAI**. earslate trades it,
over HTTPS, for a short-lived session credential. The phone then opens its
connections **straight to that provider** with the credential and streams audio
over them.

That means:

- **Your audio never reaches us.** There is nothing to reach — earslate has no
  backend. Not a proxy, not a broker, not a relay.
- **Your key never goes on the socket.** Only the short-lived credential does,
  so the long-lived key is never sitting on an open connection.
- **Your usage is yours.** Sessions are billed to your own provider account, at
  the provider's own rates. We never see them.

A conversation runs two translation sessions on one microphone, one for each
direction. Both hear everything, and the one aimed at the language being spoken
has nothing to translate: the app keeps it quiet when it says back what it
heard, so nobody gets their own words repeated. The other person's language is
worked out by listening — you only pick your own — and what you say is
translated for them once they have been heard, or once you set their language
yourself.

The screen shows the conversation as it goes: what they said on the left, what
you said on the right. Tap a caption to copy it, or copy all of it at once.

In earbuds the translation plays while the other person is still speaking. On
the phone's loudspeaker, where the microphone would hear it, the translation
waits for a pause.

## Getting a key

In the app: **Settings → API keys**, or the setup screen on first launch. It
walks you through it and opens the right console for you.

- Gemini — [Google AI Studio](https://aistudio.google.com/apikey).
- OpenAI — [API keys](https://platform.openai.com/api-keys). The account needs
  billing set up or live translation is refused. OpenAI translates into
  thirteen languages; Gemini covers more.

A key is checked before it is saved, by opening a real translation session with
it. A wrong or unfunded key fails at setup rather than in the middle of a
conversation.

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
  providers have a key, and the checks that name common paste mistakes).
- `bootstrap/` — `ProviderSessionMinter` performs the credential exchange with
  Google or OpenAI; `LocalKeyBootstrapRepository` mints from the stored key and
  reuses the credential while it has life left.
- `live/` — WebSocket transport, and one wire protocol per provider behind
  `TranslationLiveProtocol`.
- `audio/` — capture at the provider's own sample rate and frame size; playback
  on one continuous lane per direction, with a cushion that grows only when the
  network forces it and shrinks back after a sustained clean run.
- `session/` — `SessionCoordinator` owns the session lifecycle, replaces a
  connection before the provider closes it, and reconnects;
  `ConversationEngine` decides which direction may be heard; `FloorControl`
  takes turns with the speaker on a loudspeaker.
- `ui/` — onboarding, key setup, main, settings, help.

No dependency-injection framework: `EarslateRuntime` is a plain holder of
process singletons.

## Privacy

No analytics SDK, no crash reporter, no advertising identifier, and no network
call to any ClassEve service — the app has no address for one. The only
outbound traffic is to the provider you chose, for the translation you asked
for.

An install-scoped random UUID is generated locally and sent, hashed, as
OpenAI's safety identifier. It attributes abuse signals to a device rather than
to your whole OpenAI account. It is not an account, identifies no person, and
is excluded from backup.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports go to
**security@classeve.com** — please read [SECURITY.md](SECURITY.md) first.

## License

Apache-2.0. See [LICENSE](LICENSE).

Built by [ClassEve](https://classeve.com).

> **Official repository.** This is the only official repository for earslate.
> ClassEve's complete list of official accounts is at
> [classeve.com/official](https://classeve.com/official).
> The GitHub account `github.com/ClassEve` is an unrelated third party, not
> affiliated with ClassEve.
