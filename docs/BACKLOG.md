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
- [x] WhatsApp announcement round: notification listener + spoken caller
      announcement for WhatsApp calls at the 4–10 s repeat interval, opt-in
      settings toggle (WhatsApp's own ringtone cannot be silenced
      programmatically)
- [x] WhatsApp call log round (2026-07-25): app-owned log of incoming, answered
      and outgoing WhatsApp calls merged into Recents and the detail view, with
      a WhatsApp button that starts a WhatsApp voice call directly
- [ ] SMS / messaging support — agreed to be the LAST big project; smaller items
      below come first (agreed 2026-07-25)

Play policy note: call-log permissions are restricted on Google Play; the
default-dialer role is an accepted use. The app must hold the role before
requesting them and stop using them if the role is revoked.

## Keypad

- [x] Standard phone keypad
- [x] T9 search by name and by number
- [x] Paste a number from the clipboard (long-press the number display)
- [x] Automatic number formatting while typing (display only)
- [x] Speed dial via long-press (keys 2–9, assigned from the key itself;
      clearing an assignment is not built yet)
- [x] Call voicemail via long-press on 1
- [x] SIM selection before placing a call (2026-07-26: a default-SIM setting,
      like the system's own, shown only on a multi-SIM device)
- [ ] ~~Remember the last-used SIM per contact~~ — dropped 2026-07-26: one
      clear default setting is enough and less surprising

## Contacts

Agreed direction (2026-07-25): the system ContactsProvider stays the single
source of truth — the app never keeps its own contact store. Reading shows
everything the provider holds; edits (when built) write to the provider on the
user's Google account so they sync to Google like edits made in the Google
Contacts app. New contacts must be saved on the Google account, not
device-only.

- [x] Browse and search device contacts
- [x] Favorites
- [x] Frequently used contacts (most-called section on the contacts tab, v1.13)
- [x] Full contact details view: addresses, emails, birthdays, organization,
      notes, connected apps (WhatsApp/Telegram/Signal actions with real app
      icons), share as vCard, block numbers (v1.8–v1.9)
- [x] Add and edit contacts (v1.13: the + button and the card's pencil open
      the system/Google contact editor — saves go to the Google account and
      sync like edits made in Google Contacts; an in-app form remains a
      possible later upgrade)
- [x] Profile photos (round avatars)
- [~] Multiple numbers per contact (the contact card shows them all; the
      list model still picks one)
- [x] Custom labels, e.g. work, family, customer (shown on the card;
      editable via the system editor)
- [x] Personal notes on contacts (shown on the card; editable via the
      system editor)

## Call history

- [x] Incoming, outgoing, missed and rejected calls
- [x] Blocked calls in the log
- [ ] Calls handled by an AI agent in the log
- [x] Call date, time and duration
- [x] SIM used per call (2026-07-26)
- [x] Search by name or number (home screen, Pixel-style; digit search
      matches national and international formats)
- [x] Filter chips: All / Missed / Outgoing / WhatsApp (horizontally
      scrollable row)
- [x] Grouping of consecutive calls from the same number ("Name (3)")
- [ ] Call statistics
- [x] Contact counts per person (detail-view statistics)
- [x] Per-person latest call and total talk time (detail-view statistics)
- [ ] Callback reminders
- [ ] Unanswered outgoing WhatsApp calls are not recorded (their notification
      lifecycle carries no distinguishing signal — known gap from v1.7)

## Incoming call view

- [x] Large caller name and number
- [x] Caller photo
- [~] Indication of whether the number is a saved contact (name shown when saved)
- [ ] Spam-call suspicion indicator
- [x] Business/CNAP caller name when the network provides one (was already
      wired: callerDisplayName is the fallback when the caller isn't saved)
- [x] "Last called ..." info on the incoming screen (v1.11)
- [x] SIM used for the incoming call (2026-07-26; also shown during the call)
- [ ] Full-screen vs compact heads-up preference
- [ ] AMOLED black theme option
- [~] Color coding for known, unknown and suspected-spam callers (unknown
      numbers get a warning tag since v1.11; spam awaits spam data)
- [x] Answer
- [x] Decline
- [x] Silence the ringer without declining (v1.11)
- [x] Decline with an SMS reply (canned replies, direct send; v1.11)
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
- [x] Open the caller's contact card during a call (tap the avatar, v1.13)
- [ ] Notes during a call
- [ ] Post-call reminder prompt

## Call blocking and screening

- [x] Block a single number (from the call detail view; system-wide block list)
- [x] Block private/hidden numbers (v1.12, screening service; needs the
      caller ID & spam role — requested from the blocking settings page)
- [x] Block numbers not in contacts (v1.12)
- [x] Block by number prefix/pattern (v1.12; also covers country codes,
      foreign and premium prefixes via the user's own list)
- [x] Scheduled (time-based) blocking (rules limited to a time window,
      overnight windows supported; v1.13)
- [x] Blocking limited to one SIM (2026-07-26; one rule set with a SIM
      scope rather than separate lists per SIM)
- [x] Allow list (whitelist) — allowed numbers always get through (v1.13)
- [x] Always allow favorites (on by default; v1.13)
- [x] Allow repeat callers (second call within 15 minutes; on by default; v1.12)
- [x] Silent blocking without a notification (screening rejects before ringing)
- [ ] Separate log for blocked calls (blocked calls land in the main history
      with the blocked type; a dedicated view is still open)

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

## Code review follow-ups (review run 2026-07-25)

Round 1 shipped the same day: always-on prefix blocks, national/international
number and prefix matching, contacts fail-open in the rule evaluator, a
notice when the decline-SMS fails, direct call-UI launch when notifications
are disabled, DTMF stop-task replacement, vCard share read grant,
provider-backed block state on the contact card, redacted settings log,
role-first onboarding order, contact-name fallback for API 26–29 and the
lone-zero search guard.

Round 2 shipped 2026-07-26: all four items below.

- [x] Persist the source package of WhatsApp call notifications (Room
      migration) and route callbacks through it — WhatsApp Business installs
      currently open regular WhatsApp; the wa.me fallback also needs the
      international form for nationally formatted numbers
- [x] Rewrite the call-log row type to BLOCKED after an in-call rule
      rejection so saved-contact blocks appear under the Blocked filter
- [x] Don't resolve a WhatsApp caller's number from a display name shared by
      several contacts — an ambiguous name should store no number
- [x] Re-query the call log and contacts right after a runtime permission
      grant instead of waiting for the flow to restart

Improvement suggestions from the same review (shipped 2026-07-26):

- [x] Short-circuit call-path provider work: skip the contact lookup and the
      full merged-history read when no enabled rule needs them, and query
      only recent incoming rows for the repeat-caller window
- [x] Make set-valued settings edits atomic: add/remove blocked prefixes and
      allowed numbers inside one DataStore edit instead of replacing a set
      computed from a possibly stale UI snapshot
- [x] Give name-only WhatsApp rows a usable detail path: open details via an
      app-owned record key, hide phone actions, allow deletion (the
      name-delete repository method exists but is unused)
- [x] Make the lint gate warning-clean: fix project-owned warnings, move the
      hard-coded "eSIM" into locale resources, disable only the
      time-sensitive version checks and fail the gate on new warnings

## Technical debt / polish

- [ ] CallStyle notification for the ongoing call (hang-up button in the notification)
- [ ] InCallFinishGuard: same-frame tie at the grace boundary + combined
      "call arrives near grace expiry then ends" test
- [ ] `DeviceDefault` parents for window themes
- [x] Monochrome launcher icon (2026-07-26, with the lint cleanup)
- [ ] targetSdk bump when a newer stable SDK is available
