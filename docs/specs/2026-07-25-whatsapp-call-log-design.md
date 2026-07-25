# WhatsApp call log — design

Date: 2026-07-25. Approved by the user in the design discussion.

## Problem

WhatsApp on this device writes nothing to the system call log, so WhatsApp
calls never appear in Recents or the call detail view even though the
`CallSource.WHATSAPP` labeling already exists. Truecaller shows WhatsApp
calls by recording them itself from its notification listener; ARK-phone
does the same with the listener it already has for the caller announcement.

## Scope

Record incoming (answered and missed) and outgoing WhatsApp calls into an
app-owned log, merge them into Recents and the call detail view, and let
the user start a WhatsApp voice call directly from the log. Answering
stays in WhatsApp — there is no API for answering another app's call, so
"manage in our own app" means: see, hear, log and call back.

## Notification classification

WhatsApp `CATEGORY_CALL` notifications split three ways:

- **Ringing** — tag contains `ringing_call` (verified in the field).
- **Missed-call summary** — tag contains `missed_calls` (seen in dumpsys);
  ignored, our own lifecycle already records the miss.
- **Ongoing call** — any other call-category notification;
  `android.showChronometer` is the supporting signal. The exact shape on
  this device is unverified, so implementation starts by extending the
  existing diagnostic log line with removal events and the chronometer
  flag; the user makes three test calls (missed, answered, outgoing) and
  the rule is locked against real data.

The caller (name or number) comes from the same extraction the announcer
uses: CallStyle person, else title unless it is the multi-account
"[ +358 45 ... ]" shape, else the text line ("Äänipuhelu henkilöltä X" /
"from X" / first phone-number match). `isVideo` comes from the text
("Videopuhelu" / "video call") and is stored but has no UI yet.

## State machine — WhatsAppCallMonitor

New singleton in the telecom layer fed by the listener's posted/removed
events:

- Ringing posted → remembered as pending (caller, start time).
- Ringing removed → 5 s grace; if an ongoing notification appeared, the
  call was **answered** (recorded when the ongoing notification goes away,
  duration = its lifetime); otherwise record **missed** now.
- Ongoing posted with no pending ringing → **outgoing** (recorded on
  removal with duration).
- A pending ringing older than 10 min is discarded (stale guard).
- Process death mid-call loses at worst the duration — best effort, same
  as Truecaller.

## Storage — Room

Chosen over a JSON DataStore file (hundreds of rows over time; every
DataStore edit rewrites the whole file) and over writing to the system
call log (rejected: WhatsApp callers often have no number, rows would mix
into other apps' data, deletes get murky).

- `WhatsAppCallEntity`: id, callerName?, callerNumber?, type
  (INCOMING/OUTGOING/MISSED), timestampMillis, durationSeconds, isVideo.
- `WhatsAppCallDao`: Flow of all calls newest-first, insert, delete by
  number or by name.
- `ArkPhoneDatabase` version 1, provided via Hilt.
- `WhatsAppCallLogRepository` interface + Room implementation mapping
  entities to `CallLogEntry` (source WHATSAPP, number "" when unknown).

## Merge — existing UI unchanged

`CombinedCallLogRepository` implements the existing `CallLogRepository`:
combines the system log and the WhatsApp log sorted by timestamp, negates
WhatsApp ids to keep list keys unique, and fans `deleteCallsFor` out to
both. Hilt binds it as the `CallLogRepository`. Recents and CallDetail
pick the rows up as-is; the WhatsApp badge already exists. Entries with
only a name (number "") group by name in the detail view and hide the
phone-call actions.

## WhatsApp calling from the log (Truecaller model)

- Recents row for a WhatsApp call: name/number, type + "WhatsApp" + time
  (existing suffix), and the trailing phone button replaced by a
  **WhatsApp icon** (own drawn vector in WhatsApp green — the official
  logo is trademarked and not bundled).
- Tapping it starts a WhatsApp voice call directly: look up the contact's
  `vnd.android.cursor.item/vnd.com.whatsapp.voip.call` row in
  `ContactsContract.Data` (by number, else by display name) and fire
  ACTION_VIEW on it, package-locked to WhatsApp. Fallback when the caller
  is not a contact: open the wa.me chat for the number; last resort,
  launch WhatsApp.
- The same WhatsApp call action appears in CallDetail for numbers with
  WhatsApp history.
- New injectable `WhatsAppCaller` owns the lookup and intent building.

## Settings

None. Recording is on whenever notification access is granted; missed
WhatsApp calls get no extra notification of ours (WhatsApp posts its own).

## Testing

Classification and caller extraction as pure-function unit tests; the
monitor on virtual time; DAO on in-memory Room under Robolectric; merged
repository merge/delete tests; a Recents UI test for the WhatsApp icon.
Field verification with the three test calls closes the round.
