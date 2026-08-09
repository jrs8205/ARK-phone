# VoIP Phase 1 field results — 2026-08-09

Setup: worker version `1502c6e1` (zombie-socket liveness + abuse hardening,
commit `399b9c8`), app debug build of `39b4265` on both phones (Pixel 8a =
caller `ARK-S3CU-DBNH`, Pixel 10 Pro = callee `ARK-E5HU-JVA8`), both on the
home Wi-Fi. Logs captured from `wrangler tail`, and `adb logcat` on both
phones simultaneously.

## Round 1 — locked screen, app swiped away (13:59)

**PASS for the ring, but did not exercise FCM.** The 10 Pro's process had
survived the swipe (same pid since install), so its socket was live and
pinging; the worker's reach check answered `online=true` immediately and no
wake was sent — correct behavior under the new liveness rules. The offer and
21 trickled candidates were delivered into the live socket, and the
full-screen intent opened InCallActivity **on the locked screen**
(ActivityManager shows the FGS starting with `uidState: TOP` via the
notification's InCallActivity pending intent). Answered ~6 s in, audio both
ways, clean teardown.

This is the first live proof that the full-screen-intent path works on a
locked phone — the 2026-08-08 blocker (denied `USE_FULL_SCREEN_INTENT`
appop) is resolved.

## Round 2 — locked screen, process verifiably killed (14:03)

**PASS for the ring; FCM again not needed, for a new and important reason.**
The process was killed with `am stop-app` (verified dead at 14:01:38). Ten
seconds later ActivityManager restarted it on its own:

    14:01:48 Start proc 22696 … for bound-service WhatsAppCallListenerService

The app's WhatsApp notification listener makes Android rebind — and
therefore resurrect — the process shortly after any kill. The inbox socket
reconnected at 14:01:49, so the 14:03 call again found a live socket, was
delivered directly, and rang on the locked screen exactly as in round 1.

## Round 3 — locked screen, forced deep Doze (14:12)

**PASS — and the socket survived Doze.** The 10 Pro was forced into deep
idle (`dumpsys battery unplug` + `deviceidle force-idle`, re-forced at
14:09:36, state verified IDLE) and the call was placed ~3 min later, well
past the 45 s liveness window. The phone rang on the locked screen — but
again through the live socket: no wake was sent, no reconnect happened
(`inbox connect=true` appears only once, at 14:01:49), and the offer landed
on the same process at 14:12:33. The system-bound notification listener
exempts the process from Doze's network restrictions, so its keepalive
pings never stopped and the worker correctly kept treating it as online.

## What this means

- **The practical goal is met three-for-three**: a locked phone — swiped
  away, freshly killed, or in forced deep Doze — rings for an ARK call in
  under a second, through the same call screen as a carrier call.
- **The notification listener makes the app unusually resilient** on these
  phones: Android resurrects the process ~10 s after any kill to rebind the
  listener, and the binding exempts the process from Doze's network
  restrictions, so the inbox socket effectively never dies. The FCM wake is
  a safety net for what remains: memory-pressure kills without a prompt
  rebind, network loss that outlives the socket, aggressive OEM battery
  managers on other devices, and deep app-standby after days of disuse.
- The FCM chain's pieces are each proven live (FCM delivery + cold start on
  2026-08-08 at 22:00:26; watcher notify on 08-08; wake budget by tests),
  but the full chain in one run remains unexercised — its trigger
  conditions are genuinely hard to produce on these devices. To force it:
  temporarily disable the app's notification access, `am stop-app`, call.

## Round 4 — forced FCM chain (14:20): chain PASS, media FAIL, fallback PASS

Notification access revoked (`cmd notification disallow_listener`) so nothing
could resurrect the process; `am stop-app`; verified dead 20 s. The call
exercised the full wake chain for the first time: reach `online=false` →
durable budget approved (`allowWake`) → FCM sent 14:20:52 → **cold start**
(`Start proc … FirebaseInstanceIdReceiver` 14:20:53) → connect 0.7 s → flush
→ ringing at ~2.5 s from the push, watcher flipped the caller's reach inside
3 s. The user answered — then two defects surfaced: the insistent ringtone
kept playing over the answered call (the notification was only replaced on
InCall), and the callee's TURN fetch stalled with no trace, so no answer was
ever signaled; the caller's 15 s timeout fired and the call **fell back to
the carrier and completed over Moi** — the fallback covenant held.

