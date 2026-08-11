package org.jarsi.arkphone.voip.telecom

import android.media.AudioManager
import android.media.ToneGenerator

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

class ToneGeneratorRingback : RingbackController {
    private var tone: ToneGenerator? = null

    override fun start() {
        if (tone != null) return
        // ToneGenerator throws when the audio resource is unavailable; a
        // missing ringback must never take the call attempt down with it.
        tone = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, VOLUME).also {
                it.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }.getOrNull()
    }

    override fun stop() {
        tone?.let { generator ->
            runCatching {
                generator.stopTone()
                generator.release()
            }
        }
        tone = null
    }

    private companion object {
        /** 0–100; below full blast so the tone sits like a carrier ringback. */
        const val VOLUME = 80
    }
}
