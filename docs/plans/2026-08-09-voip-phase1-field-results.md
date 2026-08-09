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

## Remaining protocol (stage C)

- [x] Doze test (14:12): rang on the locked screen; socket survived forced
      idle via the listener exemption, so the wake was correctly not needed
- [ ] Optional: forced FCM chain (notification access off + am stop-app)
- [ ] Fallback matrix: airplane-mode peer → carrier call after ~7 s
- [ ] Superseded / network-switch during a call
- [ ] Mobile ↔ mobile
- [ ] Master switch off → carrier
- [ ] Soak: several calls over a day, both directions
