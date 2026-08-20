# External review brief — group MMS pipeline (2026-08-12 evening)

You are reviewing the **group MMS send / receive / display pipeline** of the
ARK-phone repository (branch `feature/voip-spike`, Android app at commit
`1fa5e06`). This is a **report-only** defect hunt: three field-observed
symptoms remain unexplained after a day of fixes, all listed below with the
device-level evidence already collected. Your job is to find the defects that
produce them — and any adjacent defects in the same pipeline.

## Ground rules

- **Report only. No fixes, no patches, no refactors.** Every finding will be
  verified against the code and fixed by the maintainer separately.
- Finding format: severity P1/P2/P3 · `file:line` · one-sentence claim ·
  concrete failure scenario · which symptom (S1/S2/S3, or "new") it explains ·
  confidence. Sorted by severity. No style notes.
- Distinguish clearly between **app defects** and **carrier-side behavior**
  (an MMSC rewriting or stripping headers). If a symptom is plausibly
  carrier-side, say so and state what evidence would decide it.
- The fixes listed under "Already fixed — context, not findings" were
  verified on devices today. Do not re-report them; do report regressions
  they may have introduced.

## Devices and terminology

Real phone numbers are masked; the **formats are verbatim** (formatting is
load-bearing in this story). Three phones exchange a group MMS:

| Alias | Device | Build | SIMs |
|---|---|---|---|
| **A** | Pixel 8a (tonight's sender) | debug @ `1fa5e06` | Moi/DNA (default, sub 1) + Telia Dot; Moi number is **11 digits** national (`045…`, 8+3) |
| **P** | Pixel 10 Pro (tonight's non-receiving recipient until the last fix; now receives) | debug @ `1fa5e06` | Moi/DNA only; number `044…` (10 digits) |
| **W** | Wife's phone (always received) | beta @ the `80dcfed` wave build (has all receive-path code below but **not** `1fa5e06`, which is send-side only) | carrier unknown; number `044…` (10 digits) |

All three run ARK as the default SMS app. `READ_PHONE_NUMBERS` is granted on
A and P (verified via dumpsys). W's runtime-permission state is **unknown**
(she installs by hand; onboarding should have asked, not verified).

We have full adb access to A and P. **We cannot pull logs or databases from
W** — reasoning about W must work from code plus the A/P evidence.

## The pipeline under review

Send path:
`ui/conversation/ConversationActivity` (opened by `EXTRA_THREAD_ID`) /
`ui/newmessage/NewMessageActivity` (recipient picking) →
`messaging/MmsSender.kt` (`AndroidMmsSender.send`: normalizes recipients,
composes the PDU via `data/mms/MmsPdu.kt` `composeSendReq`, stores the outbox
row + `addr` rows, hands the file to `PlatformMmsTransport` →
`SmsManager.sendMultimediaMessage`) → `SmsSendStatusReceiver` flips the box
on the platform result.

Receive path:
`messaging/WapPushReceiver.kt` (`WAP_PUSH_DELIVER_ACTION`) →
`messaging/MmsDownloader.kt`:
- `onPush` (line ~66): parses the m-notification-ind, **files the placeholder
  row under the sender's 1:1 thread** (`getOrCreateThreadId(context, sender)`,
  line ~76), stores the From addr, kicks `startDownload`.
- `onDownloaded` (line ~125): parses the m-retrieve-conf
  (`parseRetrieveConf` in `data/mms/MmsPdu.kt`), then `storeRetrieved`
  (line ~213) flips the row to m-retrieve-conf, resolves the group via
  `groupRecipients` (line ~246, own-number exclusion + the
  size-≥2-when-own-numbers-incomplete heuristic) and **moves the row to the
  group thread** (`getOrCreateThreadId(context, group)`), inserts To/Cc addr
  rows and parts, returns the abandoned thread id for
  `messagesRepository.recomputeThreadRead`.
- `notifyUnlessBlocked` (line ~319) → `MessageNotifier.notifyMessage`.

Display path:
`ConversationViewModel.open(threadId)` + `data/MessagesRepository` /
`data/SystemMessagesRepository` resolve messages and participants for the
conversation screen; the conversations list and the message notification
have their own title/recipient resolution. **Where each of these three
surfaces gets its participant set from is exactly what S2 asks you to
audit.**

## Already fixed — context, not findings

Four receive-side layers (all shipped this morning, `e36117d..80dcfed`):
`READ_PHONE_NUMBERS` added to the onboarding core set; own-number resolution
prefers API 33 `getPhoneNumber` (carrier+IMS) and a **partly** known own-number
set no longer passes for knowing them all; Cc/Bcc headers (0x82/0x81) are
parsed and counted into the group thread; an insert-address-token From falls
back to the push row's stored sender.

One send-side layer (tonight, `1fa5e06`): recipients are normalized with
`PhoneNumberUtils.normalizeNumber` before the PDU / addr rows / thread id.
Root cause, proven on devices: contact cards synced from a Google account
carry display formatting (`+358 44 …` with spaces), WhatsApp raw contacts
carry clean E164; the picker surfaces both. A spaced To-address went into the
PDU verbatim and **the MMSC silently dropped that recipient** while
delivering the clean one in the same PDU. After the fix, P receives (first
time ever for a fresh group compose — see evidence).

## Tonight's field test (after `1fa5e06` on A and P)

A composed a fresh group message ("testi") to W and P and sent it at 19:48.

**Result:** P now receives — but shows **two** messages: first an **empty
message from A** (a bare 1:1 conversation), then the real group message.
Opening the group conversation shows **only A** where the participants
should be A and W. W received her copy but it is a **pure 1:1 from A** — no
trace of P anywhere on her phone.

### Device evidence, sender A

Sent row `content://mms/145`: `thread_id=55`, `m_type=132`, `msg_box=2`
(platform reported success), `sub_id=1` (Moi). Its addr rows — both To
(type 151), both clean E164 (the normalization works):

```
addr: address=+<W, clean E164>, type=151
addr: address=+<P, clean E164>, type=151
```

Thread 55 = `recipient_ids: <W-canonical> <P-canonical-clean>`. (The
previous evening's failed send sits in thread 54 whose P-canonical is the
*spaced* variant — two parallel sent-threads on A are expected debris of the
format bug, listed here so you don't chase them.)

### Device evidence, receiver P

Exactly **one** new MMS row and **zero** new SMS rows:

```
content://mms/67: thread_id=94, m_type=132, msg_box=1, sub_id=1,
                  ct_l=http://mmsc2.dna.fi/…   (download succeeded)
part: ct=text/plain, text="testi"
addr: address=+<A>, type=137 (From)
addr: address=+<W>, type=151 (To)
addr: address=+<P>, type=151 (To)
```

So **DNA delivered the complete To-list** — no carrier stripping on this leg.
The provider's threads table, both rows created within the same second:

```
thread 93: message_count=1, recipient_ids=<A>            ← the "empty message"
thread 94: message_count=1, recipient_ids=<A> <W>        ← the real group
```

Thread 94's membership is **correct** (A + W, P's own number excluded — the
own-number logic worked). Thread 93 is the push placeholder's 1:1 thread:
`onPush` filed the notification-ind there, `storeRetrieved` moved the row to
thread 94, and the emptied thread survives with a stale `message_count=1`
and **no messages in it** — rendered in the app as an empty conversation
from A.

### Symptoms to explain

- **S1 — ghost empty 1:1 thread on the receiver.** The placeholder thread
  (93) outlives the re-threading move. Audit the whole move: who deletes an
  emptied thread (nobody?), what the telephony provider's message_count
  triggers do on a `THREAD_ID` **update** (as opposed to insert/delete),
  what `recomputeThreadRead` covers and what it cannot, what tapping the
  ghost conversation does, and whether a notification posted between push
  and download would target the doomed thread id.
- **S2 — the opened group shows only the sender.** Thread 94's canonical
  set is correct in the provider, yet the conversation screen's participant
  area shows only A. Find where `ConversationViewModel.open(threadId)` /
  the repositories resolve participants for (a) the conversation screen,
  (b) the conversations list row, (c) the notification — the list row
  reportedly showed both recipients while the opened screen showed only A,
  so at least two of those three disagree. Likely candidates: participants
  derived from the messages' addr rows (only From present pre-download, or
  only the From row consulted) instead of the thread's canonical set, or a
  1:1-shaped screen fed a group thread.
