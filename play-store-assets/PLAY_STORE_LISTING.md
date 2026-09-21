# earslate — Play listing

Package `com.classeve.earslate`. The listing text, in one format shared by
every app: this file is what `Website/scripts/apply-store-listings.mjs`
writes to the Console and what `check:listings` compares the live page
against. Edit here, apply, verify. The Console is not the source of truth;
this file is.

## App name

```
earslate
```

## Short description

```
Live speech translation with your own Google Gemini key. 150+ languages.
```

## Full description

```
earslate turns your phone into a live translation earpiece. Start a session and the speech around you is translated into your language in real time — read it on screen or hear it through your earbuds.

YOU BRING THE KEY
earslate has no account and no subscription, and ClassEve runs no server in this path. You add your own Google Gemini API key once, and the app talks straight to Google. Sessions are billed to your own Google account, at Google's rates.

HOW IT WORKS
• Add your key once in Settings
• Start a session with a tap or the Quick Settings tile
• Read the translation on screen, or route it to your earbuds
• Works across 150+ language pairs

YOUR KEY, YOUR AUDIO
Your key is sealed by the Android keystore and goes only to Google, over HTTPS. While a session is active, ambient audio streams over an encrypted connection directly to Google Gemini; ClassEve never receives your audio. Google handles that audio under its own terms, so do not use earslate for confidential conversations. Details: https://classeve.com/privacy

The microphone is active only while a session is running.

Free, and open source: github.com/Classevelabs/earslate

By ClassEve.
```

## Store presence

- Category: Communication. Tags: Translator, Translation, Speech, Languages, Live captions.
- Contact: website `https://classeve.com`, email `contact@classeve.com`, privacy policy `https://classeve.com/privacy`.
- Icon `earslate-play-icon-512.png` and feature graphic `earslate-feature-graphic-1024x500.png` in this directory; `render-assets.py` regenerates them.
- App access: restricted. A session needs the reviewer's own Google Gemini key, entered in Settings; the reviewer instructions in the Console say so and name the in-app path.
- Data safety: audio is shared with Google Gemini for translation, encrypted in transit, not marked ephemeral. The user's key stays on the device and goes only to Google. ClassEve collects nothing.
- Content rating: everyone. Ads: none. Target audience: 18 and over.
- The in-app prominent disclosure (`R.string.audio_disclosure_body`, enforced at `TranslatorService.onStartCommand`) names Google Gemini; that string, the data-safety declaration and the full description all name the same provider and move together.
