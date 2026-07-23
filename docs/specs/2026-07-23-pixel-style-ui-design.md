# Pixel-style UI and Lock-Screen Call Actions — Design

## Goal

Bring the main navigation and incoming-call experience in line with the Google
Phone app on Pixel devices, and make the dial pad usable in bright light with
WCAG AAA contrast.

## Requirements (from on-device testing feedback)

1. **Lock screen:** incoming calls must show Answer and Decline buttons without
   extra taps. The current notification has the actions but a collapsed
   heads-up hides them; the full-screen intent alone is not enough.
2. **Dial pad:** the call button must sit above the system navigation bar, not
   under it.
3. **Navigation:** a persistent bottom bar with Home (home icon), Keypad
   (dialpad icon) and Contacts, visible on every tab, like the Pixel Phone app.
   The dial pad becomes a tab; the floating dial pad button is removed.
4. **Home tab:** favorites at the top (round photos) with recent calls below.
5. **Contact photos:** round everywhere (currently square).
6. **Dial pad number field:** visually distinct input area in both light and
   dark themes; text contrast above WCAG AAA (≥ 7:1 normal text, ≥ 4.5:1 large
   text, ≥ 3:1 non-text) so it is legible in sunlight.

## Design

### Incoming-call notification (telecom)

`CallNotifications.showIncomingCall` switches to
`NotificationCompat.CallStyle.forIncomingCall(person, decline, answer)`, which
renders always-visible Answer/Decline buttons in the heads-up and on the lock
screen (framework-styled on API 31+, plain actions below). The existing
full-screen intent and INSISTENT ringing stay. The answer action becomes a
direct `PendingIntent.getActivity` into `InCallActivity` carrying
`ACTION_ANSWER` + call id, so answering from the notification also opens the
in-call screen (no broadcast trampoline); `InCallActivity` answers via an
injected `CallController` in `onCreate`/`onNewIntent`. Decline stays a
broadcast to `CallActionReceiver`, which now only handles decline. The
notification is built by an `internal` builder function so a Robolectric test
can assert the style, actions and full-screen intent.

### Navigation (ui/navigation, MainActivity)

`MainTab` becomes `HOME, KEYPAD, CONTACTS`. `MainScreen` shows a
`NavigationBar` with all three on every tab and hosts `DialpadScreen` as the
KEYPAD tab inside the `Scaffold` (content padding keeps it above the
navigation bar, fixing requirement 2). The FAB and the separate full-screen
dial pad mode are removed; `MainActivity` routes `tel:` intents to the KEYPAD
tab with the number prefilled. Back on a non-HOME tab returns to HOME.

### Home tab (ui/home)

New `HomeScreen`: favorites (starred contacts from `ContactsViewModel`) as a
horizontal row of round avatars with names, then a recents header and the
existing `RecentsContent`. No new ViewModels or repository work. A stateless
`HomeContent` keeps it testable without Hilt.

### Avatars (ui/components)

`ContactAvatar` moves from `ContactsScreen` to a shared component with a size
parameter; photos are clipped to `CircleShape` (monogram fallback already
round). Used by the contacts list (40 dp) and home favorites (56 dp).

### Dial pad field and contrast (ui/dialpad, ui/theme)

The number row gets a rounded `Surface` (`surfaceContainerHigh`, outline
border) so the input area is visible on both themes; the number is centered
`onSurface` text. Dial pad keys use `surfaceContainerHigh` with `onSurface`
digits and `onSurfaceVariant` letters. A new `ThemeContrastTest` computes WCAG
contrast ratios for the static dark scheme's used pairs and enforces AAA
(≥ 7:1 text, ≥ 4.5:1 large text/containers, ≥ 3:1 outline); the custom scheme
values are adjusted until the test passes. Dynamic (Material You) schemes
cannot be checked at build time, but the chosen role pairs keep a tone
distance ≥ 60, which yields ≥ ~7:1 by construction.

## Out of scope

Ongoing-call notification style, voicemail tab, contacts editing, call
screening. Interfaces of `CallController`, repositories and ViewModels are
unchanged.
