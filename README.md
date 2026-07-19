# earslate

Account-free live speech translation for Android and iOS. Earslate captures speech only while a session is active, streams it directly to the selected provider, and plays translated audio with live captions.

The app is free to use and has no sign-in, subscription, or user-supplied API key. A small ClassEve broker returns single-use, short-lived Gemini or OpenAI credentials; audio never traverses the broker.

## Android build

Requires JDK 17 and an Android SDK. Copy `local.properties.example` to `local.properties`, set `sdk.dir`, then run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The optional `EARSLATE_WORKER_URL` property selects a staging broker. It is a public URL, not a secret. Never place provider API keys in the mobile project.

## Runtime architecture

- Native Kotlin/Compose Android client and native SwiftUI iOS client.
- Automatic, Gemini, or OpenAI provider preference in Settings.
- `POST /v1/earslate/session` mints a short-lived provider credential without a user account.
- Device-to-provider WebSockets keep audio out of ClassEve infrastructure.
- Gemini Live Translate uses constrained `v1alpha` ephemeral tokens, 16 kHz PCM16 input, and 24 kHz PCM16 output.
- OpenAI Realtime Translate uses translation client secrets and 24 kHz PCM16 WebSocket input/output.
- Android keeps the foreground microphone service and Quick Settings tile.
- Credentials are held in memory for one session and never persisted.

## Authentication and privacy

There is no user authentication. Each installation generates a random UUID used only for anonymous rate limiting and the provider safety identifier. It is not an account and carries no entitlement. The broker stores no audio or transcript data.

Production deployment requires `GEMINI_API_KEY` and/or `OPENAI_API_KEY` as Cloudflare Worker secrets. Rotate any key ever pasted into chat or another plaintext channel before deployment.

## License

The Earslate Android and iOS source is licensed under Apache-2.0; see [`../LICENSE`](../LICENSE).
