package org.jarsi.arkphone.voip

import kotlinx.coroutines.flow.Flow

/**
 * A number↔ARK-code link. Links live only on this device: nothing about the
 * contact list is ever sent to the worker.
 */
data class ArkLink(
    val numberKey: String,
    val number: String,
    val code: String,
    val nickname: String,
    val publicKey: String,
    val linkedAtMillis: Long,
)

interface ArkLinkRepository {
    val links: Flow<List<ArkLink>>

    suspend fun link(
        number: String,
        code: String,
        nickname: String,
        publicKey: String,
        atMillis: Long,
    )

    suspend fun unlink(number: String)
}
