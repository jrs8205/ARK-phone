# External review brief №4 — the review-3 fix wave (2026-08-10)

You are reviewing the wave that closed review №3: all 13 findings of
`2026-08-09-evening-review-findings.md` were verified against the code
(13/13 real), then fixed test-first, one commit per finding. This is a
**report-only** defect review of those fixes: verify each one is correct
and complete, and hunt for regressions the wave introduced. Brief №2
(`docs/reviews/2026-08-09-evening-review-brief.md`) still applies for
format and ground rules; this brief covers the delta since.

Commit ranges under review:

- Android, branch `feature/voip-spike`: `ea39ad8..628b570`
  (fixes `823b03b`, `8f5a282`, `403aad7`, `7564241`, `72cc9b8`,
  `b4d3896`, `1ec6c94`, `071b923`, `59c2f61`, `6f9f53c`, plus the
  developer-verification asset `628b570` and two docs commits).
- Worker (its own repository under `worker/`): `1fc17d1..4f945a2`
  (`4f945a2` only), deployed as version `a2e50429`.

## Ground rules

- **Report only. No fixes, patches, or refactors.** Every finding will be
  verified against the code before anything is changed.
- **Skip the four known issues** listed under "Known issues" below — they
  are already on the ledger. Re-report one only if you can show it is
  worse than P3 or that this wave widened it.
- **Skip known deferrals** recorded in `docs/BACKLOG.md` (wake
  authorization, Bluetooth endpoint state, nickname editing, return-to-call
  affordance, faster ICE restart).
- **Do not flag field-verified behavior for redesign** (list below) without
  a concrete defect and failure scenario.
- Finding format (same as briefs №1–2): severity P1/P2/P3 · `file:line` ·
  one-sentence claim · concrete failure scenario · confidence. Sorted by
  severity, no style notes.

## Field status as of this brief

- Fix №9 (proximity): **field-verified** — on an ARK call both test
  phones now turn the screen off at the ear; carrier calls unchanged.
- Fix №10 (package-event receiver): after a sideloaded install the
  receiver started the engine and the inbox reconnected, verified in
  logcat on the 10 Pro.
- Fixes №11 (speaker before Telecom ready) and №8 (master switch off)
  are code-complete with the suites green; their field passes were still
  pending when this brief was written.

## Scope 1 — the 13 fixes, one by one

Re-verify each against its finding; the danger is a fix that closes the
reported path while opening a neighbor.

1. **№1 · `8f5a282` — emergency classification fails toward the carrier**
   (`AppModule`, new `CarrierBiasedEmergencyNumbers`): a TelephonyManager
   that is missing or throws on Q+ now classifies the number as emergency,
   forcing the carrier path. Verify the bias triggers only on failure —
   ordinary numbers must not start routing to the carrier — and that the
   pre-Q deprecated check is untouched.
2. **№2 · `403aad7` — inline Telecom refusal on outgoing setup**
   (`VoipCallCoordinator.startCall`): after `addToTelecom` returns true,
   `active !== call` now detects that an inline `addCall` failure (before
   its first suspension on Main.immediate) already finished the call and
   started carrier fallback, and skips opening surfaces for the dead
   handle. Verify the fallback dials exactly once on this path and that
   no legitimate setup can trip the guard.
3. **№3 · `7564241` — refusals during resend flushing requeue**
   (`SignalingClient.flushResendQueue`): when the replacement socket also
   refuses, the unsent tail goes back at the **front** of the queue
   (ahead of frames stashed meanwhile) and the socket is dropped like any
   refused send. Verify ordering, the `MAX_RESEND` trim (it drops from the
   front — can it eat the very frames just requeued?), and that the
   flush → refuse → reconnect → flush loop cannot spin.
4. **№4 · `72cc9b8` — signaling frames scoped to their call**: outgoing
   frames carry a stamped `callId`; `reconcileFlush` extracts the offer's
   stamp; the session filters received frames by it. Unstamped frames
   from an older build pass the filter **by design** (mixed-version
   safety while the second phone still runs the previous beta) — that is
   deliberate, not a finding. Verify the stamped path actually closes
   review №2's cross-call replay for same-version peers.
5. **№5+№6 · `b4d3896` — FCM token rotation retries, superseded retries
   cancel** (`ArkFcmRegistration`): `onNewToken` now runs the retrying
   sync path, and a newer token cancels the sleeping retry of the one it
   replaces. Verify a cancelled retry can no longer overwrite a
   successfully-posted newer token, and that rotation storms cannot stack
   retry loops.
6. **№7+№12 · worker `4f945a2` — global offer bound, stale sockets
   starved**: queued offers now count toward a type-blind global roof
   (newest rows win), and only live sockets get live sends, so a frame
   delivered to a stale-but-working socket can no longer re-ring from its
   queued duplicate after a reconnect. Verify the interplay with the
   per-sender newest-offer protection (can pressure evict a protected
   offer, and is that acceptable?) and that reach replies still traverse
   a socket the watcher considers stale.
