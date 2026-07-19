# earslate — Play Console submission (source of truth)

Package `com.classeve.earslate` · first Play submission · billing is **external (Paddle web)** → Play app is a **free download, no Google Play Billing**. Mirrors how `com.lven.cloud` / `com.lven.assist` are already configured.

## Upload files (all in `D:\Claude\earslate-play-upload\`)
| Asset | Spec | File |
|---|---|---|
| App bundle | AAB, release-signed (CN=Earslate) | `earslate-0.3.0-vc13-release.aab` (vc13 / 0.3.0) |
| App icon | 512×512 PNG | `earslate-play-icon-512.png` (real adaptive launcher icon: cream brackets on ember `#C2410C`) |
| Feature graphic | 1024×500 PNG | `earslate-feature-graphic-1024x500.png` |
| Phone screenshots | 5 × 1080×1920 | `earslate-screenshot-01..05.png` |

## Store listing
- **App name:** `earslate`
- **Default language:** English (United States)  ·  **App or game:** App  ·  **Free/paid:** Free
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

GREAT FOR
• Travel — follow signs, announcements, and conversations abroad
• Meetings and lectures in another language
• Talking with people who don't share your language
• Everyday moments where you just need to understand

DESIGNED TO BE HONEST ABOUT PRIVACY
earslate is a cloud translation tool. While a session is active, ambient audio is streamed over an encrypted connection to a third-party AI translation provider (Google) that performs the translation. ClassEve never receives or stores your audio. On the current standard API tier the provider may retain audio under its own API terms, so please don't use earslate for confidential or sensitive conversations. Full detail: https://classeve.com/privacy

The microphone is only active while a session is running. You can stop at any time.

By ClassEve.
```
- **Category (primary):** Communication  ·  Tags: Translator, Translation, Speech, Languages, Live captions
- **Contact:** website `https://classeve.com` · email `support@classeve.com`
- **Privacy policy:** `https://classeve.com/privacy`  (already discloses earslate audio→Google flow + sensitive-use warning)

## App content / declarations
- **App access:** All functionality available without special access (free tier translates without login; paid tier is just higher limits via standard purchase).
- **Ads:** No.
- **Content rating (IARC):** Category = Utility / Communication. No violence / sexual / profanity / drugs / gambling. No user-generated content hosting or social sharing. Digital purchases = Yes (external subscription). Expected: Everyone/Teen.
- **Target audience:** 18 and over (app captures ambient/third-party speech; conservative + avoids child-data scrutiny). *Adjustable if founder wants 13+.*
- **Data safety:**
  - Collects/shares data: **Yes**. Encrypted in transit: **Yes**. Deletion available: **Yes** (account deletion / support@classeve.com).
  - **Audio → "Voice or sound recordings"**: Collected **Yes**, Shared **Yes** (Google, translation). Purpose: App functionality. *Not* marked ephemeral (provider may retain on standard tier).
  - **App activity → "App interactions"**: Collected (usage metering for limits). App functionality. Not shared.
  - **Device or other IDs**: Collected (anonymous device/session token). App functionality. Not shared.
  - **Personal info → Email** (only when linking a paid account): Collected, Account management. Not shared.
- **News app:** No · **COVID:** No · **Government:** No · **Financial:** No · **Health:** No
- **Pricing:** Free. No Google Play in-app products (subscriptions are external via Paddle — mirror `com.lven.cloud`).

## Release
- Track: **Internal testing** first (new app). Release name `0.3.0 (13)`.
- Release notes:
```
First earslate test build. Live speech translation across 150+ language pairs, with on-screen text and earbud audio output. Microphone active only during a session.
```
- Testers: reuse the existing ClassEve tester list (same emails as Lven/Folio internal tracks).

## HELD for explicit founder go
Everything is set up and uploaded; the final **"Send for review" / publish** to a live testing track is the only irreversible step and is held until the founder says go.
