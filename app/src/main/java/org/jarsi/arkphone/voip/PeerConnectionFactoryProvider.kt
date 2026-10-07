package org.jarsi.arkphone.voip

import android.content.Context
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/** One process-wide libwebrtc factory, initialized lazily on first call. */
class PeerConnectionFactoryProvider(private val context: Context) {

    val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .createPeerConnectionFactory()
    }
}
