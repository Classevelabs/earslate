# earslate — Play Console listing (source of truth)

Package `com.classeve.earslate` · **free, no billing, no accounts** · minSdk 29 · target/compile SDK 36.

> This file was rewritten 2026-07-25. The previous version described the
> pre-0.2.0 product: Paddle billing, a paid tier, account deletion, and Google
> as the only audio recipient. All four were wrong for the shipping app and the
> last two are Data-safety declarations, so they are compliance-relevant, not
> cosmetic. See "What changed and why it matters" at the bottom.

## Current build

| | |
|---|---|
| Version | **0.3.1 (versionCode 14)** |
| Status | uploaded to Play **alpha + internal** (2026-07-24, target-API-36 pass) |
| Artifact | `D:\Claude\release-builds-2026-07-24\earslate-release-vc14-0.3.1-api36.aab` |
| Signing | release keystore, coordinates in `local.properties` (never on repo); `preReleaseBuild` fails closed without them |

Store assets live beside this file in `play-store-assets/`, and duplicates are
in `D:\Claude\earslate-play-upload\` (icon 512, feature graphic 1024×500,
5 phone screenshots 1080×1920).

## How the app actually works (this drives every declaration below)

- No sign-in, no account, no subscription, no in-app purchase, no user-supplied
  API key. The app is free and fully functional on first launch.
- `POST /v1/earslate/session` on the ClassEve Worker mints a **single-use,
  short-lived** provider credential. The Worker is a broker only.
- The device then opens a WebSocket **directly to the provider**. Audio never
  traverses ClassEve infrastructure and is never stored by ClassEve.
- The provider is **Gemini _or_ OpenAI**, chosen per session (user preference in
  Settings, default Automatic). Both are live paths — the declarations must
  cover both.
- Each install generates a random UUID used only for anonymous rate limiting and
  as OpenAI's safety identifier. It is not an account and grants no entitlement.
- The microphone is open only while a session is running.

## Store listing

- **App name:** `earslate`
- **Default language:** English (United States) · **App or game:** App · **Free/paid:** Free
- **Short description (≤80):**
  `Hear nearby speech translated into your language, live. 150+ languages.`
- **Full description:**
```
earslate turns your phone into a live translation earpiece. Start a session, and the speech happening around you is translated into your language in real time — read it on screen or hear it through your earbuds.

HOW IT WORKS
• Tap to start a live session
• earslate listens to nearby speech and streams the translation back instantly
• Read along on screen, or route the spoken translation to your earbuds
• Works across 150+ language pairs

FREE, AND NO ACCOUNT
earslate is free. There is no sign-up, no subscription, and nothing to configure — open it and start.

GREAT FOR
• Travel — follow signs, announcements, and conversations abroad
• Meetings and lectures in another language
• Talking with people who don't share your language
• Everyday moments where you just need to understand

DESIGNED TO BE HONEST ABOUT PRIVACY
earslate is a cloud translation tool. While a session is active, ambient audio is streamed over an encrypted connection directly to a third-party AI translation provider (Google or OpenAI) that performs the translation. ClassEve never receives or stores your audio. The provider may retain audio under its own API terms, so please don't use earslate for confidential or sensitive conversations. Full detail: https://classeve.com/privacy

The microphone is only active while a session is running. You can stop at any time.

By ClassEve.
```
- **Category (primary):** Communication · Tags: Translator, Translation, Speech, Languages, Live captions
- **Contact:** website `https://classeve.com` · email `support@classeve.com`
- **Privacy policy:** `https://classeve.com/privacy`

## App content / declarations

- **App access:** All functionality is available without special access. No login exists.
- **Ads:** No.
- **Content rating (IARC):** Utility / Communication. No violence / sexual content /
  profanity / drugs / gambling. No user-generated content hosting or social sharing.
  **Digital purchases: No.** Expected: Everyone/Teen.
- **Target audience:** 18 and over (the app captures ambient third-party speech;
  conservative, and avoids child-data scrutiny).
- **Data safety:**
  - Collects/shares data: **Yes**. Encrypted in transit: **Yes**.
  - **Audio → "Voice or sound recordings"**: Collected **Yes**, Shared **Yes**
    — with **Google _and_ OpenAI**, for translation. Purpose: App functionality.
    *Not* marked ephemeral (a provider may retain under its own API terms).
  - **Device or other IDs**: Collected — the anonymous install UUID, for abuse
    prevention and the provider safety identifier. Not shared as an advertising ID.
  - **No** Personal info, **no** email, **no** financial info, **no** app-activity
    analytics. There is no account to collect them against.
  - Deletion: no account exists, so there is nothing to delete server-side;
    uninstalling clears the install UUID. Support: support@classeve.com.
- **News app:** No · **COVID:** No · **Government:** No · **Financial:** No · **Health:** No
- **Pricing:** Free. No Google Play Billing, no in-app products, no external subscription.

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

- Track: alpha + internal. Promotion to production is a founder decision.
- Release notes:
```
Live speech translation across 150+ language pairs, with on-screen captions and earbud audio output. Free, no account needed. Microphone active only during a session.
```
- Testers: the existing ClassEve tester list (same emails as Lven/Folio internal tracks).

## What changed and why it matters

The previous revision of this file was written for the original paid product and
was never updated through two product pivots (paid+accounts → bring-your-own-key
→ free broker-minted). It claimed:

1. *"billing is external (Paddle web)"* and *"paid tier is just higher limits"* —
   there is no billing. `00052_remove_earslate.sql` dropped earslate from the
   subscriptions product enum entirely.
2. *"Digital purchases = Yes"* in the IARC questionnaire — wrong, and it changes
   the rating questionnaire.
3. *"Personal info → Email (only when linking a paid account)"* and
   *"Deletion available (account deletion)"* — there is no account.
4. *Audio shared with "Google"* only — the app has shipped an **OpenAI** path
   since 0.3.0/vc13. A Data safety form that omits a real recipient of
   microphone audio is the kind of mismatch Play enforces against.

Item 4 is the one worth acting on first: verify the live Data safety form in the
Console lists OpenAI alongside Google before promoting past internal testing.
