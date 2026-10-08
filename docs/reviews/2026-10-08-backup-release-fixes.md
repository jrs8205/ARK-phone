# Restore release fixes — review round 5 (2026-10-08)

Starting point: `feature/voip-spike` at `f4ce4cb`. The reviewer applied the
fixes for the three round-4 P2 findings in the working tree together with
the journal's error handling; they are committed as `2cca4fe` after being
verified here (1 017 tests, lint clean). The minified release build's real
ARK call is still to be verified on devices before a release.

## Fixes

- `TableWriteLock` finishes a pending restore before every repository
  write; a failed recovery refuses the write. Recovery works before the
  startup task has run; the dependency on `BackupStore` is resolved lazily
  when the lock is first used. The restore, the startup recovery and every
  other writer share one mutex.
- `BackupStore.snapshot()` holds the same lock while reading the
  preferences and both tables, and finishes a pending restore first. A
  failed recovery never produces a backup reported as saved.
- `ArkLinkCache.refresh()` cancels the old collector and waits for it to
  end before starting the new one, then waits for the new subscription's
  first result. Concurrent refreshes are serialised, and the collector
  switch is completed even when the caller is cancelled. A query failure
  clears the stale links and reaches the caller.
- `RestoreJournal` reports read and delete failures. An unreadable journal
  is no longer treated as absent, and a failed delete does not let new
  writes land beside a journal that could later wipe them.

## Verification

Sixteen regression tests were added: a call recorded after both COMMIT
attempts, a link written before the startup task, writes refused by a
failed recovery, snapshot recovery and locking, a delayed link result,
concurrent refreshes, a cancelled collector switch, and journal read and
delete failures.

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest` | 1 017 tests, 132 classes; 0 failures, 0 errors, 0 skipped |
| `:app:lintDebug` | 0 errors, 0 warnings, 1 pre-existing hint |
| `:app:assembleRelease` | passed; minification and resource shrinking on |
| `:app:assembleBeta` | passed |
| `apksigner verify --verbose` | both APKs carry a valid v2 signature |
| `git diff --check` | passed |

One combined run failed in `packageBeta` without a specific cause; a
separate run of the unchanged beta build with `--stacktrace` passed, as did
the release build and lint. The original cause of the packaging failure was
not established.

## Build artefacts

Release candidate: `app/build/outputs/apk/release/ARK-phone-1.28-release.apk`
(versionCode 16, 46 043 781 bytes), SHA-256
`ba74bbc6029511225cfe8c6ba00d7c96c28609ce12ce3db0d2b7c6cd5ddef852`.

Comparison build: `app/build/outputs/apk/beta/ARK-phone-1.28-beta.apk`
(59 106 447 bytes), SHA-256
`6117bb65e8f4b1992982ef7d74d589bd20b76e4b760fe57d09978591504e0a6d`.

## Open release check

A real ARK call with the minified release APK identified above, on two
test devices, has not been made. Verify a call in both directions,
two-way audio, ending the call, and an incoming call with the app in the
background. The update must keep the app data and the current signature.
Local tests and the signature check do not prove these device paths.

## Follow-up from the maintainer's review of these fixes

With a pending journal that cannot be finished, every table write now
throws (`IOException` from an unreadable journal, `SQLiteException` from a
failing replay). `WhatsAppCallMonitor` records on the application scope
and `ContactCardViewModel` links on the view-model scope, neither of which
caught those: the process would have died at the end of every WhatsApp
call and on every link attempt for as long as the journal stayed pending.
Both now report instead — the monitor logs and skips the row, the contact
card returns to the code entry with "Could not save the link. Try again."
(new string in eleven languages) and a failed unlink keeps the link.
