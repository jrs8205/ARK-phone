# Incoming call view round — design

Date: 2026-07-25. Chosen by the user; direct SMS sending picked in the
design discussion.

## Scope

1. **Silence without declining** — a button on the incoming call screen
   that stops our ringtone (the incoming notification is re-posted on the
   silent channel) and the spoken announcement, leaving the call ringing
   and answerable.
2. **Decline with an SMS** — next to decline, a message option opens a
   small chooser of canned replies; picking one rejects the call and sends
   the SMS directly (SmsManager, new SEND_SMS permission requested in
   context). Canned texts (fi/en): "can't talk now, I'll call back",
   "text me", "I'll call you soon".
3. **"Last called" info** — the incoming screen shows when this number
   last appeared in the call log ("Last call: yesterday").
4. **Caller color coding** — numbers not in contacts get a
   warning-colored "Unknown number" tag; saved contacts render normally.

## Components

- `RejectMessageSender` (fun interface + SmsManager impl) for testability;
  SEND_SMS added to the manifest and requested when the feature is first
  used (fallback: reject without message when denied — the message chooser
  hides when the permission is missing and cannot be asked from the lock
  screen flow, so the permission is requested via onboarding-style prompt
  in call screen context).
- `ArkInCallService`/`CallNotifications`: re-post the incoming notification
  silently for the silence action; expose silence to the in-call UI.
- `InCallViewModel`: last-call lookup from `CallLogRepository`, unknown
  caller flag, silence + reject-with-message actions.
- `CallScreen`: silence button, message chooser (modal list of canned
  replies), last-called line, unknown-number tag.

## Testing

ViewModel tests (last-call lookup, unknown flag, actions call their
collaborators); canned-reply chooser and unknown tag via Compose tests;
silence path field-verified (channel visible in dumpsys).
