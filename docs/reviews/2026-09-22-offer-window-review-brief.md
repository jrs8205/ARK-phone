# External review brief — ARK call falls back to the carrier after a reach-query wake (2026-09-22)

You are reviewing **three fixes** in the ARK-phone repository (branch
`feature/voip-spike`, commits `47f52d2`, `aa034b9`, `3b5e144` on top of the
1.27 release `0dffff3`). They close one field defect — an outgoing ARK
(internet) call that turned into a carrier call although the callee was
reachable — and one bookkeeping defect found on the way. The root cause was
established from device records on both phones (Telecom's historical events
on the caller, the battery-stats history on the callee), so it is not in
question — **your job is to verify the fixes are correct and complete, and to
find any defect they introduce or leave open.**

## Ground rules

- **Report only. No patches.** Every finding will be verified against the
  code and fixed by the maintainer separately.
- Finding format: severity P1/P2/P3 · `file:line` · one-sentence claim ·
  concrete failure scenario · confidence. Sorted by severity. No style notes.
- The diagnosis below is device-verified fact; do not re-litigate it. Review
  the **new code paths** and everything they interact with.

## The field defect (device-verified, context)

Outgoing ARK call, caller Pixel 8a → callee Pixel 9a, 2026-09-21 16:12.

- Caller (`dumpsys telecom`, Historical Events): the self-managed ARK call was
  CREATED at 16:12:15.37, DIALING at .42, DISCONNECTED (LOCAL) at
  16:12:32.73; the carrier call to the same number was CREATED at
  16:12:32.80. That is ~2 s of reach check (answered "online") followed by
  the full 15 s `VOIP_CONNECT_TIMEOUT_MS` — the offer was sent, no
  `call-ringing` and no answer came back.
