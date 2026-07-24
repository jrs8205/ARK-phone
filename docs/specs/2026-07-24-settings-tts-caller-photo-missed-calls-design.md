# Settings, spoken caller announcement, caller photo, missed-call notification

Date: 2026-07-24
Status: approved

## Goal

Four related additions in one round:

1. An in-app settings screen (the app has none yet), opened from a gear icon.
2. An opt-in spoken caller announcement (TTS) when a call is ringing.
3. The caller's contact photo on the in-call screen, including on the lock screen.
4. The app's own missed-call notification replacing the system Telecom one,
   with a launcher badge and a call-back action.

## Decisions

- Settings live in a dedicated `SettingsActivity` (precedent: `InCallActivity`),
  not a fourth tab and not a NavHost refactor.
- Settings are stored in Preferences DataStore behind a `SettingsRepository`
  interface with a Hilt binding.
- The gear is a Material vector icon in an `IconButton` (48 dp touch target,
  meets the >= 44 px requirement) at the top-right of the Home tab, with a
  content description and click haptics.
- The announcement is spoken twice: immediately when ringing starts and again
  at 8 s if still ringing. It stops the moment the call is answered, rejected
  or disconnected.
- The announcement is skipped when: the setting is off (default), the ringer
  is in silent/vibrate mode, or Do Not Disturb is active
  (interruption filter != ALL).
- Announcement text is localized: "%s is calling" / "Unknown caller is
  calling" (en), "%s soittaa" / "Tuntematon soittaja soittaa" (fi). TTS audio
  uses ring-stream audio attributes so its volume follows the ring volume.
- Caller resolution (name + photo URI) uses a single new
  `ContactsRepository.lookupContact(number)` backed by
  `ContactsContract.PhoneLookup`, shared by the in-call photo, the announcer
  and the missed-call notification.
- The in-call caller photo is a large round `ContactAvatar` (~112 dp) above
  the caller name, on both the incoming and ongoing layouts; letter avatar
  fallback. The lock screen shows the same `CallScreen`, so it needs no extra
  work.
- A `MissedCallReceiver` registered for
  `TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION` takes over the missed
  call notification (the system suppresses its own once the default dialer
  declares this receiver). Count 0 cancels the notification; count > 0 posts
  one on a new "Missed calls" channel with `setShowBadge(true)`, the caller's
  name and photo, a call-back action, `setNumber(count)` and a content intent
  opening the app. Opening the app clears the notification and calls
  `TelecomManager.cancelMissedCallsNotification()`.

## Components

- `data/SettingsRepository.kt` — interface: `val settings: Flow<Settings>`,
  `suspend fun setAnnounceCaller(enabled: Boolean)`.
  `data/DataStoreSettingsRepository.kt` — Preferences DataStore
  implementation; read errors fall back to defaults.
  `data/model/Settings.kt` — `data class Settings(val announceCaller: Boolean = false)`.
- `ui/settings/SettingsActivity.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`
  — themed like `MainActivity`; a switch row for the announcement with title
  and description.
- `ui/home/HomeScreen.kt` — gear `IconButton` opening `SettingsActivity`.
- `telecom/CallerAnnouncer.kt` — driven by `ArkInCallService` ringing state;
  TTS behind a small interface so tests can fake it; uses the shared `Clock`.
- `data/ContactsRepository.kt` — new `lookupContact`;
  `SystemContactsRepository` implements it with `PhoneLookup`.
- `ui/incall/InCallViewModel.kt` — enriches UI state with the caller photo
  URI; `CallScreen.kt` renders the avatar.
- `telecom/MissedCallReceiver.kt` + a channel and builder in
  `CallNotifications.kt`; clearing hook on app open (`MainActivity` resume).
- Strings in `values/strings.xml` and `values-fi/strings.xml`.

## Error handling

Every new path fails silent and safe: missing READ_CONTACTS or a failed
lookup falls back to number-only / letter avatar / "unknown caller"; TTS
engine or language unavailability skips the announcement; DataStore IO errors
yield defaults. Nothing may disturb call handling.

## Testing

- Settings repository: default value and round-trip persistence.
- Settings screen: switch reflects and writes state (Robolectric Compose).
- CallerAnnouncer with a fake TTS and test clock: speaks at 0 s and 8 s while
  ringing, stops on answer, respects the setting, silent mode and DND.
- InCallViewModel photo enrichment with a fake repository.
- MissedCallReceiver handler logic: count 0 cancels, count > 0 posts with the
  resolved name.
- CallScreen shows the avatar when a photo URI is present.
- Lint stays at 0 errors; ThemeContrastTest keeps guarding contrast pairs.

## Out of scope

SIM settings/info page (next round), SMS support (own project), spam
indicators, decline-with-message. Tracked in `docs/BACKLOG.md`.
