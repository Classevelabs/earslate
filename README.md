# earslate

Always-available live speech translator for Android. Listens continuously, translates nearby speech into the user's native language, and plays the translated audio through earbuds or speaker.

A free, standalone, bring-your-own-key ClassEve product — no account, no billing, no ClassEve server in the audio path. Powered by **Google Gemini Live** over a direct device-to-Live WebSocket using the user's own Gemini API key, stored encrypted on-device only.

## Status

- Android: live. APK at [`classeve.com/downloads/Earslate.apk`](https://classeve.com/downloads/Earslate.apk).
- iOS: source-ready (see [`../earslate-ios/`](../earslate-ios/)); shipping status TBD pending macOS build + App Store review.

## Quick start

Requires JDK 17 and an Android SDK with platform 34 + build-tools 34.0.0.

```bash
cp local.properties.example local.properties
# edit local.properties: set sdk.dir
./gradlew assembleDebug
```

Install the resulting APK on a device running Android 10+ (API 29). On first launch, enter your own Gemini API key (get one free at [aistudio.google.com/apikey](https://aistudio.google.com/apikey)) — it's stored encrypted on-device and used only to talk directly to Google's Gemini Live endpoint.

## Architecture

Full spec: [`../live_translator_kotlin_blueprint_v2.md`](../live_translator_kotlin_blueprint_v2.md).

- Native Kotlin Android app.
- Jetpack Compose UI, Material 3 theming, ClassEve design language (see `ui/theme/`).
- Direct device-to-Gemini-Live WebSocket. No backend in the audio hot path.
- Foreground microphone service + Quick Settings tile for one-tap start/stop.
- Listen mode only for v1.

## Layout

```
app/src/main/java/com/classeve/earslate/
  audio/       AudioCaptureEngine, AudioPlaybackEngine, VadGate, jitter buffer, route monitor
  bootstrap/   SessionBootstrapRepository + UserKeyBootstrapRepository (bring-your-own-key)
  live/        LiveSocketClient, LiveSessionConfigFactory, LiveMessageParser, LiveEvent
  session/     SessionCoordinator, ReconnectManager, RuntimeState, state machine
  service/     TranslatorService (foreground), TranslatorTileService (Quick Settings), NotificationFactory
  settings/    AppSettings + preferences
  metrics/     Local metrics store (debug diagnostics only)
  ui/          MainActivity, Compose screens, design-token theme
```

## Auth

There is none. earslate has no account, no sign-in, and no ClassEve server in the path at all. The user supplies their own Google Gemini API key on first launch (`ApiKeySetupScreen`); it is stored only in `EncryptedSharedPreferences` via `GeminiKeyStore`/`SecurePrefs` and used solely to open a direct WebSocket to Google's Gemini Live endpoint. ClassEve never receives or stores the key, and there is no usage metering, billing, or daily cap — the user pays Google directly for their own API usage.