- Callee (`dumpsys batterystats --history`): the phone was in deep sleep on
  Wi-Fi. FCM wake at 16:12:16.19 (`tmpwhitelist … c2dm.intent.RECEIVE` for
  the app's uid), the app's `WakeLockHolder` released at 16:12:17.76,
  `-running` at 16:12:18.13. No ARK ring ever: the callee's Telecom history
  and call log hold only the carrier call at 16:12:33. The app process had
  been alive for six days (warm wake, no cold start).
- The next day's successful call (2026-09-22 15:54) shows the same wake
  shape with the ring (`+fg` of the app, full-screen intent) 1.4 s after the
  FCM wake — inside the window.
- Mechanism: the callee answers a reach query the moment its woken socket
  connects (the worker's reach watcher fires from the socket handshake),
  then `VoipEngine.awaitWake` held the phone for only `FLUSH_DRAIN_MS +
  WAKE_RING_MARGIN_MS` = 1 s after connect. The caller, on the reach reply,
  fetched TURN credentials over HTTP and created the offer before sending
  it — 1–2 s. The offer reached a phone that had already dropped its wake
  lock and was not processed inside the caller's window.
- Side finding from the caller's call log: every carrier fallback since
  2026-08-05 left a **0 s outgoing row with `PHONE_ACCOUNT_ID = ark-voip`
  80–140 ms before the carrier row** (e.g. rows 204/205 on 09-21). The
  coordinator's comment says a fallback makes no ARK row, and its test
  passed — because the test's fake session never ended on `hangUp()`.

## The fixes under review

### 1. `47f52d2` — no phantom ARK row on fallback, carrier dials after Telecom release

`app/src/debug/java/org/jarsi/arkphone/voip/telecom/VoipCallCoordinator.kt`

- `fallBack()` (line 384) now cancels `call.stateJob` **before**
  `call.session.hangUp()`. Reason: `WebRtcCallSession.hangUp()` sets
  `Ended("local-hangup")` synchronously; on the production scope
  (`Dispatchers.Main.immediate`, called from a dispatched task) the
  `state.collect` observer resumed inline inside `hangUp()`, saw a
  deliberate end (`shouldFallBack` false), ran `finish(recordRow = true)` —
  writing the ARK row and clearing `active` — and fallBack's own
  `finish(recordRow = false, afterTelecomReleased = …)` then found
  `active !== call` and invoked the carrier dial at once, before
  `telecom.remove` had released the ARK call.
- `armConnectTimeout()` (line 374) no longer calls `session.hangUp()`
  itself before `fallBack()` (fallBack hangs up).
- Tests (`VoipCallCoordinatorTest`): `FakeSession.hangUp()` now moves the
  state to `Ended("local-hangup")` like the real session; `FakeReach` can
  `delay()` so the reach coroutine resumes as a dispatched task; two new
  tests run under `UnconfinedTestDispatcher` and assert no row and the
  carrier dial waiting for `telecom.releaseNow()`. They failed before the
  fix (both) and pass after.

### 2. `aa034b9` — TURN credentials fetched during the reach check

- `VoipMediaSession.prepare()` added (`EngineSignaling.kt`).
- `WebRtcCallSession.prepare()` (line 77) starts `scope.async
  { turnFetcher() }` once while Idle; `fetchIceServers()` (line 82)
  consumes it in `openAdapter()` (line 178) and falls back to a fresh
  `turnFetcher()` when the prefetch returned null.
- `VoipCallCoordinator.startCall()` calls `session.prepare()` (line 164)
  right before launching the reach check.
- Tests: prefetch reused (one fetch across prepare + placeCall), a null
  prefetch refetched at placeCall, and the coordinator calling `prepare`
  before the reach answers.

### 3. `3b5e144` — the woken inbox holds until a ring lands

`app/src/debug/java/org/jarsi/arkphone/voip/VoipEngine.kt`

- New `WAKE_OFFER_WINDOW_MS = 6_000` (line 34); `WAKE_RING_MARGIN_MS` made
  public.
- `awaitWake()` (line 79): remembers `ringCount.value`, forces a fresh
  socket, `connect()`s, then `withTimeoutOrNull(WAKE_OFFER_WINDOW_MS)
  { ringCount.first { it != ringsBefore } }` and `delay(WAKE_RING_MARGIN_MS)`.
  `ringCount` (line 176) is a `MutableStateFlow<Int>` incremented in
  `ring()` after the `_incomingCalls` emit.
- The caller of `awaitWake` is unchanged: `ArkMessagingService` blocks in
  `onMessageReceived` under `withTimeoutOrNull(WAKE_HOLD_MS = 9_500)`
  (line 51).
- Tests: the wake is still held 1.1 s after connect with an empty flush,
  completes `WAKE_RING_MARGIN_MS` after an offer rings, and releases at
  window end with no offer.

Full suite + lint green (925 unit tests). Installed on all three phones
(debug on the 8a and 10 Pro, beta on the 9a); field re-test pending.

## What to verify (at minimum)

1. **Fix 1 ordering.** With `stateJob` cancelled before the hang-up, does
   anything still need the `Ended` state on the handle or the UI —
   `VoipCallHandle.onState`, `ui.changed()`, `CallController.onCallRemoved`
   (it sets `endedCall` only when `disconnectError != null`; "local-hangup"
   maps to null), the ringback collector? Any fallback entry
   (`onTelecomFailed`, reach false, connect timeout, ring timeout) that
   now leaves a job, timer or notification behind? Is the user-cancel path
   (the user hangs up during the reach check → `shouldFallBack` false via
   the observer) still distinguishable from a fallback?
2. **Fix 1 concurrency.** `fallBack` can be reached from the reach
   coroutine, the timeout job and `onTelecomFailed` (from Telecom's
   callback via `CoreTelecomRegistrar`). Is there a state where two of them
   run for the same call, and does the `stateJob` cancel change what
   `finish` does the second time?
3. **Fix 2 lifecycle.** `prepare()` uses `scope.async` on the per-call
   session scope (a plain `Job`, not a supervisor). If `turnFetcher` throws
   (`ArkAccountClient.turnCredentials` → `OkHttpArkHttp.get`), what happens
   to the scope, the session and the coordinator — is that worse than the
   pre-fix `turnFetcher()` call inside `placeCall`'s `launch`? Should the
   async be guarded like `media { }`? What if the call is cancelled or hung
   up while the prefetch is in flight (`sessionScope.cancel()` in
   `finish`)? Is a TURN credential fetched ~2 s early still valid for the
   call's duration (check the worker's TTL in `worker/` if available, else
   assume Cloudflare TURN short-lived credentials)?
4. **Fix 2 on the callee.** `prepare()` is caller-only; the callee still
   fetches TURN in `answer()`. Is there a reason to prefetch at ring time,
   and is there any risk in not doing so?
5. **Fix 3 semantics.** `awaitWake` now holds up to `connect (≤ 8 s) + 6 s
   + 0.5 s`, cut at 9.5 s by the service. Is Firebase's callback budget on
   the target devices (Android 17, `targetSdk 36`) really ≥ 9.5 s, and what
   happens at the cut — does `withTimeoutOrNull` cancelling `connect()` or
   `ringCount.first` leave the `SignalingClient` in a bad state? Battery:
   the worker rate-limits wakes (10 s per target, 6 per caller per 10 min,
   30 per hour); is a 6.5 s hold per wake acceptable, including wakes for
   calls the caller abandons?
6. **Fix 3 signal.** `ringCount` increments after `_incomingCalls.tryEmit`
   regardless of `emitted`; the coordinator's `onIncoming` may still drop
   the call (disabled, no link, blocked, mic). Should the hold end on a
   ring the coordinator drops? Can a ring that happens during `connect()`
   (before `first { }` subscribes) be missed — the flush drain waits
   `FLUSH_DRAIN_MS` after CONNECTED, but is there a path that rings
   earlier? Two wakes back to back: does the second `awaitWake`'s
   `ringsBefore` snapshot behave?
7. **Interaction with the worker.** The worker's reach watcher TTL is 8 s
   and the caller's reach timeout 7 s; the caller now sends the offer
   ~200 ms after the reply. Any case where the offer arrives at the callee's
   inbox before its socket is registered as live (superseded-socket races,
   `connectedAt` liveness) and gets buffered instead of forwarded — and is
   that then still inside the 6 s window via the flush?
8. **Test strength.** Do the unconfined-dispatcher tests in
   `VoipCallCoordinatorTest` actually reproduce the Main.immediate inline
   delivery (the reach coroutine resumes from a `delay`, i.e. a dispatched
   task)? Any existing test now passing for the wrong reason after
   `FakeSession.hangUp()` changed?

## Environment

- Android app module; `minSdk 26`, `targetSdk 36`, `compileSdk 36`,
  Kotlin, Hilt, Robolectric unit tests. The VoIP sources live in
  `app/src/debug/java/org/jarsi/arkphone/voip/` and are shared by the
  `beta` variant; the release variant has none of them.
- Files: `voip/telecom/VoipCallCoordinator.kt`, `voip/VoipEngine.kt`,
  `voip/WebRtcCallSession.kt`, `voip/EngineSignaling.kt` (interface),
  `voip/SignalingClient.kt` (socket, unchanged),
  `voip/fcm/ArkMessagingService.kt` (unchanged),
  `voip/telecom/CoreTelecomRegistrar.kt` (unchanged),
  `telecom/CallController.kt` (unchanged); tests under
  `app/src/testDebug/java/org/jarsi/arkphone/voip/`.
- The signaling worker is a separate, git-ignored project (`worker/`,
  Cloudflare Durable Objects); its reach/wake logic is unchanged.
