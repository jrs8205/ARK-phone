# External review brief №2 — everything shipped on 2026-08-09

You are reviewing one day's worth of changes to the ARK-phone repository.
This is a **report-only** defect review: verify that every fix and feature
listed below is correct and complete in the code, and hunt for regressions
they may have introduced. The previous brief
(`docs/reviews/2026-08-09-external-review-brief.md`) still applies for
format and ground rules; this brief covers the delta since.

Commit ranges under review:

- Android, branch `feature/voip-spike`: `a3791d8..fe0e57a`
  (`39b4265`, `c0ae606`, `71876b7`, `d2714dd`, `9978945`, `36d2efe`,
  `b72f942`, `489c4a1`, `2a52e63`, `c8876ef`, `ffe1a7a`, `fd7e143`,
  `d2733b7`, `78c25ee`, `1db4ef9`, `dab497e`, `fe0e57a` + docs commits)
- Worker (its own repository under `worker/`): `075b300..1fc17d1`
  (`399b9c8`, `1fc17d1`), deployed as version `477d9aec`.

## Ground rules

- **Report only. No fixes, patches, or refactors.** Every finding will be
  verified against the code before anything is changed.
- **Do not flag field-verified behavior for redesign.** The list under
  "Verified working" was proven on real hardware today, most of it with
  logs on both phones and the worker tail. Report an issue there only with
  a concrete defect and failure scenario.
- **Skip known deferrals** recorded in `docs/BACKLOG.md`: wake
  authorization (per-link wake tokens), live audio endpoint state
  (Bluetooth), ARK nickname editing, the "return to call" home-screen
  affordance, faster ICE-failure detection / ICE restart on network switch.
- Finding format (same as brief №1): severity P1/P2/P3 · `file:line` ·
  one-sentence claim · concrete failure scenario · confidence. Sorted by
  severity, no style notes.

## Verified working today — do not churn

Twelve field rounds, all ultimately green:

- Locked-phone ARK ring in four states: app swiped away, process killed,
  forced deep Doze, and the full FCM chain with notification access revoked
  (cold start → connect 0.5 s → ring 2.4 s from push → answer → TURN
  0.66 s → two-way audio).
- Mobile→Wi-Fi (the 2026-08-06 no-ring bug — no longer reproduces),
  mobile↔mobile over the TURN relay, Wi-Fi loss mid-call (clean ~10 s drop
  with the connection-lost notice), Wi-Fi gain mid-call (seamless).
- Airplane-mode fallback: ARK attempt for 7 s, then a clean carrier
  hand-over (after the `489c4a1` race fix).
- Awake-screen ring: call-screen buttons only, quiet tray entry, no
  duplicate banner (`fae5b92`); answering silences the ringtone
  (`d2714dd`); `CallControlScope.answer` returns Success in the log.
- On a second phone (release-signed beta): registration and linking via
  the in-app permission banners with no adb, wake + full-screen ring
  through bedtime-mode DND (`1db4ef9`), and a sideloaded update with **no
  launch** self-starting the engine in 3 s (`fe0e57a`,
  `stopped=false` verified from dumpsys).

## Scope 1 — the two review fix waves (54 findings)

Commits `39b4265` + `c0ae606` implement the 23 findings of review 1;
`b72f942` implements the 28 findings of review 2; `1fc17d1` (worker) closes
review 2's worker holes. Brief №1's Scope 1 section lists wave 1 in
detail. Wave 2, for re-verification:

**Worker**: per-sender/global queue caps now exempt the sender's newest
`call-offer` (a 21-candidate burst used to evict it); `notifyIfOnline`
counts only a live socket whose `send` did not throw; the frame cap
measures UTF-8 bytes when the cheap UTF-16 bound cannot prove compliance.

**SignalingClient**: socket callbacks are generation-tagged (a dying
socket's late close must not cancel the replacement's ping loop or spawn
reconnect storms); refused forwardable frames go to a bounded resend queue
flushed on the next open; `forceReconnect()` exists for the wake path.

**VoipEngine**: `awaitWake` force-reconnects instead of trusting a cached
CONNECTED state; the signal stream is a replay(64) `SharedFlow` of
seq-stamped frames, and each session filters at its call's `sinceSeq`
horizon (outgoing calls use the current seq via the factory).

**Coordinator/registrar**: `active` is installed before `addCall` can fail
inline; an answer that beats the `CallControlScope` is replayed when the
scope opens; adapter construction failures end the call as `media-error`;
a mute pressed before the adapter exists is applied at creation; carrier
fallback hangs the session up first, writes no ARK history row, and — from
`489c4a1` — dials only after `VoipTelecom.remove` reports the platform
released the call (exactly-once `onReleased`, including the
not-current-call path).

