# ARK-phone user guide

How to set ARK-phone up and use it. For what the app is and where to get it,
see the [README](../README.md).

## First start

ARK-phone asks to become your default phone app and for the permissions it
needs (call log, contacts, phone, microphone, notifications). Without the
default-app role it cannot answer or place calls. Becoming the default SMS app
is optional and is offered when you open Messages for the first time.

Settings live behind the gear icon on the home screen.

## ARK internet calls

ARK calls are free, end-to-end encrypted calls between two phones that both
run ARK-phone. They are off until you create a code.

### Create your ARK code

1. Open **Settings → ARK internet calls**.
2. Type a nickname — the name your friends will see — and tap
   **Create ARK code**. The code looks like `ARK-XXXX-XXXX`.
3. Tap **Share code** to send it to the people you want to call.
4. Allow the microphone and notifications when asked. To ring on a locked
   screen, also allow the full-screen notification when the app offers it.

Use a code on one phone only. A new phone gets a new code — or your old one
through a [backup](#backup-and-restore).

### Link a friend

1. Open the friend's contact card.
2. Enter the ARK code they sent you and tap **Link**. The card now shows the
   link; tap it again to unlink.

Both phones need to link each other: your friend links your code on your
contact card the same way.

### Make and receive calls

Call a linked contact as you always do — from the keypad, the history or the
contact card. If the friend's phone is reachable, the call goes over the
internet and the in-call screen says so. If it is not (the phone is off, has
no connection, or has ARK calls switched off), the very same call goes over
your carrier instead; you do not need to try again.

An incoming ARK call rings like any other call, also on a locked and sleeping
phone, and appears in the history like any other call.

### Switch ARK calls off

The switch **Use ARK internet calls** at the top of the ARK screen sends every
call over the carrier again. Your code and links are kept.

## Calls

- **Keypad**: type a name or number; T9 matching finds contacts as you type.
  Long-press 2–9 to assign or dial a speed dial. Paste a number from the
  clipboard with the paste button.
- **In a call**: mute, speaker, hold and the keypad are on the call screen.
  The screen turns off when the phone is at your ear.
- **History**: search, and filter by missed, outgoing, blocked or WhatsApp
  calls. Repeated calls from one number are grouped; tap a row for details
  and a callback. WhatsApp calls have a direct WhatsApp callback.
- **Contacts**: favorites and most-called first, then everyone. Edits open the
  system contact editor and sync to your Google account.

## Call blocking

Open **Settings → Call blocking**. For the rules to work, ARK-phone must be
the phone's caller ID & spam app — the screen offers to set it.

- **Block all calls**: only favorites, allowed numbers and repeat callers get
  through. Combine with **Block only at set times** for quiet nights.
- **Block hidden numbers**, **Block callers not in your contacts**.
- **Blocked prefixes**: numbers starting with a prefix (for example `+358700`)
  are always blocked, even saved contacts and outside the schedule.
- **Blocked numbers** and **Allowed numbers**: always blocked, or always
  through, whatever the other rules say.
- **Let repeat callers through**: a number that calls again within the time
  window is not blocked.
- **Apply the rules to**: all SIMs or one of them.
- **What happens to a blocked call**: reject immediately, or let it ring to
  voicemail — your phone stays silent and shows nothing, the caller hears
  normal ringing.

Blocked calls do not ring and are listed in the history as blocked.

## Spoken caller announcement

**Settings → Spoken caller announcement** reads the caller's name (or number)
aloud while the phone rings, repeated at the interval you choose, also on the
lock screen. It uses the phone's speech engine; if the engine lacks your
language, the screen says so and links to the system's speech settings.
**Announce WhatsApp calls** does the same for WhatsApp and needs notification
access, which the screen asks for.

## Messages

With ARK-phone as the default SMS app, Messages shows your conversations with
unread indicators and search. Picture messages (MMS) and group messages work
in both directions; a failed picture download can be retried with a tap.
Notifications offer quick reply and mark-as-read. Select several messages to
delete or share them; text and pictures shared from other apps open a new
message. Blocked senders never ring or notify.

## Dual SIM

**Settings → SIM for calls** chooses the SIM ARK-phone calls with; each call
in the history shows the SIM it used. **Settings → SIM cards** shows the
operator, number, slot and country of each SIM, and opens the system's SIM
settings.

## Backup and restore

**Settings → Backup** saves everything ARK-phone itself owns — settings,
blocking rules, speed dials, your ARK code with its linked contacts and the
WhatsApp call history — into one file. Calls, messages and contacts are not
included; they are Android's and stay in your Google backup.

- **Save backup to file**: keep the password on. The file contains the key to
  your ARK code, and anyone who has the file can take over the code. A
  forgotten password cannot be recovered.
- **Restore from file**: choose the file, enter its password, and the app
  shows what the file brings — the ARK code and the number of linked
  contacts — before anything is replaced. An ARK code must be used on one
  phone only: after restoring onto a new phone, create a new code on the old
  one or stop using it.

A restore is refused while a call is in progress.

## Languages

The app follows the device language — English, Finnish, Swedish, German,
French, Spanish, Estonian, Russian, Portuguese, Italian or Polish — and falls
back to English. On Android 13 and newer you can choose a language for
ARK-phone alone in the system's app language settings.

## Troubleshooting

- **A call to a linked friend went over the carrier.** The friend's phone was
  not reachable within a few seconds: it was off, had no internet, had ARK
  calls switched off, or had not allowed the microphone. The call still went
  through, over the carrier.
- **Incoming ARK calls do not ring on a locked phone.** Allow notifications
  and the full-screen notification for ARK-phone (the ARK screen asks for
  both), and exclude ARK-phone from battery optimisation in the system
  settings.
- **The caller's name is not spoken.** Check the speech engine in
  **Settings → Spoken caller announcement**; the screen says when the engine
  or your language is missing.
- **Blocking rules have no effect.** Set ARK-phone as the caller ID & spam
  app from **Settings → Call blocking**.
- **Group messages arrive as separate conversations.** Some carriers strip
  the other recipients from group MMS; the messages are the same, only the
  grouping is lost.
