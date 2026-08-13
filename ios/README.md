# earslate for iOS

The SwiftUI client. Same product as the Android app in `../app`, same wire
protocols, same architecture: **bring your own provider key, and no ClassEve
server anywhere in the path.**

## Status

**Code-complete and unshipped.** Building and testing need no Apple account and
run in CI on every change. Shipping to the App Store needs an Apple Developer
Program membership that does not exist yet, so the target is simulator-only and
unsigned on purpose.

**Nothing here has been compiled since the 0.4.4 rewrite.** The port off the
deleted broker was written on a machine with no Swift toolchain, so its first
real compile will be the next CI run on a macOS runner. Treat a red build as
expected work, not as a surprise.

## How a session starts

1. The user pastes their own Gemini or OpenAI key into `KeySetupView`.
2. `ProviderKeyVerifier` mints a real session with it before it is saved, so a
   bad key, an unfunded account, or a key without access to the translate model
   fails at setup rather than mid-conversation.
3. The key is stored in the Keychain as `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
   — device-only, so it is excluded from iCloud Keychain and from backups,
   matching Android's `allowBackup=false`.
4. `ProviderSessionMinter` exchanges it, once over HTTPS, for a short-lived
   single-use credential.
5. `LiveTranslationClient` opens a WebSocket straight to the provider carrying
   only that credential. The long-lived key never touches the socket.

## What changed in 0.4.4, and why it matters

This client used to POST to `https://api.classeve.com/v1/earslate/session`.
**That route was deleted in 0.4.0** when Android moved to bring-your-own-key:
the Worker has no earslate handler and `00052_remove_earslate.sql` dropped the
product. The endpoint answered 404, so the app could not start a single session.

It compiled. Its tests passed. Its CI was green. The wire-format tests assert
the *shape* of the setup frame and the build proves it compiles — neither has
any opinion about whether the credential source exists, so the green check
certified a dead app for weeks.

Removed: `AppConstants.swift` (the worker origin) and the broker `BootstrapClient`.
`BootstrapResponse` — a `Decodable` of the broker's JSON — became `SessionBootstrap`,
a plain struct built on-device, because a type that still knew how to decode a
server response was the last thread tying the app to a 404.

Added: `ProviderKeyStore.swift` (Keychain + the shallow key checks),
`KeySetupView.swift` (there was no way to enter a key at all), on-device minting
in `BootstrapClient.swift`, and `SessionMintShapeTests.swift`.

The iOS workflow now fails if any ClassEve endpoint reappears in Swift code.

## Two rules that are not style preferences

**Key formats are never validated.** Only unambiguous mistakes are refused — a
URL, a leftover `Bearer `, embedded whitespace, something under 8 characters.
Google changed its key prefix once and the Android app, which hardcoded `AIza`,
rejected perfectly valid keys with a confident wrong error until it shipped an
update. The provider is the only thing that knows.

**Setup-frame field placement is asymmetric.** Transcription sits on `setup`;
translation sits inside `setup.generationConfig`. Nesting transcription into
`generationConfig` closes the socket with 1007 and no translation ever happens.
Android shipped that mistake, measured it on-device, and fixed it; iOS carried
it for another month because nothing compared the two. `GeminiSetupFrameTests`
and `SessionMintShapeTests` now pin both sides.

## Build

```bash
brew install xcodegen
cd ios
xcodegen generate
xcodebuild test -project EarslateiOS.xcodeproj -scheme EarslateTests \
  -destination 'platform=iOS Simulator,name=iPhone 15' CODE_SIGNING_ALLOWED=NO
xcodebuild build -project EarslateiOS.xcodeproj -scheme Earslate \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO
```

`GENERATE_INFOPLIST_FILE: YES` in `project.yml` is load-bearing — without it
xcodegen writes `INFOPLIST_KEY_*` entries and Xcode 16 then looks for an
`Info.plist` that nothing generates.

## Before this can ship

- An Apple Developer Program membership, then signing and an App Store listing.
- A first successful compile — see Status.
- One real session on a device, against both providers.
