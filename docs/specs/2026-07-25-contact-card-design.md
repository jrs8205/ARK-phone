# Contact card (read-only) — design

Date: 2026-07-25. Approved by the user in the design discussion.

## Problem

The contacts tab only lists names and calls on tap; everything else stored on
a contact (other numbers, emails, addresses, birthdays, organization, notes)
is invisible in ARK-phone. The user wants all of it viewable in the app.

## Direction (agreed)

The system ContactsProvider stays the single source of truth — the app never
keeps its own contact store. This round is read-only; editing comes later and
will write to the provider on the user's Google account so changes sync to
Google exactly like edits made in the Google Contacts app.

## Opening the card

The contact list row tap opens the card; calling moves to a trailing phone
icon button on the row — the same pattern Recents uses (row opens details,
icon acts). The Home tab's favorites row stays a one-tap speed dial.

## The card — ContactCardActivity

Own activity (like CallDetailActivity), launched with the contact id.
Scrollable content:

- Large round avatar, display name, favorite star when starred.
- Call and Message buttons (first phone number).
- All stored fields, grouped, with the system's localized type labels
  (Mobile/Home/Work…):
  - Phone numbers — tap calls; every number is shown even though the list
    model still holds one.
  - Emails — tap opens the mail app.
  - Postal addresses — tap opens the map.
  - Events — birthday and anniversary with their own labels.
  - Organization and title, notes, websites (tap opens the browser).
- A "Call history" row that opens the existing call detail view.

## Data

- New model `ContactDetails` with `LabeledField` lists per group.
- `ContactsRepository.contactDetails(contactId)` reads the contact row
  (name, photo, starred) plus all `ContactsContract.Data` rows for the
  contact in one query, off the main thread.
- A pure mapper assembles `ContactDetails` from data rows; type labels come
  through an injected resolver so the mapper is unit-testable, with the
  production resolver backed by `Phone.getTypeLabel`/`Email.getTypeLabel`/
  postal equivalents and string resources for event types. Unknown mimetypes
  and blank values are skipped.

## Testing

Mapper as pure unit tests; the card screen with state + callback Compose
tests (fields rendered, tap callbacks fire); a contacts list test for the
new row behavior (tap opens card, icon calls).

## Polish round (same day, per the user's Pixel Contacts screenshot)

- Sections grouped into rounded surface cards so groups read apart.
- Trailing action icons: a message icon on phone rows, a directions icon on
  address rows.
- Connected apps section: third-party contact actions (WhatsApp message /
  voice / video, Signal, …) read generically from the provider's
  `vnd.android.cursor.item/vnd.*` data rows and launched via their data row
  id + mimetype — the mimetype routes to the owning app, so every such app
  works without app-specific code.
- Share contact as a vCard through the system share sheet (lookup key).
- Block/unblock all of the contact's numbers from the card (error-colored
  row, same BlockedNumbersRepository as the call detail view).
- Contacts list: an "All contacts" header separates favorites from the rest.
- Skipped on purpose: weather widget, ringtone/reminders/security rows,
  VIP/ICE chips, recent-messages preview.
