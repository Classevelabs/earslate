# earslate — Play Console listing (source of truth)

Package `com.classeve.earslate` · **free, no billing, no accounts, no backend** · minSdk 29 · target/compile SDK 36.

> Rewritten **2026-08-13** for 0.4.3. The previous revision described 0.3.1/vc14:
> a hosted session broker at `POST /v1/earslate/session`, and "no user-supplied
> API key". Both statements stopped being true in **0.4.0**, when the broker was
> deleted and the app became bring-your-own-key. The Data safety declarations
> and the store description were both derived from that stale description, so
> this was a live compliance divergence, not a documentation lag. See
> "What changed and why it matters".

## Current build

| | |
|---|---|
| Version | **0.4.3 (versionCode 18)** |
| Architecture | **Bring-your-own-key. No ClassEve backend of any kind.** |
| Play status | **0.3.1/vc14 on alpha + internal** — four versions behind, and that build calls a route that now 404s. See "Release". |
| Signing | **Brand keystore** `_secrets/classeve-brand.jks`, alias `earslate`, DN `CN=Earslate, O=ClassEve, C=IN`, RSA 4096. **This is a NEW key — see the warning below.** |
| Gates | `verifyReleaseSigning` (fails closed without signing coordinates) and `verifyReleaseIdentity` (fails on any non-brand DN or entity string in the APK bytes) |

> ⚠️ **The signing key changed on 2026-08-13 and this breaks the update path.**
> Every build up to and including the 0.4.3 APK currently served from
> classeve.com was signed with `earslate-release.keystore`, whose DN embedded
> an `O=` beyond the brand plus `L=` and `ST=` fields — the legal entity, a city
> and a state, all three forbidden by INTERNAL-RULES §2, all three present in
> the raw bytes of every published APK. It went unnoticed for months because
> `strings` and `keytool -printcert -jarfile` both report a dirty APK CLEAN
> (the DN is DER-encoded in the v2 block and these builds carry no v1
> signature). A raw-byte scan is the only thing that finds it.
>
> Consequences that must be decided before shipping:
> - An install signed with the old key **cannot be updated** by the new one.
>   Existing alpha/internal testers must uninstall and reinstall.
> - If the Play listing is **not** enrolled in Play App Signing, the app signing
>   key cannot be changed at all and this needs a key-reset request to Google.
>   **Confirm enrolment in the Console before uploading.**
> - The blast radius is small now (alpha + internal only) and grows with every
>   day the app stays published. This is the cheapest it will ever be to fix.

## How the app actually works (this drives every declaration below)

- No sign-in, no account, no subscription, no in-app purchase.
- **The user supplies their own Google Gemini or OpenAI API key.** It is entered
  in-app (Settings → API keys, or the first-launch setup screen) and stored
  encrypted with an AES-256-GCM key held in the Android keystore (StrongBox
  where available). It is excluded from backup and device-to-device transfer.
- That key is used **once, over HTTPS, directly to the provider**, to mint a
  short-lived single-use session credential. The phone then opens a WebSocket
  **straight to the provider** carrying only that credential.
- **There is no ClassEve server anywhere in the path.** Not a proxy, not a
  broker, not a relay. `/v1/earslate/session` was deleted and returns 404.
- Sessions are billed to the user's own provider account at the provider's rates.
- The provider is **Gemini _or_ OpenAI** — user preference in Settings, default
  Automatic. Both are live paths; the declarations must cover both.
- An install-scoped random UUID is generated locally and sent, **hashed**, as
  OpenAI's `OpenAI-Safety-Identifier`. It is not an account and identifies no
  person. It is never sent to ClassEve, because there is nowhere to send it.
- The microphone is open only while a session is running.

## ⚠️ App access — this will block review if not handled

**Changed in 0.4.0 and not yet reflected in the Console.** The listing currently
declares *"All functionality is available without special access."* That is no
longer true: a reviewer who installs earslate and presses start gets the key
setup screen, and **cannot translate anything without supplying their own Gemini
or OpenAI API key.**

Play requires working access instructions for any functionality behind a
credential, including third-party ones. Before the next submission, the
**App access** section must be updated to one of:

