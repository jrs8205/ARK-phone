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
   anything is touched. Any failure leaves the phone unchanged.
2. The preferences DataStore is cleared in one edit, then the restored
   entries are written in a second edit. The gap makes the identity flow
   emit `null` and then the restored identity, which is what makes the ARK
   engine drop its current connection and reconnect under the restored code
   (`ArkVoipStartup` keys on identity presence). Because the synced push
   token is not in the file, the next startup posts this phone's token to
   the worker, so ARK calls now reach this phone.
3. `ark_links` and `whatsapp_calls` are replaced inside one Room
   transaction; the WhatsApp rows get fresh ids.
4. The confirmation dialog before step 1 says that restoring replaces the
   current settings, rules, ARK code and links, and that an ARK code must be
   used on one phone at a time.

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
  password field appears. "Restore" asks for confirmation, then restores
  and shows "Backup restored" or the error.

Errors shown: not a backup file, unsupported version, wrong password or
damaged file, password required, could not read/write the file.

## Code

- `backup/BackupSnapshot.kt` — the payload model and its JSON (kotlinx
  serialization, `JsonObject` built by hand for the typed preferences).
- `backup/BackupCodec.kt` — envelope encode/decode, PBKDF2 + AES-GCM,
  `BackupError` sealed type. Pure Kotlin, JVM-tested.
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
- Store: export carries preferences, links and calls and omits the synced
  push token; restore replaces all three and leaves the synced token empty;
  the identity flow goes through `null` during a restore.
- View model: export writes the file and reports saved; a password mismatch
  blocks the save; restore of an encrypted file without a password reports
  password required.

## Out of scope

Automatic scheduled backups, cloud upload from inside the app, backing up
the system call log or messages, password recovery.
