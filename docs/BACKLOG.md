# ARK-phone feature backlog

Legend: `[x]` done · `[~]` partial · `[ ]` planned. Items under **Under discussion**
need a design conversation before implementation. This list is aspirational — items
may be re-scoped or dropped.

## Under discussion (next up)

- [ ] Spoken caller announcement (TTS): speak the contact name for saved contacts,
      "unknown caller" otherwise; offered as an opt-in setting
- [ ] Contact photo on the incoming-call screen, including on the lock screen
- [ ] In-app settings screen with SIM card settings and information
- [ ] SMS / messaging support

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
- [ ] Blocked calls in the log
- [ ] Calls handled by an AI agent in the log
- [x] Call date, time and duration
- [ ] SIM used per call
- [ ] Search by name, number or date
- [ ] Grouping of consecutive calls from the same number
- [ ] Call statistics
- [ ] Callback reminders

## Incoming call view

- [x] Large caller name and number
- [ ] Caller photo (under discussion, see above)
- [~] Indication of whether the number is a saved contact (name shown when saved)
- [ ] Spam-call suspicion indicator
- [ ] SIM used for the incoming call
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
- [ ] Notes during a call

## Call blocking and screening

- [ ] Block a single number
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
