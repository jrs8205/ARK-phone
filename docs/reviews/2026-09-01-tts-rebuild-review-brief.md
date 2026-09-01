# External review brief — TTS engine rebuild after a dead binding (2026-09-01)

You are reviewing **one fix** in the ARK-phone repository (branch
`feature/voip-spike`, commit `cabc423`): the caller-announcement speech
engine now rebuilds its `TextToSpeech` instance when the engine binding has
died. The defect it fixes was diagnosed on a device with full log evidence,
so the root cause is not in question — **your job is to verify the fix is
correct and complete, and to find any defect the change introduces or leaves
open.**

## Ground rules

- **Report only. No patches.** Every finding will be verified against the
  code and fixed by the maintainer separately.
- Finding format: severity P1/P2/P3 · `file:line` · one-sentence claim ·
  concrete failure scenario · confidence. Sorted by severity. No style notes.
- The diagnosis below is device-verified fact; do not re-litigate it.
  Review the **new code paths** and their interaction with the rest of the
  announcement pipeline.

## The field defect (device-verified, context)

- A Pixel 9a runs ARK as its default dialer with announce mode
  **voice-only**: the ringtone notification is posted silent
  (`ArkInCallService.kt` passes `silentRing = true`) and the caller's name
  is spoken in a loop by `CallerAnnouncer` → `TtsSpeechEngine`.
- The ARK process had been alive **11 days 21 h** (default dialers live
  long). On 2026-08-29 the Google TTS package (`com.google.android.tts`)
  self-updated beneath it.
- The framework `TextToSpeech` object never rebinds after its engine
  service dies. From that moment every `speak()` returned `ERROR`, logging
  `W/TextToSpeech: speak failed: not bound to TTS engine` — captured live
  during a test call, repeating at the announce interval while the phone
  only vibrated (voice-only mode has no audible ringtone by design).
- `TtsSpeechEngine` is a `@Singleton` that created its `TextToSpeech` once,
  never read the `speak()` result, and never recreated the engine — so the
  announcement stayed dead until the process was killed. Restarting the
  process restored speech immediately (verified with a second test call).

## The fix under review

`app/src/main/java/org/jarsi/arkphone/telecom/SpeechEngine.kt`
(class `TtsSpeechEngine`), commit `cabc423`:

- `speak(text)` sets `rebuildAllowed = true` and delegates to a new private
  `speakInternal(text)`.
- `speakInternal` now checks the result of `engine.speak(...)`. On any
  non-`SUCCESS` result, **if `rebuildAllowed` is still true**: it flips
  `rebuildAllowed` to false, logs a warning, `runCatching { shutdown() }`
  on the dead engine, clears `tts`/`ready`, parks the text in `pending`,
  and calls `create()` — the fresh engine's `onInit` then speaks the parked
  text via `speakInternal`.
- `onInit(SUCCESS)` was changed to take `pending` into a local, null the
  field, then call `speakInternal(parked)` — previously it called the
  public `speak()` and nulled `pending` *after*, which would now both reset
  the rebuild guard and clobber a re-parked utterance.
- One rebuild per external `speak()` call: if the rebuilt engine's retry
  also fails, it gives up silently (no loop). The next external `speak()`
  gets a fresh guard, so in the voice-only repeat loop the engine retries
  a rebuild once per announcement interval until ringing stops.

Threading contract (pre-existing, unchanged): all calls arrive on the main
dispatcher and `onInit` also lands on the main thread, so the mutable state
(`tts`, `ready`, `pending`, `rebuildAllowed`, `focusRequest`) is
single-threaded by design.

Tests added
(`app/src/test/java/org/jarsi/arkphone/telecom/TtsSpeechEngineTest.kt`):
a Robolectric shadow subclass whose `speak()` returns queued failures;
one test proves a dead binding is rebuilt and the utterance spoken by the
new instance, another proves a persistently failing engine is rebuilt only
once and that a later announcement still speaks on the kept engine.

## What to verify (at minimum)

1. **State-machine holes.** Any interleaving of `speak` / `stop` /
   `onInit` (SUCCESS and ERROR) / rebuild that loses an utterance that
   should be spoken, speaks a stale one after `stop()`, or leaks
   `pending` across calls.
2. **Audio focus.** `takeAudioFocus()` is called before the ready check and
   focus is held across a rebuild; `stop()` releases it. Can a give-up path
   leave focus held with nothing speaking longer than the ringing episode?
   Can focus be requested twice or abandoned twice?
3. **Loop bounds.** Convince yourself no input sequence makes the rebuild
   recurse or ping-pong unboundedly within one announcement (note
   `onInit` → `speakInternal` → failure → guard).
4. **The old-engine corpse.** `shutdown()` on a dead binding, `tts = null`
   before `create()` — anything reachable that still holds the old
   instance (utterance callbacks, the framework's static state)?
5. **`create()` returning null** mid-rebuild, and `onInit(ERROR)` on the
   rebuilt engine — both should degrade to silence, not to a wedged state
   that ignores all future announcements.
6. **Interaction with callers.** `CallerAnnouncer` (voice-only repeat loop,
   WITH_RINGTONE double speak, the WhatsApp path) and `RingSilencer` — does
   any of them assume `speak()` is fire-and-forget in a way the rebuild
   breaks (timing, double speech, speech after answer)?
7. **The `setSpeechRate` result is still ignored** — is that safe on a dead
   binding given the rebuild now triggers off `speak()`'s result?

## Also in the same unreleased set (secondary, diff-only)

Two earlier unreleased commits sit on the same branch after v1.26; review
their diffs only if something in them intersects the announcement path:

- `e4de62d` — conversations list hides threads with `message_count == 0`
  (draft-ghost rows born when a composer opens).
- `0626fc7` — every build variant carries a `versionNameSuffix`
  (`-debug`/`-beta`/`-release`).

## Environment

- Android app module only; `minSdk 26`, `targetSdk 36`, Kotlin, Hilt,
  Robolectric unit tests. Full suite + lint green at `cabc423`
  (914 unit tests).
- The announcement pipeline files:
  `telecom/SpeechEngine.kt` (the fix), `telecom/CallerAnnouncer.kt`,
  `telecom/SpeechAvailability.kt`, `telecom/RingSilencer.kt`,
  `telecom/ArkInCallService.kt` (ringing entry point, `silentRing`),
  `telecom/WhatsAppCallMonitor.kt` (WhatsApp announce entry).