7. **№8 · `1ec6c94` — incoming calls await persisted settings**
   (`VoipCallCoordinator.onIncoming` via `arkCallsEnabled`): the master
   switch is read only after settings have actually loaded, not from the
   synchronous default. Verify the cold-FCM-start ordering against
   `awaitLinkCache` and the bounded wait — a hung DataStore must not
   strand the offer past the caller's 15 s connect timeout.
8. **№9 · `823b03b` — proximity screen-off exists on the self-managed
   path** (`ArkVoipStartup` now requires `ProximityController`): the
   singleton is created with the VoIP startup, which arms the
   `combine(calls, audio, inCallUiVisible)` collector. Field-verified.
   Verify the carrier path (`ArkInCallService`) still constructs it in
   builds without the engine, and that nothing else depends on the
   controller being created lazily.
9. **№10 · `071b923` — package-event receiver holds its process**
   (`ArkPackageEventReceiver`): `goAsync` plus an 8 s grace before
   `pendingResult.finish()`. Verify the 8 s fits the broadcast budget on
   all supported APIs, that `finish()` cannot run twice or never, and
   that a BOOT_COMPLETED storm cannot pile up held broadcasts.
10. **№11 · `59c2f61` — speaker choice replay**
    (`CoreTelecomRegistrar.pendingSpeaker`): a route tap that arrives
    before the `CallControlScope` or the wanted endpoint exists is
    stashed and replayed when the scope opens or the endpoint appears.
    Verify the replay applies at most once per stash and interacts sanely
    with a user who toggles during setup. (The stale-replay-across-calls
    hole is known issue 3 — skip it.)
11. **№13 · `6f9f53c` — legacy `voip_calls` channel retired everywhere**:
    the id joined `REPLACED_CHANNELS` in the main sourceset and the
    debug-only deletion left `VoipForegroundService`. Verify a release
    installed over a release-signed beta actually deletes the channel,
    and that nothing still posts to the old id.
12. **`628b570` — developer-verification asset**: embeds
    `app/src/main/assets/adi-registration.properties`. Confirm the asset
    contains nothing beyond the registration token and changes no runtime
    behavior.

## Known issues — already on the ledger, do not re-report

Four P3s found in an internal pass over this same wave, all confirmed in
code and queued for the next fix evening:

1. `FlushReconciler.reconcileFlush` attributes trailing ICE candidates to
   the newest offer by sender only — it never checks the candidates' own
   `callId` stamp, so a resend flush that queues attempt A's late
   candidates after attempt B's offer seeds B's session with A's
   candidates.
2. `SignalingClient.stop()` does not clear `resendQueue`, so the first
   `onOpen` after a later `start()` flushes stale frames from the
   previous engine run.
3. `CoreTelecomRegistrar`'s `pendingAnswer`/`pendingSpeaker` are singleton
   fields: `add()` does not reset them and the not-current-call `remove()`
   path does not clear them, so a stash can survive into the next call
   and replay there.
4. `VoipCallCoordinator.ring()` has no post-`addToTelecom` recheck: an
   inline Telecom refusal (which hangs the session up asynchronously)
   still lets the incoming path run `ui.added` / `showIncoming` /
   `openCallScreen`, blipping a ring and announcement for a call that is
   already dead.

## Scope 2 — cross-cutting regression hunt

The wave touched the same seams as the last two. Look for interactions:

- The `callId` stamp vs. the replay(64) stream vs. the resend queue: with
  known issues 1–2 open, is there a frame path where the stamp is checked
  in one place but trusted in another?
- The worker's type-blind global roof vs. the per-sender newest-offer
  exemption added in `1fc17d1`: two caps now prune the same table — can
  their interleaving delete an offer the first cap just protected?
- Starving stale sockets vs. `notifyIfOnline`'s live-send accounting and
  the reach watcher: a phone whose socket went stale mid-query must still
  end up woken, not silently unreachable.
- The receiver's 8 s grace vs. the FCM wake budget (7 s reach timeout,
  8 s worker watcher): does holding the process change any timing the
  field tests calibrated?
- `arkCallsEnabled` vs. `awaitLinkCache` vs. the mic-permission check:
  the checks reordered — can any combination now drop a call the old
  order accepted (or accept one it refused)?
- The proximity singleton now exists in every engine build: any effect on
  carrier calls in the same process, wake-lock lifecycle at startup, or
  battery when no call ever happens?

## Pointers

- Review №3 findings (all fixed here):
  `docs/reviews/2026-08-09-evening-review-findings.md`
- Field evidence: `docs/plans/2026-08-09-voip-phase1-field-results.md`
- Deferred items: `docs/BACKLOG.md`
- Protocol: `worker/docs/protocol.md`
- Test suites: `app/src/test` + `app/src/testDebug` (full suite + lint
  green at `628b570`), `worker/test` (73 green at `4f945a2`)

Deliver one report, findings sorted P1 → P3, in the finding format above.
