package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.first
import org.jarsi.arkphone.util.sameCaller
import javax.inject.Inject

/**
 * The app-owned block list. Numbers blocked here run through the rule
 * evaluator like every other block, so the reject-or-voicemail choice
 * applies to them — the platform's BlockedNumberContract list would hard
 * reject the call before this app ever saw it.
 */
class DataStoreBlockedNumbersRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : BlockedNumbersRepository {

    override suspend fun canBlock(): Boolean = true

    override suspend fun isBlocked(number: String): Boolean =
        settingsRepository.settings.first().blockedNumbers.any { sameCaller(it, number) }

    override suspend fun block(number: String): Boolean {
        settingsRepository.addBlockedNumber(number)
        return true
    }

    override suspend fun unblock(number: String): Boolean {
        // The stored form may differ from the queried one (national vs
        // international), so every matching entry goes.
        val matches = settingsRepository.settings.first()
            .blockedNumbers.filter { sameCaller(it, number) }
        matches.forEach { settingsRepository.removeBlockedNumber(it) }
        return matches.isNotEmpty()
    }
}
