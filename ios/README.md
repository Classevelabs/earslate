# Earslate for iOS

Native SwiftUI client for the free, account-free Earslate live translator.

## Status

- iOS 16+ XcodeGen project for iPhone and iPad.
- No login, pairing, subscription, refresh token, or API-key entry.
- Provider preference: Automatic, Gemini, or OpenAI. The current iOS listening flow translates nearby speech into the selected target language.
- Short-lived credentials from `POST https://api.classeve.com/v1/earslate/session`.
- Direct device-to-provider audio WebSockets; credentials are never persisted.
- 16 kHz PCM16 microphone capture, with 24 kHz resampling for OpenAI and 24 kHz playback.
- Bounded reconnect attempts after an interrupted live connection.

## Build and verify on macOS

```bash
brew install xcodegen
cd earslate-ios
xcodegen generate
xcodebuild -project EarslateiOS.xcodeproj -scheme Earslate \
  -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

Select a real Apple development team before running on a physical device or archiving. Physical-device testing is required for microphone permission, speaker/earbud routing, interruptions, background audio, latency, and provider audio quality.

## Privacy

The app sends captured audio directly to the selected translation provider only while the session is active. The ClassEve broker sees only an anonymous install UUID, provider choice, and target language while minting a credential; it does not receive audio or transcripts.

## License

Apache-2.0. See [`../LICENSE`](../LICENSE).
