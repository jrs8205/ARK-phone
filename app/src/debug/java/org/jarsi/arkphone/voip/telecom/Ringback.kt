package org.jarsi.arkphone.voip.telecom

import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Local ringback for an outgoing ARK call: the network plays this tone on a
 *  carrier call, but an internet call has no network in between, so the phone
 *  generates it itself once the callee reports its ring surfaces are up. */
interface RingbackController {
    fun start()
    fun stop()

    object None : RingbackController {
        override fun start() = Unit
        override fun stop() = Unit
    }
}

class ToneGeneratorRingback(private val scope: CoroutineScope) : RingbackController {
    private var job: Job? = null

    override fun start() {
        if (job != null) return
        job = scope.launch {
            // ToneGenerator throws when the audio resource is unavailable; a
            // missing ringback must never take the call attempt down with it.
            val tone = runCatching {
                ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME)
            }.getOrNull() ?: return@launch
            try {
                while (isActive) {
                    tone.startTone(ToneGenerator.TONE_SUP_RINGTONE, TONE_MS)
                    delay(TONE_MS + GAP_MS)
                }
            } finally {
                runCatching {
                    tone.stopTone()
                    tone.release()
                }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        /** 0–100; below full blast so the tone sits like a carrier ringback. */
        const val VOLUME = 80

        /** One burst of the 425 Hz tone. */
        const val TONE_MS = 1_000

        /** The pause between bursts; the tone's built-in carrier cadence
         *  pauses 4 s, which field feedback found too sparse. */
        const val GAP_MS = 2_000L
    }
}
