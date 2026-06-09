# earslate

Always-available live speech translator for Android. Listens continuously, translates nearby speech into the user's native language, and plays the translated audio through earbuds or speaker.

A separate ClassEve product with its own subscription — not bundled into Lven Instant or Lven Cloud. Powered by **Google Gemini Live** over a direct device-to-Live WebSocket. Pricing page: [`classeve.com/releases/earslate/pricing`](https://classeve.com/releases/earslate/pricing) (coming soon).

## Status

- Android: live. APK at [`classeve.com/downloads/Earslate.apk`](https://classeve.com/downloads/Earslate.apk).
- iOS: source-ready (see [`../earslate-ios/`](../earslate-ios/)); shipping status TBD pending macOS build + App Store review.

## Quick start

Requires JDK 17 and an Android SDK with platform 34 + build-tools 34.0.0.

```bash
cp local.properties.example local.properties
# edit local.properties: set sdk.dir and (for dev) GEMINI_API_KEY
./gradlew assembleDebug
```

Install the resulting APK on a device running Android 10+ (API 29).

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
  bootstrap/   SessionBootstrapRepository + local dev stub + remote interface
  live/        LiveSocketClient, LiveSessionConfigFactory, LiveMessageParser, LiveEvent
  session/     SessionCoordinator, ReconnectManager, RuntimeState, state machine
  service/     TranslatorService (foreground), TranslatorTileService (Quick Settings), NotificationFactory
  settings/    AppSettings + preferences
  metrics/     Local metrics store (debug diagnostics only)
  ui/          MainActivity, Compose screens, design-token theme
```

## Auth

Sign-in goes through the **ClassEve Worker** via the RFC 8628 device-authorization grant — same pairing flow as Lven. On first launch the user opens [classeve.com/link](https://classeve.com/link) with the displayed code, signs in, and tokens land in EncryptedSharedPreferences. The Worker mints short-lived Gemini Live credentials per session — **no raw API keys ship in the release APK**. `local.properties` still holds a `GEMINI_API_KEY` for `assembleDebug` builds only; release builds force the field empty (see `app/build.gradle.kts`).

Daily usage caps are enforced by `TranslateUsageReporter` heartbeating `/v1/earslate/heartbeat` every 60 seconds; on `429 DAILY_LIMIT_REACHED` the session stops and the UI surfaces a "Daily limit reached" banner.