**Gates**: incoming ARK calls honor the master switch, require the mic
permission, and await the link cache (bounded 2 s) before an unlinked
verdict; outgoing calls respect a persisted OFF before DataStore's first
emission (`SettingsCache.ready`); pre-Q devices use the deprecated
emergency-number check; the missed-call notification callback dials the
carrier directly (notification-trampoline rule).

**FCM**: pre-registration tokens live in a `pendingToken`, never the
synced marker (the race left the worker tokenless); registration carries
the pending token and the post-registration refresh posts it; failed syncs
retry with backoff; `Task.isSuccessful` is checked before `getResult`.

**Housekeeping**: per-call WebRTC natives disposed (connection, track,
source); ARK HTTP on a derived OkHttp client with a 10 s `callTimeout`
(the websocket client stays unbounded); a blank worker URL is a no-op
end to end; the ARK settings row and notification channels are absent in
builds without the engine; POST_NOTIFICATIONS banner on Android 13+.

## Scope 2 — the evening's field-driven fixes (highest value)

These were written fast, under field pressure, late in the day — give them
the most suspicious read:

1. **Ring-time channel matrix** (`CallNotifications.buildIncomingCall`,
   commits `fae5b92` + `78c25ee` + `1db4ef9`): the ARK ring channel is
   chosen from (screen locked/off) × (app foreground, passed in as
   `arkBanner`) × (voice-only `silentRing`) × `quiet`. Verify every cell:
   locked rings HIGH (+FSI), awake+foreground rings the ordinary channel
   with no banner, awake+background rings HIGH so the heads-up is the
   answer surface, voice-only variants land on the soundless HIGH channel
   without losing the FSI, and the carrier branches are byte-for-byte the
   old behavior. Both ARK channels are v2 ids with `setBypassDnd(true)`;
   old ids are deleted via `REPLACED_CHANNELS`. Is anything still posting
   to or checking the old ids?
2. **ARK announce path** (`78c25ee`, `CallControllerVoipCallUi`): the ARK
   ring now runs `CallerAnnouncer.onRinging` and passes
   `silentRing = announceMode == VOICE_ONLY` from `SettingsCache.current`;
   the announcer is stopped on answer, on silence, and on `removed()`.
   Compare against the carrier contract in `ArkInCallService` — what did
   the ARK path not copy (rule-evaluated ring decisions, announce-WhatsApp
   flag, settings await timing on cold start)?
3. **Proximity gating** (`dab497e` + `fe0e57a`): the ARK audio glue
   reports `earpiece = !speakerOn`; the lock additionally requires
   `CallController.inCallUiVisible`, set from `InCallActivity`
   onResume/onPause. Scrutinize the lifecycle: does a proximity-darkened
   display pause the activity on any supported API (which would release
   the lock and flicker), what happens on rotation, on `setTurnScreenOn`
   wake-ups, and when the activity is finished by `InCallFinishGuard`
   while a call continues?
4. **ArkPackageEventReceiver** (`fe0e57a`, debug sourceset):
   MY_PACKAGE_REPLACED + BOOT_COMPLETED start `VoipStartup.onAppStart`.
   Verify Hilt injection in a manifest receiver at boot time, the
   idempotence of `onAppStart` when the app later starts normally, that
   the receiver's process has time to matter (no `goAsync`), and whether
   BOOT_COMPLETED without the identity registered does anything wasteful.
5. **Beta build type** (`fd7e143`, `app/build.gradle.kts`): the debug
   sourceset compiled into a release-signed, unminified variant
   (`java`+`kotlin` srcDirs, res, manifest overlay, `betaImplementation`
   dependency copies, `matchingFallbacks`). Look for variant drift: any
   `debugImplementation` dependency, resource, or manifest entry the beta
   does NOT inherit; BuildConfig differences; google-services processing;
   anything gated on `BuildConfig.DEBUG` that behaves differently in beta.

## Scope 3 — cross-cutting regression hunt

The same files were edited many times today. Look for interactions:
the seq-replay stream vs. the resend queue (can a resent frame replay into
a later call's session within the 64-frame window despite `sinceSeq`?);
`forceReconnect` vs. the generation guard vs. `onSendRefused` re-entry;
the fallback `onReleased` callback vs. a concurrent observe-side `finish`;
`silenceRinging`/`clearNotification` ordering vs. the shared
`NOTIFICATION_ID`; the earpiece flag vs. carrier calls in the same
process; and the wife-phone first-hour combination (fresh install →
banners → register → link → locked DND ring) as an end-to-end code path.

## Pointers

- Field evidence with timings: `docs/plans/2026-08-09-voip-phase1-field-results.md`
- Deferred items: `docs/BACKLOG.md`
- Protocol (updated for liveness, caps, budgets): `worker/docs/protocol.md`
- Test suites: `app/src/test` + `app/src/testDebug` (~800 green),
  `worker/test` (72 green)

Deliver one report, findings sorted P1 → P3, in the finding format above.
