# SMS Messaging — Design

Date: 2026-07-29
Status: Approved

## Goal

ARK-phone becomes the device's default SMS app, replacing Google Messages for
daily messaging: conversations, reading, sending, notifications. Existing
SMS/MMS messages must be visible immediately on first open — they already live
in the system message store this design reads from.

Features included in the first version:

- Conversation list, thread view, compose (new message via recipient picker)
- Search across contact names/numbers and message text
- Delivery reports on sent SMS (always requested, no settings toggle)
- SIM selection for sending; the default is always the primary messaging SIM
  configured in Android settings
- Quick reply directly from the notification
- Messages from numbers on the app's blocked-numbers list are silenced
- MMS: receive and display (including incoming group messages); send a single
  image to a single recipient

## Non-goals

- Composing group messages (group MMS). Incoming group messages are displayed;
  reply behaviour in group threads is out of scope for v1 (composer disabled
  in group threads).
- RCS. Third-party apps cannot access RCS; all traffic is SMS/MMS. Existing
  RCS chat history stays readable only in Google Messages — it is not in the
  system SMS/MMS store and will not appear in ARK-phone.
- Scheduled sending, message backup (the system store is the store), a
  delivery-report settings toggle, a background resend queue.

## Architecture decision: system message store is the single source of truth

The app keeps no message database of its own. As the default SMS app it must
write incoming messages to the system Telephony provider (`Telephony.Sms`,
`Telephony.Mms`, threads via `Telephony.Threads`) anyway, so the UI reads
conversations, threads and search results directly from the provider through
`observedQueryFlow` — the same reactive query pattern the call log and
contacts already use. App-specific state goes to the existing Preferences
DataStore only where the provider has no field (none identified for v1).

Consequences:

- One source of truth; no synchronisation bugs.
- Switching the default SMS app back and forth loses nothing; both apps read
  the same store.
- The provider API is old and quirky, especially MMS parts. Fossify Messages
  is added to `reference/` as a git-ignored shallow clone (read-only, like
  AOSP Dialer and Fossify Phone) and used as the reference for provider and
  MMS handling.

## Default-SMS role and manifest components

`RoleManager.ROLE_SMS` requires four manifest components before Android will
offer the app as a choice:

1. `SMS_DELIVER` broadcast receiver (permission `BROADCAST_SMS`) — incoming
   SMS arrive only here for the default app.
2. `WAP_PUSH_DELIVER` broadcast receiver (permission `BROADCAST_WAP_PUSH`,
   MIME `application/vnd.wap.mms-message`) — MMS notifications.
3. A compose activity handling `ACTION_SENDTO` for `sms:`/`smsto:`/`mms:`/
   `mmsto:`. The existing "Message" actions in the contact card and call
   detail switch from launching the system SMS app to opening this screen.
4. `HeadlessSmsSendService` handling `ACTION_RESPOND_VIA_MESSAGE` (permission
   `SEND_RESPOND_VIA_MESSAGE_SERVICE`) — the system's "respond via message
   during a call" hook.

New permissions: `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH`
(`SEND_SMS` already declared).

Role request UX follows the call-screening-role pattern: a banner at the top
of the Messages tab ("Set ARK-phone as the default SMS app") whenever the role
is not held, firing `createRequestRoleIntent(ROLE_SMS)`. Without the role the
tab works read-only (conversations and search via `READ_SMS`, requested
contextually on the tab); sending and notifications activate with the role.
Each test phone can adopt the role at its own pace, and switching back in
Android settings is always possible without data loss.

## UI

**Messages tab** — fourth bottom-nav tab (speech-bubble icon; unread
conversation count as a badge on the icon). Top-down: the shared `SearchField`,
then the conversation list in the app's rounded card style. Row: contact
avatar (name resolved via the same PhoneLookup mechanism as calls), name or
number, snippet of the latest message, timestamp; unread rows bold with a dot.
Search matches contact name/number and message body text; tapping a result
opens the thread. A pencil FAB starts a new message → recipient picker
(contact search or manual number entry). Long-press on a conversation row
copies the number (app-wide convention).