- **S3 — W sees a pure 1:1, no trace of P.** Her receive code is identical
  to P's, where the same PDU produced a correct group thread. Reason about
  `groupRecipients` on W for `From=A, To=[W, P]`: with own numbers complete
  `{W}` → others `[P]` → group `{A, P}`; with own numbers empty/incomplete →
  others `[W, P]` (size 2) → group `{A, W, P}`. **Both** outcomes are groups,
  so if her app got the full To-list she should see a group either way —
  which points at her carrier's MMSC stripping co-recipients on delivery
  (unprovable without her device; DNA's MMSC did *not* strip on the A→P
  leg). Before settling for the carrier explanation, audit
  `parseRetrieveConf` (`data/mms/MmsPdu.kt`) for any way a well-formed
  multi-To retrieve-conf parses to a single or empty To list (header
  ordering, encoded-string vs address-present-token forms, the
  `/TYPE=PLMN` suffix, charset-tagged strings), and `WapPushReceiver` /
  `onPush` for paths that could bypass `storeRetrieved`'s addr insertion.

## Additional audit surface (same pipeline, likely co-located defects)

- `PhoneNumberUtils.compare`-based member matching in `groupRecipients` and
  anywhere else members are matched: A's number is **11 digits** national —
  check for loose-suffix false positives/negatives between the three
  numbers, and between spaced/clean variants that still coexist in old
  canonical rows.
- `Telephony.Threads.getOrCreateThreadId` input hygiene: sender strings from
  the push (`onPush` uses the raw From), recipients from `conf.to`/`conf.cc`
  with the PLMN suffix removed — mixed formats mint parallel canonical rows
  (we already have spaced-vs-clean debris); check every call site for
  un-normalized input.
- The outbox row is stored with `m_type=132` (m-retrieve-conf) for a
  **sent** message (`AndroidMmsSender.storeOutboxRow`) — verify nothing
  downstream (display, notifications, delivery status, the S2 participant
  resolution) misclassifies sent group messages because of it.
- `MessageNotifier.notifyMessage(threadId, sender, …)` is called after the
  move with the **new** thread id but a single sender string — check the
  notification's conversation identity, reply target and grouping for group
  threads (the quick-reply SIM/membership fixes from this morning sit right
  here).
- The empty-message rendering itself: what does the conversation screen show
  for an m-notification-ind row (m_type 130) while the download is pending,
  and for a thread whose only message was moved away mid-view.

## What "done" looks like

A ranked findings list in the format above — nothing else. For each of
S1/S2/S3 either a concrete code-level cause (file:line + failure scenario)
or an explicit "carrier-side / not reproducible from code" verdict with the
evidence that would settle it. New defects found along the way are welcome;
patches are not.
