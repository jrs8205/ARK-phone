package org.jarsi.arkphone.voip.fcm

import kotlinx.coroutines.flow.first
import org.jarsi.arkphone.data.ArkIdentityRepository
import org.jarsi.arkphone.voip.ArkAccountClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the worker's copy of this device's FCM registration token current. A
 * peer registered with no token can never be woken, so the token is posted as
 * soon as an identity exists and again whenever Firebase rotates it.
 */
@Singleton
class FcmTokenSync @Inject constructor(
    private val identityRepository: ArkIdentityRepository,
    private val accountClient: ArkAccountClient,
) {
    /** True when the worker holds [token]. */
    suspend fun sync(token: String): Boolean {
        if (token.isBlank()) return false
        val identity = identityRepository.identity.first()
        if (identity == null) {
            // Registration has not happened yet; remember the token so the
            // registration call itself can carry it.
            identityRepository.setSyncedFcmToken(token)
            return false
        }
        if (identityRepository.syncedFcmToken.first() == token) return true
        val posted = accountClient.updateFcmToken(
            token,
            "${identity.code}.${identity.deviceToken}",
        )
        if (posted) identityRepository.setSyncedFcmToken(token)
        return posted
    }
}
