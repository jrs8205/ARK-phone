package org.jarsi.arkphone.voip.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import org.jarsi.arkphone.BuildConfig
import org.jarsi.arkphone.voip.AndroidKeystoreArkKeyPairSource
import org.jarsi.arkphone.voip.ArkAccountClient
import org.jarsi.arkphone.voip.ArkHttp
import org.jarsi.arkphone.voip.ArkKeyPairSource
import org.jarsi.arkphone.voip.OkHttpArkHttp
import org.jarsi.arkphone.voip.OkHttpWebSocketConnector
import org.jarsi.arkphone.voip.VoipAccountGateway
import org.jarsi.arkphone.voip.VoipConfig
import org.jarsi.arkphone.voip.WebSocketConnector
import org.jarsi.arkphone.voip.WorkerVoipAccountGateway
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Debug-only: this module is what fills the main tree's optional VoIP
 * bindings. A release build has no such module, so every ARK surface resolves
 * Optional.empty() and stays invisible.
 */
@Module
@InstallIn(SingletonComponent::class)
object VoipModule {

    @Provides
    @Singleton
    fun provideVoipOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideVoipConfig(): VoipConfig = VoipConfig(BuildConfig.VOIP_WORKER_URL)

    @Provides
    @Singleton
    fun provideArkHttp(client: OkHttpClient): ArkHttp = OkHttpArkHttp(client)

    @Provides
    @Singleton
    fun provideArkAccountClient(http: ArkHttp, config: VoipConfig): ArkAccountClient =
        ArkAccountClient(http, config.workerUrl)

    @Provides
    @Singleton
    fun provideArkKeyPairSource(): ArkKeyPairSource = AndroidKeystoreArkKeyPairSource()

    @Provides
    @Singleton
    fun provideWebSocketConnector(client: OkHttpClient): WebSocketConnector =
        OkHttpWebSocketConnector(client)

    @Provides
    @Singleton
    fun provideVoipAccountGateway(impl: WorkerVoipAccountGateway): VoipAccountGateway = impl
}
