package org.jarsi.arkphone.voip.fcm

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val pending = MutableStateFlow<String?>(null)

    /** A token seen before registration, for the registration call to carry. */
    val pendingToken: StateFlow<String?> = pending.asStateFlow()

    /** True when the worker holds [token]. */
    suspend fun sync(token: String): Boolean {
        if (token.isBlank()) return false
        val identity = identityRepository.identity.first()
        if (identity == null) {
            // NOT the synced marker: writing it there once raced an in-flight
            // registration that had already read a null token — the marker
            // then said "worker has it" about a token the worker never saw,
            // and the phone stayed unwakeable.
            pending.value = token
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
