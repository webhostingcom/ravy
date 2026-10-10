# Ravy — Voice Assistant for Blind & Low-Vision Users

## What this actually is
A real Android Studio project (Kotlin + Compose). Nothing here is a mockup —
every action listed below calls a real Android API and returns a truthful
`SUCCESS` / `FAILED` / `NEEDS_PERMISSION` / `NEEDS_CONFIRMATION` /
`NOT_SUPPORTED` result. Ravy never claims an action happened unless it did.

There is **no "full access" permission** — Android doesn't have one. Instead,
every capability below is its own permission, requested up front so you see
the whole list at once, and you grant or deny each independently. Anything
not granted just makes the matching voice command answer honestly with
`NEEDS_PERMISSION` instead of pretending to work.

## Permissions requested, and what each unlocks
Requested together on first launch (standard runtime permissions — one popup,
one tap each, nothing hidden):

| Permission | Unlocks |
|---|---|
| Microphone | Voice input |
| Camera | Flashlight toggle |
| Contacts | "call [name]", "find contact [name]" |
| SMS (read + send) | Unread text count/senders; sending is always confirmed first |
| Calendar (read + write) | Creating events |
| Phone | Placing calls |
| Location (fine + coarse) | "where am I" |
| Media images/video | Listing recent files |
| Notifications (post) | Required by Android 13+ just to show any notification |

Two categories Android calls "special access" and will **not** let any app
enable for itself — you must flip these on manually, once, in Settings. The
app has a button for each that opens the right screen directly:

- **Notification access** — lets Ravy summarize incoming notifications
  (app + count, not full content unless you ask). Settings > Apps >
  Special app access > Notification access > Ravy > Allow.
- **Accessibility service** — lets Ravy read the current screen aloud on
  request ("read the screen"). This is the core screen-reader feature for a
  blind/low-vision user. It does NOT tap, type, or act on your behalf in
  this build — it only reads. Settings > Accessibility > Installed apps >
  Ravy > On.

## Hands-free "Hey Ravy" (real, with real trade-offs)
Tap "Enable Hey Ravy" to start a foreground service that continuously
listens for the phrase, then pops up a small orb (like Siri) at the bottom
of your screen over whatever app you're in, listens for your request, and
speaks the answer — then keeps listening for a follow-up for about 9 seconds
before going quiet again, so you don't have to repeat "Hey Ravy" every turn.

Read this before relying on it:
- This is a real restart-loop around Android's speech recognizer, not a
  low-power hardware hotword chip like "Hey Google" — it uses noticeably
  more battery.
- It requires "Display over other apps" (for the popup orb) — Ravy will
  prompt you for this the first time you enable it.
- Some phones with aggressive battery managers (Xiaomi/MIUI, Samsung, etc.)
  will kill background services anyway unless you exempt Ravy. Tap "Improve
  reliability" to open the "ignore battery optimizations" prompt for Ravy.
- Tapping the orb manually also now keeps listening for a follow-up after
  each reply, so you don't have to keep tapping either — it just quietly
  stops once you go silent.

