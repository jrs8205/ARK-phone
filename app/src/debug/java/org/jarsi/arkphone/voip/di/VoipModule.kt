package org.jarsi.arkphone.voip.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jarsi.arkphone.BuildConfig
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.data.SettingsCache
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
import org.jarsi.arkphone.R
import org.jarsi.arkphone.telecom.CallRuleEvaluator
import org.jarsi.arkphone.telecom.DisconnectError
import org.jarsi.arkphone.telecom.MissedCallNotifier
import org.jarsi.arkphone.telecom.ProximityController
import org.jarsi.arkphone.util.PermissionChecker
import org.jarsi.arkphone.voip.telecom.ArkCallLog
import org.jarsi.arkphone.voip.telecom.CallControllerVoipCallUi
import org.jarsi.arkphone.voip.telecom.CoreTelecomRegistrar
import org.jarsi.arkphone.voip.telecom.SystemArkCallLog
import org.jarsi.arkphone.voip.telecom.ToneGeneratorRingback
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
    fun provideArkHttp(client: OkHttpClient): ArkHttp = OkHttpArkHttp(
        // Same pools, but a hard total deadline for the plain HTTP calls: a
        // stalled DNS or route probe must not outlive the caller — the
        // websocket client stays unbounded, its calls are long-lived by design.
        client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build(),
    )

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
    ): VoipMediaSessionFactory =
        VoipMediaSessionFactory { peerCode, offerSdp, remoteCandidates, sinceSeq, callId, scope ->
        WebRtcCallSession(
            // Negative horizon = an outgoing call: nothing before this moment
            // may replay into it.
            signaling = EngineSignaling(
                engine,
                if (sinceSeq >= 0) sinceSeq else engine.currentSignalSeq(),
            ),
            adapterFactory = StreamPeerConnectionAdapterFactory(provider),
            turnFetcher = {
                // Bracketing logs: a 2026-08-09 cold-start call stalled inside
                // this fetch with no trace; these say on which side it died.
                android.util.Log.i("ArkPhone", "ARK turn fetch begins")
                val identity = identityRepository.identity.first()
                android.util.Log.i("ArkPhone", "ARK turn identity=${identity != null}")
                val servers = identity?.let {
                    accountClient.turnCredentials("${it.code}.${it.deviceToken}")
                }
                android.util.Log.i("ArkPhone", "ARK turn servers=${servers?.size}")
                servers
            },
            scope = scope,
            peerId = peerCode,
            initialOfferSdp = offerSdp,
            initialRemoteCandidates = remoteCandidates,
            initialCallId = callId,
        )
    }

    @Provides
    @Singleton
    fun provideArkCallLog(impl: SystemArkCallLog): ArkCallLog = impl

    @Provides
    @Singleton
    fun provideVoipCallCoordinator(
        @ApplicationContext context: Context,
        engine: VoipEngine,
        sessionFactory: VoipMediaSessionFactory,
        telecom: VoipTelecom,
        ui: VoipCallUi,
        linkCache: ArkLinkCache,
        settingsCache: SettingsCache,
        callLog: ArkCallLog,
        missedCallNotifier: MissedCallNotifier,
        callRuleEvaluator: CallRuleEvaluator,
        permissionChecker: PermissionChecker,
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
            missedCallNotifier.onMissedCallsChanged(
                count = callLog.unreadMissedCount(),
                number = record.number,
            )
        },
        clock = clock,
        scope = scope,
        // An ARK call answers to the same rules as a carrier call; the SIM
        // gate does not apply, which evaluate() treats as an unknown account.
        blockCheck = { number -> callRuleEvaluator.evaluate(number).block },
        hasMicPermission = { permissionChecker.has(android.Manifest.permission.RECORD_AUDIO) },
        disconnectErrorFor = { reason ->
            when (reason) {
                "rejected" -> DisconnectError(
                    label = context.getString(R.string.ark_call_end_rejected),
                    description = null,
                )
                "connection-failed", "media-error" -> DisconnectError(
                    label = context.getString(R.string.ark_call_end_connection_lost),
                    description = null,
                )
                else -> null
            }
        },
        // Bounded: a wedged DataStore must degrade to "unlinked" (safe by
        // carrier fallback), not hold the ring forever.
        awaitLinkCache = { withTimeoutOrNull(2_000L) { linkCache.await() } },
        // Bounded like the link cache: a wedged DataStore keeps the default
        // rather than holding the ring forever.
        arkCallsEnabled = {
            withTimeoutOrNull(2_000L) { settingsCache.await() }?.arkInternetCallsEnabled
                ?: settingsCache.current.arkInternetCallsEnabled
        },
        ringback = ToneGeneratorRingback(scope),
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
        proximityController: ProximityController,
    ): VoipStartup = ArkVoipStartup(
        engine = engine,
        onIncoming = coordinator::onIncoming,
        fcmRefresh = fcmRegistration::refresh,
        identities = identityRepository.identity,
        scope = scope,
        proximityController = proximityController,
    )
}
