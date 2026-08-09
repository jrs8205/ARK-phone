# External review №2 findings — received 2026-08-09 21:22, UNVERIFIED

The report answering `2026-08-09-evening-review-brief.md`. **None of these
have been verified against the code yet.** The next session verifies each
claim first (same process as the two earlier waves — both of those turned
out 100% real, so expectations are high), then fixes the confirmed ones.

Reviewer's summary: tests, lint, assemblies and worker tests pass, but
untested race/protocol/lifecycle paths can misroute emergency calls, lose
signaling or FCM wake capability, and exceed the worker queue bound; plus
defects reproducing the field proximity failure and visible startup,
audio-routing, duplicate-ring and channel-cleanup problems.

## P1

1. **Fail emergency classification toward the carrier** —
   `app/src/main/java/org/jarsi/arkphone/di/AppModule.kt:321`
   When TelephonyManager is unavailable or throws on Q+, the check returns
   false, so a linked emergency number (112) can enter the ARK path instead
   of Telecom; the call may be delayed by fallback or even connect to the
   linked ARK peer.

2. **Stop setup after an inline Telecom failure** —
   `app/src/debug/.../telecom/CoreTelecomRegistrar.kt:103-106`
   On Main.immediate, addCall can fail before its first suspension →
   onFailed finishes the outgoing call and starts carrier fallback inline,
   yet add() still returns true. The coordinator then adds/opens the
   already-finished handle; its later Ended observation cannot remove it
   (active already cleared) → zombie ARK call beside the carrier call.

3. **Requeue frames refused during resend flushing** —
   `app/src/debug/.../SignalingClient.kt:175`
   If the replacement socket also refuses sends during onOpen, the resend
   queue was already cleared and each false return is ignored → a queued
   answer/end frame is permanently lost.

4. **Scope resent frames to their original call** —
   `app/src/debug/.../SignalingClient.kt:124`
   Call A's stashed call-end can be resent after the same peer starts
   call B; no call id/generation/expiry on the frame, session filters only
   by peer → call B dies as peer-hangup.

5. **Apply retry handling to token rotations** —
   `app/src/debug/.../fcm/ArkFcmRegistration.kt:42`
   onNewToken still calls tokenSync.sync once (no retry); a transient POST
   failure leaves the worker with the dead old token until another process
   start.

6. **Cancel retries for superseded FCM tokens** —
   `app/src/debug/.../fcm/ArkFcmRegistration.kt:52-54`
   A sleeping retry for token A can overwrite a successfully-posted newer
   token B (sync only skips exact marker equality) → wake pushes fail
   until another refresh.

7. **Enforce the global cap across queued offers** —
   `worker/src/inbox.ts:244-246`
   The global deletion exempts EVERY call-offer (not just each sender's
   newest protected one): five senders × 16 offers = 80 rows inside the
   30 s window; more senders grow it further → storage / oversized-flush
   DoS.

## P2

8. **Await persisted settings before accepting incoming ARK calls** —
   `app/src/debug/.../telecom/VoipCallCoordinator.kt:167`
   Cold FCM start: link cache can be ready before SettingsCache's first
   emission → master switch reads default true; the rule evaluation later
   loads settings but never rechecks the switch.

9. **Instantiate proximity tracking for self-managed calls** —
   `app/src/debug/.../telecom/CallControllerVoipCallUi.kt:95`
   The only production injection site for ProximityController is
   ArkInCallService, which never runs for the self-managed path → no
   collector ever calls acquire() → **matches the live field defect:
   screen stays on at the ear during ARK calls.**

10. **Keep package-event startup alive past onReceive** —
    `app/src/debug/.../ArkPackageEventReceiver.kt:29`
    onAppStart may have already run (idempotent return) while the real
    work sits in detached app-scope coroutines; without goAsync the
    receiver process can be killed before the reconnect completes (boot /
    memory pressure).

11. **Replay speaker requests when Telecom becomes ready** —
    `app/src/debug/.../telecom/CoreTelecomRegistrar.kt:134-141`
    A speaker tap before CallControlScope/endpoints exist is dropped; UI
    optimistically shows speaker while audio stays on the earpiece, and
    the later endpoint emission never applies it.

12. **Prevent stale-socket duplicates from ringing again** —
    `worker/src/inbox.ts:212`
    A stale-but-working socket receives the offer live AND the offer is
    queued; if the call then ends (end delivered live only) and the phone
    reconnects within 30 s, the orphaned offer flushes and rings the same
    call again.

## P3

13. **Delete the legacy VoIP channel in release builds** —
    `app/src/debug/.../VoipForegroundService.kt:30`
    A release installed over a release-signed beta keeps the old
    `voip_calls` channel forever: the deleting service is debug/beta-only
    and the main REPLACED_CHANNELS list omits the id.
