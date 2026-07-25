# Call blocking rules — design

Date: 2026-07-25. Rules chosen by the user: hidden numbers, not-in-contacts,
prefix blocking, and the repeat-caller exception.

## Approach

ARK-phone is the default dialer, so it may provide a
`CallScreeningService`: rule-blocked calls are rejected silently before
they ring, with no notification, and the system logs them as blocked —
they show up in our history with the existing BLOCKED type. Number-specific
blocking stays on BlockedNumberContract as before; this round adds rules.

## Rules (all off by default, except the exception)

1. **Block hidden numbers** — no caller id.
2. **Block callers not in contacts.**
3. **Blocked prefixes** — a user-maintained list ("+358700", "0700", …);
   a call whose number starts with any listed prefix is blocked.
4. **Repeat-caller exception (default ON)** — a number calling again
   within 15 minutes is let through even when a rule would block it.

## Components

- `Settings`: `blockHiddenNumbers`, `blockUnknownCallers`,
  `blockedPrefixes: Set<String>`, `allowRepeatCallers` + DataStore keys,
  repository setters; values reach the screening path through the existing
  `SettingsCache.await()` (the service cold-start lesson from v1.4.2).
- Pure `shouldBlockCall(number, isInContacts, isRepeatCaller, settings)`
  in the telecom layer; `sameCaller` moves to a shared util so the repeat
  check and the in-call last-called line use one matcher.
- `ArkCallScreeningService : CallScreeningService` (manifest with
  BIND_SCREENING_SERVICE): incoming calls only; looks up contacts and the
  recent log, responds disallow+reject+skipNotification (call log kept).
- Settings UI: a "Call blocking" sub-page (like the SIM page) with three
  switches, the repeat-caller switch, and a prefix editor (add field +
  removable list rows).

## Testing

`shouldBlockCall` covers every rule and the exception; DataStore setters
single-write tests; blocking page Compose tests (switches, prefix
add/remove); the screening service itself is thin glue over the pure rule
and is field-verified (hidden + unknown + prefix test calls).
