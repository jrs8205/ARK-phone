# Per-number call detail view, blocking, WhatsApp labeling

Date: 2026-07-24
Status: approved

## Goal

Pixel-style call history: tapping a log row opens a full per-number detail
view. Calling from the list stays one tap away via a trailing call icon on
each row. WhatsApp calls (already present in the system call log via
WhatsApp's self-managed ConnectionService) are labeled. Blocked calls appear
in the log with their own type.

## Decisions

- Row tap behavior changes from call-immediately to open-details; a trailing
  phone IconButton on each row places the call (user approved).
- `CallDetailActivity` (own activity, `SettingsActivity` precedent) with the
  number as an intent extra; `CallDetailScreen` + `CallDetailViewModel`.
- Detail content: header (large avatar, name via PhoneLookup, number, Blocked
  badge), action row (Call, Message via ACTION_SENDTO smsto:), statistics card
  (total calls, incoming/outgoing/missed counts, total talk time, latest
  call), full history for the number with type icon, date-time, duration and
  a WhatsApp label. Number matching uses `PhoneNumberUtils.compare` so
  +358 40 and 040 forms meet.
- Top-bar 3-dot menu: Copy number (clipboard), Edit number before call
  (ACTION_DIAL into MainActivity — keypad prefill exists), Block number /
  Unblock number, Delete history (AlertDialog confirmation first).
- Blocking via `BlockedNumbersRepository` on `BlockedNumberContract`; the
  default dialer may read and write it without extra permissions. All calls
  runCatching-guarded; `canCurrentUserBlockNumbers` checked.
- Delete history needs the new `WRITE_CALL_LOG` permission, requested
  contextually at first delete (activity result launcher). Deletion resolves
  matching row ids client-side (PhoneNumberUtils.compare) and deletes by id.
- Call log source: read `PHONE_ACCOUNT_COMPONENT_NAME`; component containing
  `com.whatsapp` maps to a `CallSource.WHATSAPP`, telephony to `PHONE`, the
  rest to `OTHER`. Only WhatsApp gets a visible label (also in the main log).
- `CallLog.Calls.BLOCKED_TYPE` maps to new `CallType.BLOCKED` with its own
  label; rejected/blocked rows use the missed icon.
- Statistics are computed by a pure function over the filtered entries for
  testability.

## Error handling

Missing WRITE_CALL_LOG → delete simply asks for it; denied → nothing deleted,
menu stays. Blocking unavailable (not default dialer / managed profile) →
menu item hidden. Lookup failures → number-only header. All content-provider
work off the main thread, runCatching-guarded.

## Testing

- BLOCKED type mapping; phone-account component → source mapping.
- Statistics function: counts, total duration, latest call.
- CallDetailViewModel with fakes: filtering by number equivalence, blocked
  state, stats exposure.
- Compose: detail renders header/stats/rows; menu shows the four items;
  delete asks for confirmation; recents row tap opens details and the
  trailing icon calls.
- Lint stays at 0 errors.

## Out of scope

Search/filters/grouping in the main log, callback reminders, spam data,
calling back via WhatsApp (future rounds; tracked in docs/BACKLOG.md).
