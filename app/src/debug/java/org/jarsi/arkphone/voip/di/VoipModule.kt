package org.jarsi.arkphone.voip.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.jarsi.arkphone.BuildConfig
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.voip.AndroidKeystoreArkKeyPairSource
import org.jarsi.arkphone.voip.ArkAccountClient
import org.jarsi.arkphone.voip.ArkHttp
import org.jarsi.arkphone.voip.ArkKeyPairSource
import org.jarsi.arkphone.voip.ArkLinkCache
import org.jarsi.arkphone.voip.EngineSignaling
import org.jarsi.arkphone.voip.OkHttpArkHttp
import org.jarsi.arkphone.voip.OkHttpWebSocketConnector
import org.jarsi.arkphone.voip.PeerConnectionFactoryProvider
import org.jarsi.arkphone.voip.StreamPeerConnectionAdapterFactory
import org.jarsi.arkphone.voip.VoipAccountGateway
import org.jarsi.arkphone.voip.VoipCallGateway
import org.jarsi.arkphone.voip.VoipConfig
import org.jarsi.arkphone.voip.VoipEngine
import org.jarsi.arkphone.voip.ArkVoipStartup
import org.jarsi.arkphone.voip.VoipMediaSessionFactory
import org.jarsi.arkphone.voip.VoipStartup
import org.jarsi.arkphone.voip.WebRtcCallSession
import org.jarsi.arkphone.voip.fcm.ArkFcmRegistration
import org.jarsi.arkphone.voip.WebSocketConnector
import org.jarsi.arkphone.voip.WorkerVoipAccountGateway
import org.jarsi.arkphone.telecom.MissedCallNotifier
import org.jarsi.arkphone.voip.telecom.ArkCallLog
import org.jarsi.arkphone.voip.telecom.CallControllerVoipCallUi
import org.jarsi.arkphone.voip.telecom.CoreTelecomRegistrar
import org.jarsi.arkphone.voip.telecom.SystemArkCallLog
import org.jarsi.arkphone.voip.telecom.VoipCallCoordinator
import org.jarsi.arkphone.voip.telecom.VoipCallUi
import org.jarsi.arkphone.voip.telecom.VoipTelecom
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

    @Provides
    @Singleton
    fun provideVoipTelecom(impl: CoreTelecomRegistrar): VoipTelecom = impl

    @Provides
    @Singleton
    fun provideVoipCallUi(impl: CallControllerVoipCallUi): VoipCallUi = impl

    @Provides
    @Singleton
    fun providePeerConnectionFactoryProvider(
        @ApplicationContext context: Context,
    ): PeerConnectionFactoryProvider = PeerConnectionFactoryProvider(context)

    @Provides
    @Singleton
    fun provideVoipMediaSessionFactory(
        engine: VoipEngine,
        accountClient: ArkAccountClient,
        identityRepository: ArkIdentityRepository,
        provider: PeerConnectionFactoryProvider,
    ): VoipMediaSessionFactory = VoipMediaSessionFactory { peerCode, offerSdp, scope ->
        WebRtcCallSession(
            signaling = EngineSignaling(engine),
            adapterFactory = StreamPeerConnectionAdapterFactory(provider),
            turnFetcher = {
                val identity = identityRepository.identity.first()
                identity?.let {
                    accountClient.turnCredentials("${it.code}.${it.deviceToken}")
                }
            },
            scope = scope,
            peerId = peerCode,
            initialOfferSdp = offerSdp,
        )
    }

    @Provides
    @Singleton
    fun provideArkCallLog(impl: SystemArkCallLog): ArkCallLog = impl

    @Provides
    @Singleton
    fun provideVoipCallCoordinator(
        engine: VoipEngine,
        sessionFactory: VoipMediaSessionFactory,
        telecom: VoipTelecom,
        ui: VoipCallUi,
        linkCache: ArkLinkCache,
        callLog: ArkCallLog,
        missedCallNotifier: MissedCallNotifier,
        clock: Clock,
        @ApplicationScope scope: CoroutineScope,
    ): VoipCallCoordinator = VoipCallCoordinator(
        reachCheck = { code, timeoutMs -> engine.reach(code, timeoutMs) },
        sessionFactory = sessionFactory,
        telecom = telecom,
        ui = ui,
        nicknameForCode = { code -> linkCache.current.values.firstOrNull { it.code == code }?.nickname },
        numberForCode = { code -> linkCache.current.values.firstOrNull { it.code == code }?.number },
        callLog = callLog,
        missedCalls = { record ->
            missedCallNotifier.onMissedCallsChanged(count = 1, number = record.number)
        },
        clock = clock,
        scope = scope,
    )

    @Provides
    @Singleton
    fun provideVoipCallGateway(impl: VoipCallCoordinator): VoipCallGateway = impl

    @Provides
    @Singleton
    fun provideVoipStartup(
        engine: VoipEngine,
        coordinator: VoipCallCoordinator,
        fcmRegistration: ArkFcmRegistration,
        identityRepository: ArkIdentityRepository,
        @ApplicationScope scope: CoroutineScope,
    ): VoipStartup = ArkVoipStartup(
        engine = engine,
        onIncoming = coordinator::onIncoming,
        fcmRefresh = fcmRegistration::refresh,
        identities = identityRepository.identity,
        scope = scope,
    )
}
