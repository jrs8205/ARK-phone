# ARK-phone feature backlog

Legend: `[x]` done · `[~]` partial · `[ ]` planned. Items under **Under discussion**
need a design conversation before implementation. This list is aspirational — items
may be re-scoped or dropped.

## Under discussion (next up)

- [x] Spoken caller announcement (TTS): speak the contact name for saved contacts,
      "unknown caller" otherwise; offered as an opt-in setting
- [x] Contact photo on the incoming-call screen, including on the lock screen
- [x] In-app settings screen (gear on the Home tab)
- [x] App-owned missed-call notification with launcher badge and call-back action
- [x] SIM card information page under settings (read-only + link to system settings)
- [x] Voice-only announcement mode with an adjustable repeat interval (4–10 s)
- [x] Call history round (agreed 2026-07-24): per-number detail view opened from
      the log, with a 3-dot menu (copy number, edit before call, block number,
      delete history), per-person statistics, and WhatsApp calls labeled in the log
- [ ] WhatsApp announcement round: notification listener + spoken caller
      announcement for WhatsApp calls, opt-in settings toggle
      (WhatsApp's own ringtone cannot be silenced programmatically)
- [ ] SMS / messaging support

Play policy note: call-log permissions are restricted on Google Play; the
default-dialer role is an accepted use. The app must hold the role before
requesting them and stop using them if the role is revoked.

## Keypad

- [x] Standard phone keypad
- [x] T9 search by name and by number
- [ ] Paste a number from the clipboard
- [ ] Automatic number formatting while typing
- [ ] Speed dial via long-press
- [ ] Call voicemail via long-press on 1
- [ ] SIM selection before placing a call
- [ ] Remember the last-used SIM per contact

## Contacts

- [x] Browse and search device contacts
- [x] Favorites
- [ ] Frequently used contacts
- [ ] Add and edit contacts
- [x] Profile photos (round avatars)
- [ ] Multiple numbers per contact (model currently holds a single number)
- [ ] Custom labels, e.g. work, family, customer
- [ ] Personal notes on contacts

## Call history

- [x] Incoming, outgoing, missed and rejected calls
- [x] Blocked calls in the log
- [ ] Calls handled by an AI agent in the log
- [x] Call date, time and duration
- [ ] SIM used per call
- [ ] Search by name, number or date
- [ ] Custom filters
- [ ] Grouping of consecutive calls from the same number
- [ ] Call statistics
- [x] Contact counts per person (detail-view statistics)
- [x] Per-person latest call and total talk time (detail-view statistics)
- [ ] Callback reminders

## Incoming call view

- [x] Large caller name and number
- [x] Caller photo
- [~] Indication of whether the number is a saved contact (name shown when saved)
- [ ] Spam-call suspicion indicator
- [ ] Business/CNAP caller name when the network provides one
- [ ] "Last called ..." info on the incoming screen
- [ ] SIM used for the incoming call
- [ ] Full-screen vs compact heads-up preference
- [ ] AMOLED black theme option
- [ ] Color coding for known, unknown and suspected-spam callers
- [x] Answer
- [x] Decline
- [ ] Silence the ringer without declining
- [ ] Decline with an SMS reply
- [ ] Block the number from the call view
- [ ] Hand the call to an AI agent (requires operator-side routing support)
- [ ] "Always AI for this number" option

## Ongoing call view

- [x] Answer and hang up
- [x] Microphone mute
- [x] Speakerphone
- [ ] Bluetooth audio device selection
- [ ] Wired/earpiece audio route selection
- [x] Hold
- [ ] Add a second call
- [ ] Swap between calls
- [ ] Conference call
- [x] DTMF keypad
- [x] Call duration display
- [ ] Open the caller's contact card during a call
- [ ] Notes during a call
- [ ] Post-call reminder prompt

## Call blocking and screening

- [x] Block a single number (from the call detail view; system-wide block list)
- [ ] Block unknown numbers
- [ ] Block private/hidden numbers
- [ ] Block numbers not in contacts
- [ ] Block by country code
- [ ] Block by number prefix/pattern
- [ ] Block foreign numbers
- [ ] Block premium-rate numbers
- [ ] Scheduled (time-based) blocking
- [ ] Separate block lists per SIM
- [ ] Allow list (whitelist)
- [ ] Always allow favorites
- [ ] Allow repeat callers (e.g. second call within minutes)
- [ ] Silent blocking without a notification
- [ ] Separate log for blocked calls

## Spam detection

- [ ] User's own reports
- [ ] Local number database
- [ ] Community reporting service
- [ ] Categories: sales, scam, robocall, safe
- [ ] Warnings for suspicious numbers
- [ ] Automatic number classification
- [ ] Possible self-hosted service for reported numbers

## Operator voicemail

- [ ] Voicemail button
- [ ] Voicemail number setting
- [ ] Separate voicemail number per SIM
- [ ] One-tap call to voicemail
- [ ] Notification for new voicemail messages
- (Voicemail messages themselves remain in the operator's service)

## Technical debt / polish

- [ ] CallStyle notification for the ongoing call (hang-up button in the notification)
- [ ] InCallFinishGuard: same-frame tie at the grace boundary + combined
      "call arrives near grace expiry then ends" test
- [ ] `DeviceDefault` parents for window themes
- [ ] Monochrome launcher icon (lint warning)
- [ ] targetSdk bump when a newer stable SDK is available