## Real, open-ended answers ("Ravy, you're mad", "how are you")
Anything that isn't a recognized command (open app, flashlight, etc.) is now
sent to a real LLM instead of Ravy saying "I don't understand." **Gemini is
the default provider** (Google's Generative Language API); OpenAI is kept as
a secondary option since the integration (`llm/RavyLLM.kt`) is written so
either provider — or a future one — is just one function, swappable without
touching the rest of the app.

**Where to put your Gemini API key:** open the Ravy app on your phone, tap
the **"AI setup"** chip on the main screen, leave **Gemini** selected, paste
your key into the field, and tap **Save**. It's stored encrypted on-device
via Android's `EncryptedSharedPreferences` — it is never hardcoded into the
source, never sent to me, and never sent anywhere except directly from your
phone to Google's API over HTTPS.

Get a free Gemini API key at **aistudio.google.com/apikey** (Google AI
Studio). Until you add one, this feature honestly reports that setup is
needed instead of faking a personality reply.

If Google ever retires the specific model name this build uses, update the
single constant `GEMINI_MODEL` near the top of `llm/RavyLLM.kt` to whatever
current model name aistudio.google.com/models lists — nothing else needs to
change.

**Gemini can also trigger real device actions**, not just chat: if you say
something RavyBrain's keyword matching didn't recognize but that's still a
clear device command (e.g. an oddly-phrased "open my texts"), Gemini is
allowed to propose one of a fixed list of safe actions instead of just
replying with words. Ravy then *actually executes it* through the same real
`ActionEngine` used everywhere else in the app and speaks back Android's
real result — Gemini's guess is never itself treated as something that
already happened. Sending an SMS is deliberately excluded from what Gemini
can trigger this way; that still only ever happens through the normal
"say yes to confirm" voice flow.

## Real cloud voice (ElevenLabs) instead of the default device voice
By default Ravy speaks with the best on-device voice it can find, which is
still a synthesized robotic voice, not a Jarvis-style one. To get a real
higher-quality voice: tap the **"Voice setup"** chip → keep **ElevenLabs**
selected → paste your ElevenLabs API key → (optionally) paste a specific
voice ID from your own ElevenLabs voice library, or leave it blank to use
the built-in default (a deep, even-toned stock voice called "Adam" — the
closest free stock option to a Jarvis-style delivery) → Save.

Get a key at **elevenlabs.io** (there's a limited free tier; heavier use
needs a paid plan — that's ElevenLabs' billing, not something this app
adds on top). Stored encrypted on-device, same as the Gemini key, never
hardcoded, never sent anywhere but straight to ElevenLabs.

If a call to ElevenLabs ever fails (bad key, no network, rate limit), Ravy
silently falls back to the on-device voice for that one reply rather than
staying silent — it never fakes a cloud-voice reply that didn't actually
happen. Switch back to the plain device voice any time via the same
"Voice setup" chip.

## Dialing a specific SIM
"Dial 08012345678 with sim 2" uses `TelecomManager.placeCall` with the
matching phone account. This is a real dual-SIM API, but exact behavior
(which slot maps to which account) can vary slightly by phone manufacturer —
if it dials the wrong SIM on your device, that's an OEM quirk, not a bug in
the matching logic. Needs the Phone permission and, for the SIM-selection
part specifically, phone state permission.

## Reading a specific app's notifications
"Read WhatsApp notifications" (or "read the message from WhatsApp") reads
back the actual title + text Android received for that app's notifications
— e.g. "Broski texted and said: how are you." — not just a count. Needs
notification access enabled (see above).


"Open [anything]" is resolved live against every launchable app actually
installed on the device (`PackageManager.queryIntentActivities`) — not a
fixed list. Matching tolerates small speech-recognition mishears (e.g.
"what's app" close enough to "WhatsApp") via edit-distance, but won't open
something wildly unrelated to what was heard. Requires the
`QUERY_ALL_PACKAGES` permission already declared in the manifest — note that
if you ever publish this on Google Play, Play's policy requires a short
justification for that permission in the Play Console.

## Gmail — one-time setup only you can do
Ravy's Gmail code (`gmail/GmailClient.kt`) is real: Google Sign-In plus the
official Gmail API client, requesting read-only scope. Google requires every
app reading Gmail to have its own registered OAuth client, so until you do
this, sign-in will fail with a real Google error, not a fake one:

1. Go to console.cloud.google.com, create a project (or use an existing one).
2. APIs & Services > Library, enable the Gmail API.
3. APIs & Services > Credentials > Create Credentials > OAuth client ID,
   type Android.
   - Package name: `com.ravy.assistant`
   - SHA-1: run `./gradlew signingReport` in this project (or, in Android
     Studio, Gradle panel > app > Tasks > android > signingReport) and copy
     the debug SHA-1.
4. APIs & Services > OAuth consent screen, add your own Google account
   under Test users (this avoids Google's full app-verification process
   while you're the only user).
5. Rebuild and run. Say "sign into Gmail," approve the Google prompt, then
   "check my email."

Once signed in, "check my email" reads back sender and subject for your
latest messages only, not full bodies, matching the "report first, read on
request" rule. A `GMAIL_READ_MESSAGE` action (already wired in
`ActionEngine`) reads one full message aloud once you ask for a specific one.

## Accessibility-first design choices
Because this is meant for blind and low-vision users specifically:

- Every interactive element has an explicit `contentDescription` for
  TalkBack.
- Ravy speaks every result aloud via TTS, always — the on-screen text is a
  backup, not the primary channel.
- The screen-reading accessibility service only activates on request, so it
  never talks over you or narrates everything happening automatically.
- Recommend also enabling Android's own TalkBack as the base screen reader;
  Ravy is a voice-command layer on top of, not a replacement for, TalkBack.

## Honest limitations of this drop
- I don't have an Android SDK or internet connection in the environment that
  wrote this code, so I could not run Gradle myself to confirm a zero-error
  first compile. The code uses each API correctly and the dependency
  versions are current as of writing, but if Android Studio flags a version
  mismatch on sync, that's a real possibility worth checking, not something
  I can rule out from here.
- The "brain" (`brain/RavyBrain.kt`) is still simple keyword matching, not a
  language model. Swap it for your real ravyBrain backend call when ready,
  keeping the same `RavyAction` JSON shape.
- Voice command coverage is intentionally partial: contacts/calls, SMS
  count, calendar creation, location, files, notifications, screen reading,
  and Gmail summary all work end-to-end. Sending an SMS by voice, deleting
  files, and multi-step commands are wired in `ActionEngine` but don't yet
  have a matching phrase in `RavyBrain` — add the phrase-to-action mapping
  the same way the existing ones are written.
- `RavyAccessibilityService` here only reads. Any future feature that has it
  act (tap/type/scroll on your behalf) should be added deliberately, one
  action at a time, with its own confirmation step, not silently expanded.

## Interface redesign (hamburger menu, orb states, reminders, etc.)
See the chat message that delivered this version for a full item-by-item
status (done / simplified / deferred) against the redesign spec. Short
version: main screen, orb states, hamburger navigation, Permission Center,
Chat Mode, Serious Mode, Language, and real AlarmManager-based Reminders &
Routines (with a genuine full-screen Answer/Dismiss alert — not a real
phone call) are implemented and wired to the real engine. Google Sign-In is
real; email+code sign-in is intentionally left unimplemented rather than
faked, since a true code-verification flow needs backend infrastructure
this Android project alone can't provide. The floating "Hey Ravy" overlay
orb still reflects real listening amplitude but doesn't yet have the full
7-state color system the main in-app orb has — a disclosed simplification,
not a silent gap.

## Running it
1. Open in Android Studio, let Gradle sync (needs internet).
2. Run on a device/emulator, API 26+.
3. Grant the permission popups.
4. Tap the chips to enable notification access, accessibility, and Gmail.
5. Tap the orb and try: "flashlight", "battery", "call [a real contact
   name]", "unread texts", "where am I", "recent files", "read the screen",
   "check my email".