- **Preferred:** provide a reviewer-only Gemini API key in the App access
  instructions, with a note that the key is the reviewer's to enter in
  Settings → API keys and that usage bills to the key's owner. Rotate it after
  review.
- Or: written instructions explaining that the app requires the reviewer to
  supply their own provider key, with the console URL and the exact in-app path.

Submitting without this is the most likely cause of a rejection, and it will look
like an unrelated "app doesn't work" finding.

## Store listing

- **App name:** `earslate`
- **Default language:** English (United States) · **App or game:** App · **Free/paid:** Free
- **Short description (≤80):**
  `Live speech translation with your own Gemini or OpenAI key. 150+ languages.`
- **Full description:**
```
earslate turns your phone into a live translation earpiece. Start a session, and the speech happening around you is translated into your language in real time — read it on screen or hear it through your earbuds.

YOU BRING THE KEY
earslate has no account and no subscription, and it runs no server of its own. You supply your own Google Gemini or OpenAI API key, and the app talks straight to that provider. Sessions are billed to your account, at your provider's rates. Setup takes about a minute and the app walks you through it.

HOW IT WORKS
• Add your Gemini or OpenAI key once, in Settings
• Tap to start a live session, or use the Quick Settings tile
• earslate listens to nearby speech and streams the translation back
• Read along on screen, or route the spoken translation to your earbuds
• Works across 150+ language pairs

YOUR KEY, YOUR AUDIO
Your key is sealed by the Android keystore, and is excluded from backups and device-to-device transfer. It goes to one place and one place only: the provider that issued it, over HTTPS, once per session, to mint a short-lived session credential — so your long-lived key never sits on an open connection. ClassEve has no server in this path and never receives your audio.

GREAT FOR
• Travel — follow signs, announcements, and conversations abroad
• Meetings and lectures in another language
• Talking with people who don't share your language

DESIGNED TO BE HONEST ABOUT PRIVACY
earslate is a cloud translation tool. While a session is active, ambient audio is streamed over an encrypted connection directly to the third-party AI translation provider you chose (Google or OpenAI). ClassEve never receives or stores your audio. The provider may retain audio under its own API terms, so please don't use earslate for confidential or sensitive conversations. Full detail: https://classeve.com/privacy

The microphone is only active while a session is running. You can stop at any time.

By ClassEve.
```
- **Category (primary):** Communication · Tags: Translator, Translation, Speech, Languages, Live captions
- **Contact:** website `https://classeve.com` · email `support@classeve.com`
- **Privacy policy:** `https://classeve.com/privacy`

Store assets live beside this file in `play-store-assets/` (icon 512, feature
graphic 1024×500, 5 phone screenshots 1080×1920).

> **Screenshots need re-shooting.** The current five were captured from the
> broker build, which had no key-setup step. A listing whose screenshots skip
> the one screen that gates all functionality is both a review risk and the
> reason an installer would call the app broken.

## App content / declarations

- **App access:** **Restricted — requires a user-supplied third-party API key.**
  See the section above. This is a change from the previous declaration.
- **Ads:** No.
- **Content rating (IARC):** Utility / Communication. No violence / sexual content /
  profanity / drugs / gambling. No user-generated content hosting or social sharing.
  **Digital purchases: No.** Expected: Everyone/Teen.
- **Target audience:** 18 and over (the app captures ambient third-party speech;
  conservative, and avoids child-data scrutiny).
- **Data safety:**
  - Collects/shares data: **Yes**. Encrypted in transit: **Yes**.
  - **Audio → "Voice or sound recordings"**: Collected **Yes**, Shared **Yes**
    — with **Google _and_ OpenAI**, for translation, using the user's own
    account with that provider. Purpose: App functionality.
    *Not* marked ephemeral (a provider may retain under its own API terms).
  - **Device or other IDs**: Collected **Yes**, Shared **Yes** — the hashed
    install UUID, sent to OpenAI only, as its safety identifier. Purpose: fraud
    prevention / abuse. Not an advertising ID. Not sent to ClassEve.
  - **API key**: the user's provider credential is stored on-device only. It is
    transmitted **only to the issuing provider** (Google or OpenAI) to mint a
    session. It is never transmitted to ClassEve and never leaves the device for
    any other destination. Declare under App info / "Other" if the Console
    requires a category; do **not** declare it as collected by ClassEve, because
    it is not.
  - **No** Personal info, **no** email, **no** financial info, **no** app-activity
    analytics, **no** crash reporting, **no** advertising ID. There is no account
    to collect them against and no analytics SDK in the build.
  - Deletion: no account exists, so there is nothing to delete server-side.
    Uninstalling clears the encrypted key and the install UUID.
    Support: support@classeve.com.
