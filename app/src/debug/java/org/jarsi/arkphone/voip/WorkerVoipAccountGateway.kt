package org.jarsi.arkphone.voip

import kotlinx.coroutines.flow.first
import org.jarsi.arkphone.data.ArkIdentityRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkerVoipAccountGateway @Inject constructor(
    private val accountClient: ArkAccountClient,
    private val identityRepository: ArkIdentityRepository,
    private val keyPairSource: ArkKeyPairSource,
) : VoipAccountGateway {

    override suspend fun register(nickname: String): ArkRegistration? {
        // No key, no identity: the account is key-bound from day one.
        val publicKey = keyPairSource.publicKeyBase64() ?: return null
        val fcmToken = identityRepository.syncedFcmToken.first()
        return accountClient.register(nickname, publicKey, fcmToken)
    }

    override suspend fun lookUp(code: String): ArkAccount? {
        val identity = identityRepository.identity.first() ?: return null
        return accountClient.lookUp(code, "${identity.code}.${identity.deviceToken}")
    }
}
