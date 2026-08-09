# External code review brief — ARK-phone, 2026-08-09

You are reviewing the ARK-phone repository for defects. This is a
**report-only** review with three scopes, in priority order:

1. Verify the 2026-08-09 fix wave (23 previously reported defects) — are the
   fixes correct and complete, and did they introduce regressions?
2. Pre-flight the code behind the remaining "stage C" field tests, so the
   tests are run against code already believed correct.
3. A general defect pass over the whole application.

## Ground rules

- **Report only. Do not write fixes, patches, or refactors.** Every finding
  will be independently verified against the code and fixed by the
  maintainer. A finding is a claim about a defect, not a change request.
- **Do not flag verified-working behavior for redesign.** Everything under
  "Verified working" below has been proven live on hardware, with logs, on
  2026-08-09. Report an issue in those areas only if you can name a concrete
  defect with a concrete failure scenario — never because you would have
  built it differently. Architectural and stylistic preferences are out of
  scope everywhere.
- **Skip known and deliberate items.** `docs/BACKLOG.md` records open items
  and deliberate deferrals (see especially "External VoIP review follow-ups
  (2026-08-09)" — wake authorization and live audio-endpoint state are
  deferred by decision, not oversight). Phase 1 scope cuts documented in
  code comments (no hold, no DTMF for ARK calls, optimistic audio state) are
  decisions, not bugs.
- **Verify before you report.** Quote the actual lines from the repository.
  A plausible-sounding finding that misreads the code costs a full
  verification round. If you are unsure, say so explicitly.

### Finding format

For every finding:

- **Severity**: P1 (crash, lost or silently broken call, data loss,
  security) · P2 (wrong behavior the user notices) · P3 (minor, only if
  cheap to state and certain).
- **Location**: `path/file.kt:line` (or `.ts` for the worker).
- **Claim**: one sentence stating the defect.
- **Failure scenario**: concrete inputs/state → concrete wrong outcome.
- **Confidence**: certain / likely / needs-a-look.

Sort the report by severity. No style notes, no TODO padding, no
restatements of the backlog.

## Context

ARK-phone is an Android dialer that replaces the default phone app
(production code in `app/src/main`). The debug sourceset (`app/src/debug`)
adds "ARK internet calls": encrypted WebRTC audio between two ARK phones,
signaled through a Cloudflare Worker (`worker/` — its own codebase: Durable
Objects inbox/registry, FCM wake, TURN). The wire protocol is specified in
`worker/docs/protocol.md`; the current field-test state with log evidence is
in `docs/plans/2026-08-09-voip-phase1-field-results.md`.

Recent fix commits: Android `39b4265`, `c0ae606`, `d2714dd` (branch
`feature/voip-spike`); worker `399b9c8` (worker repo, deployed).

## Verified working — do not churn

Proven live on 2026-08-09 with logs on both phones and the worker tail:

- Locked-phone incoming ARK call rings via the full-screen intent — with
  the app swiped away, with the process killed, and in forced deep Doze.
- The full FCM wake chain: process dead + notification access revoked →
  reach reports offline → durable wake budget approves → FCM cold start →
  socket connect 0.5 s → ring 2.4 s from push → answer → TURN fetch 0.66 s →
  two-way audio.
- Socket liveness: the client pings every 20 s; the worker trusts only
  sockets live within 45 s, delivers best-effort + queues + wakes for stale
  ones; the phone reconciles the duplicates.
- Carrier fallback: an unreachable peer, an early session failure, or a
  connect timeout hands the call to the carrier — during round 4 a failed
  ICE setup fell back and completed over the carrier as designed.
- Foreground↔foreground ARK calls both directions; registration and contact
  linking; the "· ARK" marker in call history; answering silences the
  ringtone immediately.

## Scope 1 — verify the fix wave

The 23 previously reported defects were all fixed. Re-review each fix for
correctness, completeness, and regressions:

**Worker (`worker/src/inbox.ts`, `worker/src/fcm.ts`)**: socket liveness via
`getWebSocketAutoResponseTimestamp` + `connectedAt` (45 s window);
best-effort send into stale sockets while also queueing + waking;
registration gating of every client-supplied target code (`targetRegistered`,
positive-only cache); durable wake budget in the target inbox (`allowWake`:
10 s min interval, 6/caller/10 min, 30/target/h); offline-queue caps
(16/sender, 64 total, newest win); 32 KiB frame cap; new error codes
`unknown-peer`, `too-large`.

**Session (`app/src/debug/.../WebRtcCallSession.kt`)**: hangUp from Idle ends
the pre-check attempt without signaling; frames from third-party accounts
ignored (`from != peerId`, server errors exempt); SDP/adapter exceptions end
the call as `media-error` instead of crashing; flush-batch ICE candidates
seeded via `initialRemoteCandidates`.

