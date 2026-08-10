# External review №4 findings — received 2026-08-10 19:36, all verified

The report answering `2026-08-10-review-brief.md`: 5 findings (3 P1, 2 P2).
Every claim was verified against the code before fixing — **5/5 real**, the
fourth consecutive round at 100%. The four internal P3s from the same-day
self-review ride in the same fix wave, one commit per finding.

Reviewer's summary: the changed resend, call-scoping, FCM-rotation, and
stale-socket paths retain concrete races that can lose signaling, leave
wake registration stale, or force a false carrier fallback; the suites
pass but do not exercise these scenarios.

## P1

1. **Preserve requeued frames when enforcing MAX_RESEND** —
   `app/src/debug/.../SignalingClient.kt:182`
   When the replacement socket refuses while the queue held 32 frames and
   even one newer frame was stashed during the flush, `addAll(0, ...)`
   puts the unsent tail first but `removeFirst()` immediately evicts that
   same tail: an offer, answer, or end is discarded while newer ICE
   candidates survive. VERIFIED: the trim always eats the front, which is
   exactly the requeued tail.

2. **Match flush cancellation frames by call ID** —
   `app/src/debug/.../FlushReconciler.kt:46`
   In a drain ordered as call B's stamped offer followed by call A's stale
   stamped call-end/call-reject from the same peer, the cancellation
   branch runs `live.remove(from)` without comparing IDs, so B is dropped
   before a session exists and the session-level filter never runs.
   VERIFIED: distinct from internal P3 1 (ICE attribution); the end/reject
   branch is ID-blind.

3. **Wait for the cancelled token sync before starting its successor** —
   `app/src/debug/.../fcm/ArkFcmRegistration.kt:56`
   `syncJob?.cancel()` neither interrupts a blocking `Call.execute()`
   (`ArkHttp.kt:41`, `Dispatchers.IO`, no `runInterruptible`) nor waits
   for it, so the successor posts concurrently; if the dead token's
   request reaches the worker after the new token's, the worker holds the
   dead token while the local synced marker says otherwise — future
   refreshes skip the live token and push wake stays broken. VERIFIED:
   the marker is written only by the successful job, so B-then-A arrival
   at the worker leaves marker=B, worker=A.

## P2

4. **Preserve reconnect backoff after a flush refusal** —
   `app/src/debug/.../SignalingClient.kt:162`
   `onOpen()` resets the delay to 1 s before `flushResendQueue()`; a
   socket that accepts the handshake but refuses its first queued frame
   reconnects at a fixed 1 s forever — network and battery churn instead
   of backoff. VERIFIED: reset precedes the flush unconditionally.

5. **Deliver reach replies through stale-but-working sockets** —
   `worker/src/inbox.ts:213`
   A caller whose socket can still send but whose liveness timestamp
   expired installs a watcher and wakes the target; the target's
   reach-reply routes back through `notifyIfOnline`, whose stale-skip
   drops it, and the watcher is already deleted — the caller times out
   and falls back despite the peer being online. VERIFIED, and the root
   is wider than the report: `isLive` sees only `connectedAt` and the
   ping auto-response timestamp, so the same stale caller would also
   miss the **call-answer** (deliver is live-only). Fixed at the root:
   any inbound frame stamps the socket seen-now, making it live for
   every delivery that follows.

## Internal P3s fixed in the same wave

6. `reconcileFlush` attributes trailing ICE candidates to the newest
   offer by sender only, ignoring the candidates' own callId stamp.
7. `SignalingClient.stop()` does not clear `resendQueue`; the next run's
   first `onOpen` flushes stale frames from the previous engine run.
8. `CoreTelecomRegistrar.add()` does not reset
   `pendingAnswer`/`pendingSpeaker`/`endpoints`, so a stash from a
   previous call can replay into the next one.
9. `VoipCallCoordinator.ring()` opens the ring surfaces even when an
   inline Telecom refusal already killed the call — a ring/announce blip
   for a dead call.
