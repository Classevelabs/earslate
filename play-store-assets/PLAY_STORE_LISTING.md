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
Live speech translation with your own Gemini or OpenAI key. 150+ languages.
```

## Full description

```
earslate turns your phone into a live translation earpiece. Start a session and the speech around you is translated into your language in real time — read it on screen or hear it through your earbuds.

YOU BRING THE KEY
earslate has no account and no subscription, and ClassEve runs no server in this path. You add your own Google Gemini or OpenAI API key once, and the app talks straight to that provider. Sessions are billed to your provider account at your provider's rates.

HOW IT WORKS
• Add your key once in Settings
• Start a session with a tap or the Quick Settings tile
• Read the translation on screen, or route it to your earbuds
• Works across 150+ language pairs

YOUR KEY, YOUR AUDIO
Your key is sealed by the Android keystore and goes only to the provider that issued it, over HTTPS. While a session is active, ambient audio streams over an encrypted connection directly to the provider you chose; ClassEve never receives your audio. The provider handles that audio under its own terms, so do not use earslate for confidential conversations. Details: https://classeve.com/privacy

The microphone is active only while a session is running.

Free.

By ClassEve.
```

## Store presence

- Category: Communication. Tags: Translator, Translation, Speech, Languages, Live captions.
- Contact: website `https://classeve.com`, email `contact@classeve.com`, privacy policy `https://classeve.com/privacy`.
- Icon `earslate-play-icon-512.png` and feature graphic `earslate-feature-graphic-1024x500.png` in this directory; `render-assets.py` regenerates them.
- App access: restricted. A session needs the reviewer's own Gemini or OpenAI key, entered in Settings; the reviewer instructions in the Console say so and name the in-app path.
- Data safety: audio is shared with the provider the user chose (Google or OpenAI) for translation, encrypted in transit, not marked ephemeral; a hashed install id goes to OpenAI only, as its safety identifier. The user's key stays on the device and goes only to the provider that issued it. ClassEve collects nothing.
- Content rating: everyone. Ads: none. Target audience: 18 and over.
- The in-app prominent disclosure (`R.string.audio_disclosure_body`, enforced at `TranslatorService.onStartCommand`) names both providers; if the provider set changes, that string, the data-safety declaration and the full description move together.
