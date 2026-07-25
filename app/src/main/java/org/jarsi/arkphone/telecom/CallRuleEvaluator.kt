package org.jarsi.arkphone.telecom

import android.Manifest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jarsi.arkphone.data.CallLogRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.util.Clock
import org.jarsi.arkphone.util.PermissionChecker
import org.jarsi.arkphone.util.minutesOfDay
import org.jarsi.arkphone.util.sameCaller
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Evaluates the blocking rules for one incoming call. Shared by the
 * screening service (which Android only feeds calls from non-contacts) and
 * the in-call service (which sees every call and rejects rule-blocked calls
 * from saved contacts before they ring).
 */
@Singleton
class CallRuleEvaluator @Inject constructor(
    private val settingsCache: SettingsCache,
    private val contactsRepository: ContactsRepository,
    private val callLogRepository: CallLogRepository,
    private val clock: Clock,
    private val permissionChecker: PermissionChecker,
) {
    companion object {
        private const val SETTINGS_TIMEOUT_MILLIS = 1_000L
    }

    data class Decision(val block: Boolean, val details: String)

    suspend fun evaluate(number: String?): Decision {
        val settings = withTimeoutOrNull(SETTINGS_TIMEOUT_MILLIS) { settingsCache.await() }
            ?: settingsCache.current
        val now = minutesOfDay(clock.nowMillis())
        // Without READ_CONTACTS every caller would look unknown and the
        // unknown-caller rule would reject saved contacts too — fail open
        // and treat the caller as known instead.
        val contactsReadable = permissionChecker.has(Manifest.permission.READ_CONTACTS)
        val match = if (number.isNullOrBlank() || !contactsReadable) {
            null
        } else {
            contactsRepository.lookupContact(number)
        }
        val repeat = !number.isNullOrBlank() &&
            isRepeatCaller(number, settings.repeatCallerWindowMinutes)
        val block = shouldBlockCall(
            number = number,
            isInContacts = if (contactsReadable) match != null else true,
            isFavorite = match?.starred == true,
            isRepeatCaller = repeat,
            minutesOfDay = now,
            settings = settings,
        )
        return Decision(
            block = block,
            details = "block=$block hidden=${number.isNullOrBlank()}" +
                " inContacts=${match != null} contactsReadable=$contactsReadable" +
                " favorite=${match?.starred == true}" +
                " repeat=$repeat scheduleActive=${blockingScheduleActive(now, settings)}" +
                " blockAll=${settings.blockAllCallers} blockUnknown=${settings.blockUnknownCallers}",
        )
    }

    private suspend fun isRepeatCaller(number: String, windowMinutes: Int): Boolean {
        val cutoff = clock.nowMillis() - windowMinutes * 60_000L
        return runCatching {
            callLogRepository.callLog().first()
                .any { it.timestampMillis >= cutoff && sameCaller(it.number, number) }
        }.getOrDefault(false)
    }
}