**Engine & client (`VoipEngine.kt`, `SignalingClient.kt`,
`FlushReconciler.kt`)**: `reach` bounded by its own timeout end to end;
`awaitWake` blocks the FCM service through connect + drain; the 20 s ping
loop; reconciler carries post-offer candidates with the ring.

**Telecom (`VoipCallCoordinator.kt`, `CoreTelecomRegistrar.kt`,
`VoipCallHandle.kt`, `ArkCallLogWriter.kt`)**: user answer reaches
`CallControlScope.answer`; `SUPPORTS_SET_INACTIVE` no longer advertised;
async `addCall` refusal reaches the coordinator (`onFailed`) and falls back;
foreground service starts at `Connecting`; end reasons map to
`DisconnectError`; answered-but-never-connected calls log as INCOMING, not
missed; missed-call notification counts all unread missed; cancellation of
the addCall job is rethrown, not treated as refusal; answering silences the
ring.

**Routing & gating (`CallRouter.kt`, coordinator, `VoipModule.kt`)**:
emergency numbers and `*`/`#` strings go straight to the carrier; incoming
ARK calls require a local link and pass `CallRuleEvaluator`; outgoing ARK
calls require the mic permission (else carrier); ARK settings screen
requests the microphone and (API 34+) full-screen-intent permissions.

**Audio (`PeerConnectionAdapter.kt`, `StreamPeerConnectionAdapter.kt`,
coordinator, `CallControllerVoipCallUi.kt`)**: mute drives the local WebRTC
track, speaker drives Telecom endpoint change; controller attached per call,
detached on finish.

One open observation you may investigate: in field round 4 (14:20), the
callee's TURN fetch stalled after an FCM cold start — no exception, no HTTP
request on the wire — and did not reproduce on the next run. The fetch path
is `VoipModule.turnFetcher` → `ArkAccountClient.turnCredentials` →
`OkHttpArkHttp` (now log-bracketed). If you can find a concrete mechanism
for a one-off stall there, that is a valuable P2 finding.

## Scope 2 — pre-flight the remaining stage C tests

These field tests have not run yet. Review the code they will exercise and
report anything that would make them fail or, worse, pass misleadingly:

1. **Fallback matrix (peer in airplane mode)**: `VoipEngine.reach`,
   `SignalingClient.reach` (waking is non-terminal), the 7 s budget
   (`VOIP_REACH_TIMEOUT_MS`) vs the worker's 8 s watcher, coordinator's
   pre-check → `fallBack` path, `CallRouter` fallback lambda.
2. **Network switch / superseded mid-call**: `SignalingClient` reconnect
   with backoff, the `superseded` close rule, `onSendRefused`, worker's
   ghost-socket close on reconnect, and what a mid-call signaling gap does
   to an established WebRTC session (media should survive on TURN; call-end
   delivery afterwards).
3. **Mobile↔mobile**: TURN relay actually being used (`relayOnly` exists on
   the session but nothing sets it — is the default ICE config sufficient?),
   `TurnCredentialsParser`, credential TTL (7200 s) vs call setup timing.
4. **Master switch off**: `arkInternetCallsEnabled` through `SettingsCache`
   → `CallRouter`; is the switch honored for INCOMING ARK calls too, or does
   a disabled phone still ring for ARK offers?
5. **Soak**: anything that accumulates — session scopes and jobs in
   `VoipCallCoordinator`/`CoreTelecomRegistrar`, the engine's collector
   coroutines, notification and foreground-service lifecycle over many
   calls, worker-side sweep/alarm re-arming, `wake_events` growth, the
   registered-codes cache, reconnect storms on flaky Wi-Fi.

## Scope 3 — whole-app pass

A general defect review of the production dialer (`app/src/main`): Telecom
integration (`ArkInCallService`, screening, ringing, announcements, SIM
handling), call history and recents, contacts and linking UI, blocking
rules (`CallRuleEvaluator`, `BlockingRules`), SMS/MMS/messaging, settings
and DataStore migrations, notifications and channels, WhatsApp call logging,
runtime permissions and role handling, process-death and configuration
changes, Room migrations, and the Finnish/English string pairs. Prioritize
anything that can lose a call, a message, or user data.

## Repository pointers

- Android VoIP: `app/src/debug/java/org/jarsi/arkphone/voip/`
- VoIP Telecom glue: `app/src/debug/java/org/jarsi/arkphone/voip/telecom/`
- Production dialer: `app/src/main/java/org/jarsi/arkphone/`
- Worker: `worker/src/` (tests in `worker/test/`)
- Protocol: `worker/docs/protocol.md`
- Field evidence: `docs/plans/2026-08-09-voip-phase1-field-results.md`
- Known/deferred items: `docs/BACKLOG.md`
- Android tests: `app/src/test/`, `app/src/testDebug/` (~780 green);
  worker: 70 green

Deliver one report, findings sorted P1 → P3, in the finding format above.