- **News app:** No · **COVID:** No · **Government:** No · **Financial:** No · **Health:** No
- **Pricing:** Free. No Google Play Billing, no in-app products, no external subscription.

## Permissions

Requested at runtime: `RECORD_AUDIO`, and `POST_NOTIFICATIONS` on API 33+.
Declared: plus `INTERNET`, `ACCESS_NETWORK_STATE`, `MODIFY_AUDIO_SETTINGS`,
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.

`BLUETOOTH_CONNECT` was **removed in 0.4.4**. It had been declared and requested
on API 31+ since the first release and the app never called a single Bluetooth
API — routing to earbuds goes through ordinary media routing, and the route
monitor reads `AudioDeviceInfo.getType()`, which needs no permission. It was a
sensitive-permission prompt, and a Data safety line item, for a capability that
was never exercised. Do not re-add it without a call site.

## In-app disclosure (Play Prominent Disclosure & Consent)

Required because audio leaves the device. Implemented as a blocking dialog
before the **first** capture, on **every** entry point:

- Copy: `R.string.audio_disclosure_body` — names Gemini **and** OpenAI explicitly.
- Consent is recorded in `OnboardingPrefs.KEY_AUDIO_DISCLOSURE`.
- Enforced at `TranslatorService.onStartCommand(ACTION_START)`, which is the
  single choke point every start passes through (button, Quick Settings tile,
  and notification action all converge there). The service bounces to
  `MainActivity` to present the dialog, since a Service has no window.

**If the provider set ever changes, three things move together:** that string,
the Data safety declaration above, and the full description.

## Release

- **Play is on 0.3.1/vc14 (alpha + internal), and that build is dead.** It mints
  its credential from `POST /v1/earslate/session`, which was deleted in 0.4.0
  and returns 404. Every alpha and internal tester currently has an app that
  cannot start a session. Shipping 0.4.3+ to those tracks is the fix.
- Promotion to production is a founder decision, and per the Play production
  gate it additionally requires the Console App-content declarations to be
  completed by hand — there is no API for them.
- Release notes:
```
Live speech translation across 150+ language pairs, with on-screen captions and earbud audio output. Bring your own Gemini or OpenAI key — no account, no subscription. Microphone active only during a session.
```
- Testers: the existing ClassEve tester list (same emails as Lven/Folio internal tracks).

## What changed and why it matters

The revision before this one was written on 2026-07-25 for **0.3.1/vc14** and
described the hosted-broker product. Three of its statements were load-bearing
and wrong for the shipping app:

1. *"No user-supplied API key. The app is free and fully functional on first
   launch."* — The app has required the user's own provider key since **0.4.0**.
   This is what makes the **App access** declaration wrong, and it is the single
   most likely cause of a review rejection.
2. *"`POST /v1/earslate/session` on the ClassEve Worker mints a single-use,
   short-lived provider credential. The Worker is a broker only."* — That route
   was deleted. The Worker source contains no earslate handler and migration
   `00052_remove_earslate.sql` removed the product. Minting is now on-device.
3. *"Each install generates a random UUID used only for anonymous rate limiting
   and as OpenAI's safety identifier."* — There is no rate limiting, because
   there is no server to rate-limit at. The UUID's only remaining use is the
   OpenAI safety identifier.

The store's full description also promised *"There is no sign-up, no
subscription, and nothing to configure — open it and start."* The last clause
was false for every install since 0.4.0, and it is the sentence a user reads
immediately before hitting a screen asking for an API key.

**Order of operations before the next submission:**
1. Confirm Play App Signing enrolment (decides whether the key change is even possible).
2. Update **App access** with reviewer key or instructions.
3. Update **Data safety** per the section above.
4. Replace the full description and short description.
5. Re-shoot screenshots to include key setup.
6. Upload 0.4.4 to internal, verify a real session end-to-end on a device, then promote.
