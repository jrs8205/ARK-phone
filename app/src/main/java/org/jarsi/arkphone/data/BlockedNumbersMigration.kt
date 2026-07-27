package org.jarsi.arkphone.data

import javax.inject.Inject
import javax.inject.Singleton

/** The platform block list, as far as the migration needs to see it. */
interface SystemBlockedNumberList {
    suspend fun numbers(): List<String>
    suspend fun remove(number: String)
}

/**
 * Moves numbers blocked through the platform's BlockedNumberContract into
 * the app-owned list. The platform rejects its entries before this app ever
 * sees the call, so the reject-or-voicemail choice could never apply to
 * them. Runs at every start; a no-op once the platform list is empty.
 */
@Singleton
class BlockedNumbersMigration @Inject constructor(
    private val systemList: SystemBlockedNumberList,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun migrate() {
        systemList.numbers().forEach { number ->
            settingsRepository.addBlockedNumber(number)
            systemList.remove(number)
        }
    }
}