**Thread view** — its own activity (like `CallDetailActivity`). Messages as
bubbles: incoming left, outgoing right, date separators between days. MMS
images render inline in the bubble; tap opens the image full screen. Outgoing
messages show a status line: sending → sent → delivered, or failed with
tap-to-retry. Top bar: contact name and avatar (tap opens the contact card),
call button, and a menu with delete conversation and block number. Bottom:
the composer with an attachment icon (system photo picker → MMS) and send
button; on multi-SIM devices a SIM chip next to send (hidden on single-SIM
devices, same principle as `simLabelFor`). Opening a thread marks it read and
clears its notification.

## Send path

**SMS** — sent with `SmsManager` for the chosen subscription id; long texts
split with `divideMessage` into multipart messages. The app writes the
outgoing message to the provider itself and updates its status from two
callbacks: *sentIntent* (reached the network → "sent", or error → "failed")
and *deliveryIntent* (delivery report → "delivered"). Delivery reports are
always requested. A failed message stays in the thread in a failed state and
is retried by tapping it; no background resend queue.

**SIM selection** — the composer's SIM chip defaults to Android's default
messaging subscription (`SubscriptionManager.getDefaultSmsSubscriptionId()`).
Tapping the chip switches SIMs; the choice holds while the conversation is
open and reverts to the system default on the next open.

**MMS (single image, single recipient)** — the picked image is compressed
under the carrier's maximum message size (from `CarrierConfigManager`), a
send request PDU is assembled (own implementation, Fossify Messages as
reference) and handed to `SmsManager.sendMultimediaMessage`. States:
sending / sent / failed with tap-to-retry — MMS has no SMS-style delivery
report.

## Receive path

**SMS** — the `SMS_DELIVER` receiver assembles multipart messages, writes the
message to the provider inbox and posts the notification; works from a cold
process (manifest-registered receiver).

**MMS** — the WAP push receiver stores the notification indication and starts
the download immediately (`SmsManager.downloadMultimediaMessage`). If the
download fails, the thread shows a "download failed — tap to retry" row.

**Notifications** — a new "Messages" channel. Per-conversation
`MessagingStyle` notifications with a `RemoteInput` quick-reply action (reply
sends on Android's default messaging SIM) and a "mark as read" action.
Tapping opens the thread.

**Blocked numbers** — an incoming message from a number on the app's own
blocked-numbers list (`DataStoreBlockedNumbersRepository`, `sameCaller`
matching) is stored to the provider already marked read, with no notification
and no sound. The message remains visible if the user opens the thread. Call
*rules* (unknown-caller blocking, schedules, per-SIM limits) deliberately do
NOT apply to messages — otherwise verification codes from short codes and
unknown senders would be silenced.

## Data layer

- `MessagesRepository` (interface + Android implementation on the provider,
  `observedQueryFlow` for reactivity): conversations, thread messages,
  search, mark-read, delete thread.
- `SmsSender` and `MmsSender` fun interfaces seam the send path for unit
  tests, like `TelecomCallPlacer` on the call side.
- Logic as pure functions where possible: thread grouping, status mapping,
  blocked check, MMS PDU assembly, search matching.

## Error handling

- `READ_SMS` missing → the tab shows a contextual permission request.
- Role not held → banner; read-only tab otherwise functional.
- Send failure → per-message failed state with retry; a Toast is not needed
  because the state is visible in the thread.
- MMS download failure → retry row in the thread.

## Testing

TDD throughout. Pure functions as plain JUnit tests; receivers and
repositories with Robolectric; new screens with Compose tests. Field rollout:
the role is taken first on the Pixel 8a (debug build), the other phones after
the first round of field testing.
