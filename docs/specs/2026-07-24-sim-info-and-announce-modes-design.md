# SIM card info page and caller announcement modes

Date: 2026-07-24
Status: approved

## Goal

1. A read-only SIM card information page under settings, with a shortcut to
   the system's mobile network settings (third-party apps cannot change SIM
   settings themselves).
2. Extend the spoken caller announcement from an on/off switch to three modes,
   because speech over the ringtone is not always pleasant:
   - **Off** (default)
   - **With ringtone** — current behavior: speaks twice (at 0 s and 8 s)
     while the ringtone plays
   - **Voice only** — silences the ringtone (vibration stays) and repeats the
     caller's name at a user-adjustable interval, slider 4–10 s (default 6 s)

## Decisions

- The incoming-call ringtone comes from the app's own notification channel, so
  "voice only" posts the incoming notification on a new silent channel
  (`incoming_calls_silent`: no sound, vibration on, otherwise identical).
  `ArkInCallService` picks the channel from the current announcement mode.
- Settings model becomes `Settings(announceMode: AnnounceMode,
  announceIntervalSeconds: Int)`. The v1.2 boolean key `announce_caller`
  migrates on read: `true` maps to `WITH_RINGTONE` when the new key is absent.
  The interval is clamped to 4–10.
- A `SettingsCache` (settings flow started eagerly in the application scope)
  gives synchronous call-path code the latest settings value; before the first
  DataStore emission it returns defaults, which only means a normally ringing
  first call in a freshly started process.
- The announcement gate (silent/vibrate ringer or DND suppresses speech) and
  the ring-stream audio attributes stay as in v1.2, for every mode.
- SIM data comes from `SubscriptionManager.activeSubscriptionInfoList` behind
  a new `SimRepository`; per SIM: display name, carrier, phone number when
  available, slot (physical index or eSIM), country ISO. No IMEI (not
  readable by regular apps). Missing READ_PHONE_STATE yields an empty list.
- Phone numbers: on API 33+ `getPhoneNumber` needs READ_PHONE_NUMBERS; the
  permission is added to the manifest and requested contextually from the SIM
  page ("Show phone numbers" button) — the onboarding core-permission set is
  NOT changed, so existing setups are untouched. Without the grant the number
  row is simply omitted.
- The SIM page is a sub-screen inside `SettingsActivity` (a saveable boolean
  plus BackHandler; no new activity, no NavHost). Opened from a new
  "SIM cards" row on the settings screen. A bottom button opens
  `Settings.ACTION_NETWORK_OPERATOR_SETTINGS`, guarded by runCatching.
- Announcement mode UI: three radio rows; the interval slider (4–10 s, 1 s
  steps, value shown in the label) is visible only in voice-only mode.

## Error handling

As before, every new path fails silent and safe: missing permissions or
lookup/system-service failures yield an empty SIM list or an omitted row;
unknown persisted mode strings fall back to Off; the system settings intent
failure is swallowed.

## Testing

- Settings repository: new defaults, mode and interval round-trip, interval
  clamping, legacy boolean migration.
- CallerAnnouncer: with-ringtone speaks exactly twice; voice-only repeats at
  the configured interval and stops on answer; off/gated speaks nothing.
- CallNotifications: incoming notification lands on the silent channel when
  asked.
- Settings screen: radio selection writes the mode; the slider appears only
  in voice-only and writes the interval; back works.
- SimRepository: empty without permission; maps subscription fields.
- SIM screen: renders carrier/slot rows from a fake repository; empty state.
- Lint stays at 0 errors.

## Out of scope

Changing SIM settings in-app (impossible for third parties), signal strength,
IMEI, SMS. Tracked in `docs/BACKLOG.md`.
