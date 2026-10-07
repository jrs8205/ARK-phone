<p align="center">
  <img src="docs/icon.png" width="128" alt="ARK-phone icon">
</p>

<h1 align="center">ARK-phone</h1>

<p align="center">
  <a href="https://github.com/jrs8205/ARK-phone/releases/latest"><img src="https://img.shields.io/github/v/release/jrs8205/ARK-phone" alt="Latest release"></a>
  <a href="https://github.com/jrs8205/ARK-phone/releases"><img src="https://img.shields.io/github/downloads/jrs8205/ARK-phone/total" alt="Downloads"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/jrs8205/ARK-phone" alt="License"></a>
</p>

<p align="center">
An Android dialer and messaging app that replaces your phone's default Phone and
Messages apps, with free encrypted internet calls between ARK-phone users.
Built with Kotlin, Jetpack Compose and Material 3.
</p>

**Your phone stays yours.** Calls and messages go through your carrier and
nothing leaves the device. The one exception is ARK internet calls, which you
turn on yourself, and even then the app never sends anything but what a call
needs.

## Screenshots

| Home | Keypad | Messages | Conversation |
|---|---|---|---|
| ![Recent calls](docs/screenshots/home.png) | ![Keypad with T9 search](docs/screenshots/keypad.png) | ![Conversations](docs/screenshots/messages.png) | ![Conversation](docs/screenshots/conversation.png) |

## Features

### Calls

- Default phone app with a full in-call screen: mute, speaker, hold, keypad;
  the screen turns off against your cheek and the app closes when the call ends
- Keypad with T9 search, speed dial and clipboard paste
- Recent calls with search, filters (missed / outgoing / blocked / WhatsApp)
  and grouping of repeated calls
- Contacts with favorites, most-called, search and a full contact card;
  edits open the system editor and sync to your Google account
- Spoken caller announcement and caller photo, also on the lock screen
- Call blocking: single numbers, hidden numbers, unknown callers, number
  prefixes, time schedules and per-SIM rules, with an allow list and
  favorite / repeat-caller pass-through; blocked calls can be rejected or
  sent silently to voicemail
- WhatsApp calls in the call history with a direct WhatsApp callback
- Dual SIM: default SIM for calls, SIM shown per call, SIM info page

### ARK internet calls

- Create an ARK code in Settings and share it with the people you call
- Link a friend's code on their contact card; calls to linked contacts go
  over the internet whenever their phone is reachable, and over the carrier
  otherwise, so a call always goes through
- Audio is end-to-end encrypted (WebRTC with DTLS-SRTP) and travels phone to
  phone; a relay steps in only when no direct path exists, and it cannot
  decrypt the audio
- Works on Wi-Fi and mobile data, rings on a locked and sleeping phone, costs
  nothing

### Messages

- Default SMS app: SMS and picture messages (MMS), including groups
- Conversation list with search, unread indicators and multi-recipient
  new-message flow
- Notifications with quick reply and mark-as-read; blocked senders stay silent
- Delivery status, tap-to-retry for failed picture downloads, tappable links
  and numbers
- Multi-select delete and share; share text and pictures from other apps
  straight into ARK-phone

## Privacy

- No analytics, no crash reporting, no ads, no third-party SDKs that phone home
- Calls, messages and contacts stay in Android's own system stores
- Until you create an ARK code, the app makes no network connection at all
- With ARK calls on, a small signaling server (a Cloudflare Worker run by the
  author) stores your nickname, ARK code, public key and push token, and sees
  your IP address while the app is connected; it forwards call-setup messages
  and never carries audio
- A locked phone is woken for an incoming ARK call through Firebase Cloud
  Messaging; that push carries only the caller's ARK code
- The server address is in the source, so you can run your own

## Install

1. Download `ARK-phone-<version>.apk` from the
   [latest release](https://github.com/jrs8205/ARK-phone/releases/latest).
2. Open the file on your phone and allow installing from unknown sources
   when asked.
3. Set ARK-phone as the default phone app, and optionally as the default
   SMS app, when the app offers it.

Requires Android 8.0 (API 26) or newer. Tested on Pixel and Samsung Galaxy
phones.

## Building

```
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. ARK calls work
out of the box; a Firebase `app/google-services.json` is optional and only
adds the push wake-up for locked phones. To point the app at your own
signaling server, set `arkphone.voip.workerUrl` in `local.properties`.

## Testing

```
./gradlew :app:testDebugUnitTest :app:lintDebug
```

The suite has 900+ unit tests and lint runs with `warningsAsErrors`.

## Languages

English, Finnish, Swedish, German, French, Spanish, Estonian, Russian,
Portuguese, Italian and Polish. The app follows the device language and
falls back to English; Android 13 and newer can pick a language per app.

## More apps

The author's other public apps are listed at [jrs8205.com](https://jrs8205.com).

## License

GPL-3.0 — see [LICENSE](LICENSE).
