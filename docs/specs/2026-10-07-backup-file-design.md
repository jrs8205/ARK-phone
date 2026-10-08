# Backup to a file — design (2026-10-07)

## Goal

Let the user save everything ARK-phone itself owns into one file and restore
it on the same or another phone, so that a reinstall or a phone change does
not mean setting up the app, the blocking rules and the ARK code and links
again. Android's automatic Google backup stays as it is; this is the manual,
Google-free path.

## What goes into the file

- Every entry of the app's preferences DataStore (`settings`): announcement
  settings, all blocking rules and number lists, SIM choices, speed dials,
  the ARK identity (`ark_code`, `ark_nickname`, `ark_device_token`) and the
  ARK calls switch. Entries are exported generically (key, type, value), so a
  preference added later is covered without touching the backup code.
- Excluded: `ark_synced_fcm_token`. It is device state; restoring it would
  stop the new phone from posting its own push token, and the worker would
  keep waking the old phone.
- Room table `ark_links` (linked contacts' ARK codes) in full.
- Room table `whatsapp_calls` (the app-owned WhatsApp call log) in full.

Not included, by design: the system call log, SMS/MMS and contacts. They are
Android's, large, and backed up by Google already.

## File format

One JSON document, UTF-8, suggested name
`ARK-phone-backup-<yyyy-MM-dd>.arkbackup`, MIME `application/octet-stream`.

Envelope, unencrypted:

```json
{ "format": "arkphone-backup", "version": 1, "encrypted": false,
  "payload": { ... } }
```

Envelope, encrypted:

```json
{ "format": "arkphone-backup", "version": 1, "encrypted": true,
  "kdf": { "algorithm": "PBKDF2WithHmacSHA256", "iterations": 600000, "salt": "<base64, 16 bytes>" },
  "cipher": { "algorithm": "AES/GCM/NoPadding", "iv": "<base64, 12 bytes>" },
  "ciphertext": "<base64>" }
```

The ciphertext is the UTF-8 bytes of the payload JSON encrypted with
AES-256-GCM (128-bit tag), the key derived from the password with PBKDF2
(600 000 iterations, 32-byte key), AAD = `arkphone-backup:1`. A wrong
password therefore fails authentication and is reported as "wrong password
or damaged file"; the app never learns which.

Payload:

```json
{ "createdAtMillis": 0, "appVersion": "1.28",
  "preferences": [ { "key": "...", "type": "string|int|long|float|double|boolean|stringSet", "value": ... } ],
  "arkLinks": [ { "numberKey", "number", "code", "nickname", "publicKey", "linkedAtMillis" } ],
  "whatsAppCalls": [ { "callerName", "callerNumber", "type", "timestampMillis", "durationSeconds", "isVideo", "sourcePackage" } ] }
```

Reading caps the file at 16 MB and the iteration count at 5 000 000 so a
crafted file cannot exhaust memory or CPU. `version` other than 1 is refused
with a clear message; a later app may add a migration.

## Password

Optional, on by default. The screen says plainly why it matters: the file
holds the ARK device token, and whoever has the file can take over the ARK
code. Turning the password off keeps that warning visible. The password is
typed twice on export; a mismatch blocks the save. There is no recovery for
a forgotten password.

## Restore semantics

1. The file is read, parsed and, if encrypted, decrypted completely before
   anything is touched, and the confirmation (step 7) shows what it brings.
   Any failure leaves the phone unchanged. The codec never lets a parsing
   exception escape; every malformed shape becomes a `BackupError`.
2. No call may be live across the apply and none may start during it: a
   swapped identity drops the signaling client and strands a live call's
   frames. The view model takes a hold on ARK call admission
   (`ArkCallAdmission`, implemented by `VoipCallCoordinator`) on the main
   thread, the coordinator's own, so the check and the hold cannot
   interleave with a call being admitted; the hold is refused while an ARK
   call is live, ringing included (such a call is not in `CallController`
   yet). Under the hold a carrier call in `CallController` refuses the
   restore too; an incoming ARK call is dropped (the caller's connect
   timeout hands it to the carrier) and an outgoing one goes straight to
   the carrier. The hold is released when the apply ends, however it ends.
3. The snapshot is sanitised (`BackupSanitizer`): the file is untrusted
   input, so nothing is written that the app could not have written itself.
   Only keys the app knows are accepted, each with the type the app reads
   it as (a known key with another type is dropped); strings, sets, row
   texts and row counts are bounded; `announce_mode` and
   `blocked_call_action` must be enum names; the interval, repeat window
   and schedule minutes are clamped; `speed_dial_N` needs N in 2–9; an
   `ark_code` that is not a real ARK code, or an `ark_device_token` that is
   not the base64url shape the worker issues (`^[A-Za-z0-9._~-]{1,256}$`;
   it becomes an Authorization header, where a control character makes
   OkHttp throw at every connect), drops the whole identity; every value
   that can end up in a call intent — speed dials, link numbers, WhatsApp
   caller numbers — must look like a phone number
   (`^\+?[0-9][0-9 ()-]{0,63}$`, so no MMI `*`/`#` sequence), and a link's
   `numberKey` must be what `arkLinkKey(number)` computes; links need a
   valid code; WhatsApp rows need a known call type and non-negative
   duration and time. Offending entries are dropped, the rest restores. A test checks the known-key list against the
   repositories' key objects by reflection so it cannot drift.
4. SIM restrictions (`call_sim_account_id`, `blocking_sim_account_id`) are
   kept only when the account exists on this phone; a stale blocking
   account would make every rule read as "not this SIM".
5. One SQLite transaction spans both stores: the tables are replaced and,
   while that is still uncommitted, the preferences are written in ONE edit
   (clear + write). A failed preferences write is rolled back by SQLite
   itself — there is no compensating write that could fail and lose the
   only copy of the original rows — and the write lock keeps
   `WhatsAppCallMonitor` and the link screen out until the outcome is
   known, so a call recorded meanwhile lands on whichever tables survive.
   What remains is a process death between the preferences file's rename
   and the transaction commit, which would leave new preferences next to
   old tables. The whole apply runs non-cancellable: leaving the screen
   cannot cut it in half.
6. The ARK engine keys on the identity *code* (`ArkVoipStartup`): any
   change drops the current inbox client (`VoipEngine.dropClient()`,
   which also cancels a pending flush drain) and, when an identity exists,
   dials again under it. Because the synced push token is not in the file,
   the next refresh posts this phone's token to the worker, so ARK calls
   now reach this phone; the sync records its marker only for the identity
   it posted for, checked and written in the same DataStore edit, and the
   marker is bound to its account (`ark_synced_fcm_account`, device-only
   like the marker) so one left behind by another identity never counts.
7. The confirmation dialog between steps 1 and 2 says what the file brings
   — the ARK code it would install, with its nickname, and the number of
   linked contacts, as the sanitizer will keep them, or "no ARK code" — and
   that restoring replaces the current settings, rules, ARK code and links,
   and that an ARK code must be used on one phone at a time. A foreign file
   is recognisable before anything is applied.

## UI

Settings gets a row "Backup" that opens `BackupScreen` (same pattern as the
ARK calls screen: own composable, `BackupViewModel` via Hilt, switched in
`SettingsActivity`). The screen has two parts:

- **Save backup to file**: password switch (on), password and repeat fields
  with a visibility toggle, the warning text, button "Save…". The button
  opens the system file creator (`ActivityResultContracts.CreateDocument`)
  with the suggested name; on a chosen location the view model writes the
  file on the IO dispatcher and shows "Backup saved" or the error.
- **Restore from file**: button "Choose file…" opens the system file picker
  (`OpenDocument`). The view model inspects the file; if it is encrypted a
  password field appears. "Restore" decodes the file (off the main thread)
  and opens the confirmation with what it brings; "Restore" there applies
  it and shows "Backup restored" or the error. Cancel forgets the decoded
  file.

Errors shown: not a backup file, unsupported version, wrong password or
damaged file, password required, could not read/write the file.

## Code

- `backup/BackupSnapshot.kt` — the payload model and its JSON (kotlinx
  serialization, `JsonObject` built by hand for the typed preferences).
- `backup/BackupCodec.kt` — envelope encode/decode, PBKDF2 + AES-GCM,
  `BackupError` sealed type. Pure Kotlin, JVM-tested.
- `backup/BackupSanitizer.kt` — the restore-side input validation above.
  Pure Kotlin, JVM-tested.
- `backup/BackupStore.kt` — collects the snapshot from the DataStore and
  Room, applies a snapshot back. Robolectric-tested with a real DataStore in
  a temp folder and an in-memory Room database.
- DAO additions: `ArkLinkDao.all()`, `clear()`, `WhatsAppCallDao.clear()`,
  `insertAll()`.
- `ui/settings/BackupScreen.kt`, `ui/settings/BackupViewModel.kt`.
- Strings in all eleven languages.

## Tests

- Codec: plain round trip; encrypted round trip; wrong password →
  `WrongPasswordOrDamaged`; garbage → `NotABackup`; version 2 →
  `UnsupportedVersion`; every preference type survives a round trip.
- Sanitizer: unknown keys, wrong types, crafted enum values, oversized
  strings/sets, a non-ARK identity code, unusable rows and excess counts
  are dropped or clamped; valid values survive untouched; the known-key
  list matches the repositories' key objects.
- Codec: malformed envelope and payload shapes raise `BackupException`,
  never anything else.
- Sanitizer, continued: a device token the worker could not have issued
  drops the whole identity; MMI sequences in speed dials, link numbers and
  WhatsApp numbers are dropped; a link whose key is not the one computed
  for its number is dropped.
- Connector: a bearer OkHttp cannot put in a header is reported as a
  failed connect, never thrown.
- Store: export keeps only the newest 10 000 WhatsApp calls so the file
  stays restorable; restore drops SIM ids this phone does not have, keeps
  the original rows when the preferences write fails even if the database
  then refuses to write again (fault-injected framework SQLite), keeps a
  WhatsApp call recorded on another thread while the restore is between
  its stores, leaves the preferences alone when the tables fail, finishes
  when its caller is cancelled; export carries preferences, links and calls
  and omits the synced push token; restore replaces all three in one edit
  and leaves the synced token empty even when the file carries one.
- Identity repository: the marker is written only when the stored identity
  is still the one posted for, in the same edit; a restore's edit landing
  between the sync's read and its write leaves no marker; a marker left by
  another account does not satisfy the unchanged-token shortcut.
- Coordinator: an incoming ARK call is dropped and an outgoing one goes to
  the carrier while a restore holds admission; a live (ringing) ARK call
  refuses the hold.
- Startup: an identity whose code changes without passing through `null`
  closes the old inbox socket and dials with the new token.
  Without an identity, startup never asks Firebase for a token; removing
  the identity closes the socket; a client dropped mid-drain cannot ring
  its replacement early; a push sync that lands under another identity
  records no marker.
- View model: codec and store work runs on the IO dispatcher, the decode
  included; decoding shows the ARK code, nickname and link count the
  sanitizer will keep, with nothing applied until confirmed; dismissing the
  preview forgets the decoded file; a restore during a carrier call or a
  live ARK call is refused; ARK calls are held off for the whole apply and
  released after a failure; an export larger than the import limit is
  refused before anything is written; export writes the file and reports
  saved; choosing an encrypted file asks for its password and decoding
  without one reports password required; a non-backup file is reported
  when chosen. The password-repeat check lives in the screen state, not
  the view model.

## Out of scope

Automatic scheduled backups, cloud upload from inside the app, backing up
the system call log or messages, password recovery.