Fixed in `d2714dd`: answering silences the ring through the carrier path's
quiet re-post, and the TURN fetch is bracketed with logs.

## Round 5 — forced FCM chain again (14:31): full PASS

Same setup, new build. Cold start 14:31:27, push → ring 2.4 s, wake-hold
covered the window, answer at +4.8 s, TURN fetch 0.66 s (`identity=true`,
`servers=2`), answer signaled, media connected, **audio both ways**, "ARK"
shown as the caller on both phones, ringtone silenced on answer. The round 4
TURN stall did not reproduce; if it ever does, the new logs will show which
side of the fetch died.

## Round 6 — second-fix-wave sanity + mobile→Wi-Fi (16:49)

On the build with all 28 second-review fixes (app `b72f942`, worker
`477d9aec`): a foreground sanity call passed, and a **mobile→Wi-Fi call
passed** — the 8a with Wi-Fi disabled entirely (mobile data only) calling
the 10 Pro rang and completed correctly.

Mobile→Wi-Fi was the original unresolved field failure from 2026-08-06
("connects, shows peer, but NO RING"). The prime suspect then was an offer
swallowed by a stale server-side socket; the liveness rework (zombie
sockets no longer count as online; stale delivery queues + wakes), the
refused-frame resend, and the socket-generation guard all sit on exactly
that path, and the scenario now passes.

## Round 7 — airplane-mode fallback (16:56 fail → fix → 17:05 pass)

First attempt FAILED usefully: the reach timed out at 7 s as designed, but
the carrier call was rejected — "another call is being dialed". The
fallback had raced Telecom's asynchronous disconnect of the ARK call (the
round-4 pass had won the same race by luck). Fixed in `489c4a1`:
`VoipTelecom.remove` now reports the platform's release and the fallback
dials only then, with a regression test pinning the race.

Retest at 17:05 on the fixed build: ARK attempt for 7 s, then a clean
hand-over to the carrier (Moi) with no error. Fallback matrix's core case
is green.

## Rounds 8–9 — network switch mid-call (17:07, 17:09)

**Losing the network (Wi-Fi off mid-call, 17:07): PASS as designed.** The
established Wi-Fi↔Wi-Fi call dropped cleanly ~10 s after the caller's
Wi-Fi went off — libwebrtc's disconnected→failed detection — and both
ends tore down with the new "Internet call connection lost" notice
(`disconnectError` mapping seen in the field). The log also shows
`ARK telecom answer result=CallControlResult(Success)`: the Telecom
answer fix is live. Faster detection or a seamless ICE restart stays
future work.

**Gaining a network (Wi-Fi on mid-call over mobile, 17:09): PASS,
seamlessly.** The call continued without a break — Android keeps the
mobile path up until Wi-Fi is ready, so the TURN relay never broke.

The same round surfaced a UI regression: with the callee's screen awake
and unlocked, the HIGH ARK channel heads-upped a second answer/decline
banner over the opening call screen (the v1.24 duplicate-buttons problem
reborn). Fixed in `fae5b92`: the ring channel is chosen from the screen
state — locked rings HIGH + full-screen intent, awake rings on the
ordinary channel with no banner. Awaiting a screen-on verification call.

## Remaining protocol (stage C)

- [x] Doze test (14:12): rang on the locked screen; socket survived forced
      idle via the listener exemption, so the wake was correctly not needed
- [x] Forced FCM chain (14:20 fail → fixes → 14:31 full pass, see above)
- [x] Mobile→Wi-Fi (16:49): rings and completes — the 2026-08-06 R1
      failure no longer reproduces on the hardened build
- [x] Fallback matrix, core case (17:05): airplane-mode peer → clean
      carrier hand-over at 7 s (after the round-7 race fix)
- [x] Network switch mid-call (17:07, 17:09): losing Wi-Fi → clean ~10 s
      drop with the connection-lost notice; gaining Wi-Fi → seamless
- [x] Awake-screen ring re-verify (17:23, on `fae5b92`): call-screen
      buttons only, no heads-up banner; the tray keeps a quiet entry with
      an answer action as the secondary surface, like a carrier call
- [ ] Mobile ↔ mobile
- [ ] Master switch off → carrier
- [ ] Soak: several calls over a day, both directions
