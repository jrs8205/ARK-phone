package org.jarsi.arkphone.voip.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import okhttp3.OkHttpClient
import org.jarsi.arkphone.BuildConfig
import org.jarsi.arkphone.voip.OkHttpWebSocketConnector
import org.jarsi.arkphone.voip.PeerConnectionFactoryProvider
import org.jarsi.arkphone.voip.SignalingClient
import org.jarsi.arkphone.voip.StreamPeerConnectionAdapterFactory
import org.jarsi.arkphone.voip.TurnCredentialsFetcher
import org.jarsi.arkphone.voip.WebRtcCallSession
import org.jarsi.arkphone.voip.ui.VoipSessionFactory
import org.jarsi.arkphone.voip.ui.VoipSessionHandles
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object VoipModule {

    @Provides
    @Singleton
    fun provideVoipSessionFactory(
        @ApplicationContext context: Context,
    ): VoipSessionFactory {
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
        val provider = PeerConnectionFactoryProvider(context)
        return object : VoipSessionFactory {
            override fun create(
                deviceId: String,
                peerId: String,
                scope: CoroutineScope,
            ): VoipSessionHandles {
                val signaling = SignalingClient(
                    connector = OkHttpWebSocketConnector(client, BuildConfig.VOIP_AUTH_TOKEN),
                    workerUrl = BuildConfig.VOIP_WORKER_URL,
                    deviceId = deviceId,
                    peerId = peerId,
                    scope = scope,
                )
                val turnFetcher = TurnCredentialsFetcher(
                    client = client,
                    workerUrl = BuildConfig.VOIP_WORKER_URL,
                    authToken = BuildConfig.VOIP_AUTH_TOKEN,
                )
                val session = WebRtcCallSession(
                    signaling = signaling,
                    adapterFactory = StreamPeerConnectionAdapterFactory(provider),
                    turnFetcher = { turnFetcher.fetch() },
                    scope = scope,
                    peerId = peerId,
                )
                return VoipSessionHandles(signaling, session)
            }
        }
    }
}
