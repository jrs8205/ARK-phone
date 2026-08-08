package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.data.model.BlockedCallAction
import org.jarsi.arkphone.data.model.Settings

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setAnnounceMode(mode: AnnounceMode)

    suspend fun setBlockedCallAction(action: BlockedCallAction)
    suspend fun setAnnounceIntervalSeconds(seconds: Int)
    suspend fun setAnnounceWhatsApp(enabled: Boolean)

    suspend fun setBlockAllCallers(enabled: Boolean)

    suspend fun setBlockHiddenNumbers(enabled: Boolean)

    suspend fun setBlockUnknownCallers(enabled: Boolean)

    /** Null restores the system default SIM for outgoing calls. */
    suspend fun setCallSimAccountId(accountId: String?)

    /** Null lets the blocking rules apply to every SIM. */
    suspend fun setBlockingSimAccountId(accountId: String?)

    /** Atomic add/remove: two quick edits from a stale UI snapshot must not
     *  resurrect or lose an entry, so the stored set is modified in place. */
    suspend fun addBlockedPrefix(prefix: String)
    suspend fun removeBlockedPrefix(prefix: String)
    suspend fun addAllowedNumber(number: String)
    suspend fun removeAllowedNumber(number: String)
    suspend fun addBlockedNumber(number: String)
    suspend fun removeBlockedNumber(number: String)

    suspend fun setAllowRepeatCallers(enabled: Boolean)

    suspend fun setRepeatCallerWindowMinutes(minutes: Int)


    suspend fun setAlwaysAllowFavorites(enabled: Boolean)

    suspend fun setBlockingScheduleEnabled(enabled: Boolean)

    suspend fun setBlockingSchedule(startMinutes: Int, endMinutes: Int)

    /** Turns internet call routing off globally without removing any link. */
    suspend fun setArkInternetCallsEnabled(enabled: Boolean)
}
