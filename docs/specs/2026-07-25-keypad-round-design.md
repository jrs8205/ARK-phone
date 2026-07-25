# Keypad round — design

Date: 2026-07-25. Approved in the design discussion (speed dial model chosen
by the user).

## Scope

Four keypad features; SIM selection items stay in the backlog for a later
telecom-focused round.

1. **Paste from the clipboard** — long-press on the number display appends
   the clipboard's phone characters (digits, `+`, `*`, `#`); everything else
   is stripped.
2. **Live number formatting** — the typed number is shown formatted with the
   platform formatter for the device country; the raw digits stay the dialed
   value. Numbers containing `*`/`#` are shown as typed.
3. **Speed dial on keys 2–9, defined on the key itself** — long-press on a
   free key opens the system phone-number picker and stores the choice
   (Preferences DataStore, one key per digit); long-press on an assigned key
   calls immediately. No settings page. Clearing an assignment is a later
   addition if needed.
4. **Voicemail on key 1** — long-press calls the operator voicemail via a
   `voicemail:` URI through the default-dialer call path.

## Components

- `SpeedDialRepository` (interface + DataStore implementation on the existing
  Preferences store): `entries: Flow<Map<Int, String>>`, `set(digit, number)`,
  `clear(digit)`.
- `DialpadViewModel`: `onPaste(text)` filtering, `displayNumber` formatting,
  speed-dial map in the ui state, `saveSpeedDial(digit, number)`.
- `DialpadGrid`: long-press routing — 1 → voicemail, 0 → `+` (existing),
  2–9 → speed dial callback.
- `DialpadScreen`: clipboard read on display long-press; system
  `ACTION_PICK` (phone content type) launcher for assigning a key; calling
  through the existing `onCall`.
- `PhoneCaller`: a voicemail call entry point.

## Testing

ViewModel paste/format/speed-dial tests; repository test on DataStore
(single write per test — Windows rename lock); grid long-press routing via
Compose tests; screen-level picker flow verified in the field.
