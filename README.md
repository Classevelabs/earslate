# earslate

Always-available live speech translator for Android. Listens continuously, translates nearby speech into the user's native language, and plays the translated audio through earbuds or speaker.

Part of the **ClassEve** product family alongside [Lven](https://classeve.com).

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

Authentication integration with **classeve.com** is deferred. v1 runs on a local-dev bootstrap that reads `GEMINI_API_KEY` from `local.properties`. When auth lands we swap in a `RemoteBootstrapRepository` that mints ephemeral Live credentials through the ClassEve Worker — no raw API keys in the APK.
