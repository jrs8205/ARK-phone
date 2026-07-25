# Recents search, filters and grouping — design

Date: 2026-07-25. Chosen by the user as the next backlog round.

## Scope

1. **Search** on the Recents tab: a text field filtering by contact name or
   number (digits compared normalized, so "0445" finds "+358 44 5...").
   The Home tab's recents list stays without a search field.
2. **Filter chips** under the search field: All / Missed / WhatsApp.
3. **Grouping of consecutive calls** from the same caller (same number, or
   same name for number-less WhatsApp rows, and same source): one row with
   the newest entry and a count suffix — "Matti Meikäläinen (3)". Applies
   everywhere the list is shown, including Home. Tapping behaves like the
   newest entry.

## Components

- `RecentsUiState.entries` becomes `List<GroupedCallLogEntry>`
  (`entry` = newest + `count`); filtering, search and grouping live in
  `RecentsViewModel` (`query`, `filter` state; `RecentsFilter` enum
  ALL/MISSED/WHATSAPP) as pure logic over the repository flow.
- `RecentsScreen` (tab) grows the search field and chips; `RecentsContent`
  renders grouped rows with the count suffix.

## Testing

ViewModel tests for search (name + normalized number), both filters, and
grouping (consecutive same-caller runs collapse, interleaved calls don't);
a content test for the count suffix.
