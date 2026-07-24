# Spoken announcement for WhatsApp calls

Date: 2026-07-24
Status: approved

## Goal

Announce the caller's name when a WhatsApp (or WhatsApp Business) call rings,
using the app's existing speech pipeline. Opt-in, and repeating at the same
user-adjustable 4-10 s interval as the voice-only mode (user request).

## Decisions

- A `NotificationListenerService` inside ARK-phone (no separate app). It
  reacts ONLY to notifications from `com.whatsapp` / `com.whatsapp.w4b` with
  the call category, discards everything else immediately, stores nothing and
  sends nothing anywhere.
- Incoming-call detection: CallStyle call type 1 (incoming) when present;
  older formats fall back to requiring a full-screen intent, which ongoing
  call notifications do not set. The caller name comes from the CallStyle
  person (API 31+) or the notification title.
- Announcement text: "%s is calling on WhatsApp" / unknown variant, en + fi.
  Speech goes through the existing SpeechEngine and silent/DND gate.
- Repeats at `announceIntervalSeconds` (the existing slider) until the
  notification is removed (answered/declined/missed), with a 2-minute safety
  cap in case a removal event is lost.
- New setting `announceWhatsApp` (default off) + its own switch row in a
  WhatsApp section. When the toggle is on but notification access has not
  been granted, a button opens the system notification-access page
  (`ACTION_NOTIFICATION_LISTENER_SETTINGS`); access state refreshes on
  resume. Access check behind a `NotificationAccessChecker` fun interface
  (PermissionChecker precedent).
- The interval slider is visible whenever voice-only mode OR the WhatsApp
  announcement is enabled.
- WhatsApp's own ringtone cannot be silenced programmatically; the switch
  description points the user to WhatsApp's settings for that.

## Testing

- Detection function with real Notification objects (Robolectric): correct
  package+category+title → name; wrong package or ongoing call type → null;
  full-screen-intent fallback path.
- Announcer: repeats at the configured interval, stops on removal, respects
  the toggle and the gate, uses the WhatsApp strings.
- Settings: WhatsApp switch writes the setting; slider visible with mode OFF
  when the WhatsApp toggle is on; access button appears when access is
  missing and reports its callback.
- Lint stays at 0 errors.

## Out of scope

Calling back via WhatsApp, announcing other apps' calls (Telegram etc. —
possible later with the same listener), SMS.
