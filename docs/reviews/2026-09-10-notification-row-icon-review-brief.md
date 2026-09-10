# External review brief — small icon on Android 17 notification rows (2026-09-10)

You are reviewing **one fix** in the ARK-phone repository (branch
`feature/voip-spike`, commit `5d0ba74`): every notification the app posts
now carries the platform extra that asks an Android 17 notification row to
draw the notification's **small icon** instead of the app's launcher icon.
The defect was reproduced on a device with a screenshot and a
`dumpsys notification` record, so the root cause is not in question —
**your job is to verify the fix is correct and complete, and to find any
defect the change introduces or leaves open.**

## Ground rules

- **Report only. No patches.** Every finding will be verified against the
  code and fixed by the maintainer separately.
- Finding format: severity P1/P2/P3 · `file:line` · one-sentence claim ·
  concrete failure scenario · confidence. Sorted by severity. No style notes.
- The diagnosis below is device-verified fact; do not re-litigate it.
  Review the **new code path** and every notification surface it must reach.

## The field defect (device-verified, context)

- Field report: a missed or blocked call's notification "shows a text
  message icon, not a phone". The status-bar small icons were already split
  on 2026-08-20 (`ic_notification_call` = handset for calls,
  `ic_notification_message` = dotted bubble for messages, commit `580e9ae`),
  and the posted record confirms the small icon is the handset:
  `icon=Icon(typ=RESOURCE pkg=org.jarsi.arkphone id=0x7f060025)` →
  `drawable/ic_notification_call`.
- Both test phones run **Android 17** (`ro.build.version.sdk=37`, build
  `CP2A.260805.005`). Android 17 draws the **launcher icon** on the
  notification row for every app and keeps the small icon for the status
  bar alone. The shade screenshot showed the Reddit and system rows with
  their launcher icons and the ARK missed-call row with ARK's launcher
  icon — a white speech bubble with a handset inside — which at row size
  reads as a text-message bubble.
- The platform opt-out is `android.app.Notification.EXTRA_PREFER_SMALL_ICON`
  (API 37, value `"android.app.preferSmallIcon"`, boolean `true`), present
  in the API 37 `android.jar` and in `androidx.core` 1.19.0's
  `NotificationCompat`. Android 16 and older ignore the extra.
- After the fix a second test call produced a row with the handset in the
  tinted circle, the contact photo as large icon on the right, and the
  record shows `android.app.preferSmallIcon=Boolean (true)`.

## The fix under review

New file `app/src/main/java/org/jarsi/arkphone/util/NotificationIcons.kt`:

- `const val EXTRA_PREFER_SMALL_ICON = "android.app.preferSmallIcon"` — a
  literal because the module compiles against SDK 36 and depends on
  `androidx.core` 1.17.0 (1.19.0 needs `compileSdk 37`); the KDoc names the
  platform constant it mirrors.
- `fun NotificationCompat.Builder.preferSmallIcon()` =
  `addExtras(Bundle().apply { putBoolean(EXTRA_PREFER_SMALL_ICON, true) })`.

Call sites — every `NotificationCompat.Builder` in the app, each directly
after its `setSmallIcon(...)`:

- `telecom/MissedCallNotifier.kt` — missed calls (contact photo as large icon).
- `telecom/BlockedCallNotifier.kt` — silently blocked calls.
- `telecom/CallNotifications.kt` — `buildIncomingCall` (five channels,
  optional full-screen intent, `FLAG_INSISTENT`) and `buildOngoingCall`
  (also re-posted unchanged by the debug-only `VoipForegroundService` as its
  foreground notice under the same id).
- `messaging/MessageNotifier.kt` — SMS/MMS with `MessagingStyle`, reply and
  mark-read actions.

Tests added (one per notifier, in the existing Robolectric test classes):
each asserts the posted or built notification's `extras` carry
`"android.app.preferSmallIcon" == true` — the tests pin the literal key on
purpose so a typo in the constant cannot pass. Full suite + lint green
(919 unit tests).

## What to verify (at minimum)

1. **Extras survival.** Does anything between `preferSmallIcon()` and
   `NotificationManagerCompat.notify` replace the builder's extras rather
   than merge into them — `MessagingStyle`, `addAction` with
   `RemoteInput`, `setFullScreenIntent`, the `.apply { flags = ... }` after
   `build()`, or the foreground-service re-post path?
2. **Coverage.** Any notification the app posts that does not go through
   these four builders (WhatsApp monitor, MMS download, the debug/beta
   VoIP sources under `app/src/debug`, `NotificationCompat` calls in
   `ui/`)? Any place that rebuilds a notification from an existing one and
   would lose the extra?
3. **Platform semantics.** Conditions under which Android 17 ignores
   `EXTRA_PREFER_SMALL_ICON` (notification style, category, ongoing or
   foreground-service rows, heads-up, lock screen, grouped/summary rows,
   Wear or Auto projections) and whether any ARK notification falls under
   them. Anything on Android 16 or earlier that reads or trips over an
   unknown `android.app.*` extra?
4. **Icon fitness.** With the small icon drawn in a tinted circle on the
   row, are `ic_notification_call` and `ic_notification_message` (24 dp,
   white, single path) legible and correctly padded? Should the builders
   set `setColor` so the circle tint matches the app instead of the system
   default?
5. **Key correctness.** Confirm the literal equals
   `Notification.EXTRA_PREFER_SMALL_ICON` in the API 37 sources, and whether
   the cleaner route — `compileSdk 37` + `androidx.core` 1.19 and the real
   constant — carries any risk for this module (`targetSdk` stays 36).
6. **Test strength.** Do the four tests actually exercise the code path
   that reaches the manager (the message test goes through
   `MessagingStyle`; the call tests build without posting)? Any missing
   case worth a test — the silenced ring channel, the foreground re-post?

## Environment

- Android app module only; `minSdk 26`, `targetSdk 36`, `compileSdk 36`,
  Kotlin, Hilt, Robolectric unit tests (`@Config(sdk = [35])` in the call
  tests). Full suite + lint green at `5d0ba74`.
- Notification files: `util/NotificationIcons.kt` (the fix),
  `telecom/MissedCallNotifier.kt`, `telecom/BlockedCallNotifier.kt`,
  `telecom/CallNotifications.kt`, `messaging/MessageNotifier.kt`,
  `voip/VoipForegroundService.kt` (debug source set, re-posts the ongoing
  notification), drawables `ic_notification_call.xml` and
  `ic_notification_message.xml`, launcher icon
  `ic_launcher_foreground.xml` (the bubble-with-handset).
