# ARK Phone — v1 Design

Date: 2026-07-22
Status: Approved

## Overview

ARK Phone is a modern Android dialer application intended to replace the device's
default phone app. It is built from scratch with Kotlin, Jetpack Compose, and
Material 3. The UI uses only system/wallpaper-derived colors (Material You
dynamic color) on supported devices, follows the system light/dark setting, and
falls back to a hand-crafted dark theme on older devices. English is the default
language; Finnish is provided as a second language and is selected automatically
on devices whose system language is Finnish.

## Goals (v1)

- Fulfill all requirements of the default dialer role (`RoleManager.ROLE_DIALER`)
  so the user can set ARK Phone as the device's default phone app.
- Dial pad with outgoing call support.
- Incoming call UI and ongoing call UI (full `InCallService` implementation).
- Call log (recents) screen backed by the system call log.
- Contacts screen with favorites section and search.
- Dynamic Material 3 theming (Android 12+) with dark-theme fallback (Android 8–11).
- English default, Finnish auto-selected from device locale.

## Non-goals (v1)

- Number blocking, multi-SIM UI, speed dial, visual voicemail, call recording,
  SMS. These are candidates for later versions.
- Emergency call handling: the platform always routes emergency calls through
  the preloaded dialer; no custom handling is required or attempted.
- Contact editing (the app links to the system contact editor).

## Platform requirements

- `compileSdk` 36, `targetSdk` 36, `minSdk` 26 (Android 8.0).
- Default-dialer role request:
  - Android 10+ (API 29+): `RoleManager.createRequestRoleIntent(ROLE_DIALER)`.
  - Android 8–9 (API 26–28): `TelecomManager.ACTION_CHANGE_DEFAULT_DIALER` intent.
- The app must handle `Intent.ACTION_DIAL` (with and without `tel:` data) and
  fully implement `InCallService`; the service binding must never return null.

## Architecture

Single `app` module. MVVM with unidirectional data flow:

```
Compose UI  <--StateFlow--  ViewModel  <--Flow--  Repository  <--  system providers / Telecom
```

- Kotlin 2.x, Jetpack Compose with Material 3, version catalog (`libs.versions.toml`).
- Hilt for dependency injection.
- Coroutines + Flow throughout; no custom database in v1 (system content
  providers are the source of truth).

### Package layout

```
org.jarsi.arkphone
├── ArkPhoneApp.kt            // Application class, Hilt entry point
├── MainActivity.kt           // Single activity hosting main navigation
├── telecom/
│   ├── ArkInCallService.kt   // InCallService implementation
│   ├── CallManager.kt        // Singleton bridging Call objects to StateFlow
│   └── CallNotifications.kt  // Incoming-call notification + ringing channel
├── data/
│   ├── CallLogRepository.kt  // CallLog.Calls provider, Flow + ContentObserver
│   └── ContactsRepository.kt // ContactsContract provider, Flow + ContentObserver
├── ui/
│   ├── navigation/           // NavHost, destinations, bottom bar
│   ├── dialpad/              // Dial pad screen + suggestions
│   ├── recents/              // Call log screen
│   ├── contacts/             // Contacts + favorites + search
│   ├── incall/               // InCallActivity + call screen
│   ├── onboarding/           // Role + permission request flow
│   └── theme/                // Color schemes, typography, dynamic color logic
└── util/
```

## Screens

1. **Main screen** (`MainActivity`): bottom navigation with **Recents** and
   **Contacts** tabs; a FAB opens the **Dial pad**.
   - Recents: grouped call log entries (type icons for incoming/outgoing/missed),
     tap to call, long-press or detail affordance for number actions.
   - Contacts: favorites row/section on top, alphabetical list, search field.
   - Dial pad: T9-style filtering suggestions from contacts; call button uses
     `TelecomManager.placeCall()`.
2. **In-call screen** (`InCallActivity`): caller name/photo/number, call state,
   duration; actions: answer/reject (incoming), mute, speaker, DTMF keypad,
   hold, end call. Uses `setShowWhenLocked(true)` and `setTurnScreenOn(true)`.
3. **Onboarding**: explains the app, requests the default-dialer role and
   runtime permissions; shown until granted, re-openable from a banner if the
   user declines.

## Telecom integration

- `ArkInCallService` declared in the manifest with
  `android.permission.BIND_INCALL_SERVICE` and metadata
  `IN_CALL_SERVICE_UI = true`, `IN_CALL_SERVICE_RINGING = true`.
- The app plays the ringtone itself via a `NotificationChannel` configured with
  the ringtone sound and full-screen intent for incoming calls.
- `CallManager` holds the current `Call` list as `StateFlow`, translating
  `Call.Callback` events into UI state consumed by the in-call ViewModel.
- Outgoing calls always go through `TelecomManager.placeCall(uri, extras)`.

### Permissions

`READ_CALL_LOG`, `READ_CONTACTS`, `CALL_PHONE`, `READ_PHONE_STATE`,
`POST_NOTIFICATIONS` (API 33+). Manifest-only: `BIND_INCALL_SERVICE` (service
permission). All requested with graceful denial states.

## Theming

- Android 12+ (API 31+): `dynamicLightColorScheme(context)` /
  `dynamicDarkColorScheme(context)` selected by the system dark-mode setting.
  No brand palette; colors come exclusively from the system/wallpaper.
- Android 8–11 (API 26–30): always the custom dark Material 3 color scheme,
  regardless of system setting (per product decision: old devices are dark).
- Typography and shapes from Material 3 defaults, tuned only where needed.

## Localization

- Default resources (`values/strings.xml`): English. All strings externalized;
  no hard-coded UI text.
- `values-fi/strings.xml`: Finnish translations. Selected automatically by the
  Android resource system when the device (or per-app) locale is Finnish.
- `android:localeConfig` declared so Android 13+ shows the per-app language
  option in system settings. Supported locales: `en` (default), `fi`.

## Data layer

- `CallLogRepository`: queries `CallLog.Calls`, exposes a `Flow` of call log
  entries, refreshed via `ContentObserver`. Requires the app to hold
  `READ_CALL_LOG` (granted implicitly with the dialer role on modern Android,
  still requested explicitly).
- `ContactsRepository`: queries `ContactsContract` for contacts, favorites
  (starred), and photo URIs; exposes `Flow`, refreshed via `ContentObserver`.
- Repositories are the error boundary: provider failures surface as empty
  states with error messages, never crashes.

## Error handling

- Role or permission denied: the app remains usable as a viewer where possible,
  shows a clear explanation and a retry button; never crashes.
- `InCallService` edge cases (call dropped during UI startup, multiple calls):
  UI always renders from `CallManager` state; unknown states show a safe
  generic in-call view with the end-call action available.

## Testing

- Unit tests: ViewModels and repositories (JUnit, kotlinx-coroutines-test,
  Turbine for Flow assertions).
- UI tests: Compose testing for the dial pad (input, filtering) and main
  navigation.
- Manual/instrumented telephony testing on the emulator: incoming calls can be
  simulated with `adb emu gsm call <number>`; role flow verified on a physical
  device.

## Repository conventions

- All code, comments, commit messages, documentation, and default resources are
  written in English. Finnish appears only in `values-fi` translation files.
- The `reference/` directory (local copies of third-party sources kept for
  reading) and `.remember/` are git-ignored and never committed.
- License: GPL-3.0 (already present in the repository).
