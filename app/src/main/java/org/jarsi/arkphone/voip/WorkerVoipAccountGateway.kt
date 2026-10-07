package org.jarsi.arkphone.voip

import kotlinx.coroutines.flow.first
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.voip.fcm.FcmTokenSync
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerVoipAccountGateway @Inject constructor(
    private val accountClient: ArkAccountClient,
    private val identityRepository: ArkIdentityRepository,
    private val keyPairSource: ArkKeyPairSource,
    private val tokenSync: FcmTokenSync,
) : VoipAccountGateway {

    override suspend fun register(nickname: String): ArkRegistration? {
        // No key, no identity: the account is key-bound from day one.
        val publicKey = keyPairSource.publicKeyBase64() ?: return null
        // Best-effort carry; the post-registration refresh posts it for real.
        val fcmToken = tokenSync.pendingToken.value
        return accountClient.register(nickname, publicKey, fcmToken)
    }

    override suspend fun lookUp(code: String): ArkLookupResult {
        val identity = identityRepository.identity.first() ?: return ArkLookupResult.Failed
        return accountClient.lookUp(code, "${identity.code}.${identity.deviceToken}")
    }
}
