package org.jarsi.arkphone.telecom

import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.util.sameCaller

/**
 * The call blocking rule: hidden numbers, callers not in contacts and
 * blocked prefixes — limited to the schedule window when one is enabled,
 * and overridden by the allow list, favorites and the repeat-caller
 * exception.
 */
internal fun shouldBlockCall(
    number: String?,
    isInContacts: Boolean,
    isFavorite: Boolean,
    isRepeatCaller: Boolean,
    minutesOfDay: Int,
    settings: Settings,
): Boolean {
    if (!blockingScheduleActive(minutesOfDay, settings)) return false
    if (number.isNullOrBlank()) {
        return settings.blockHiddenNumbers || settings.blockAllCallers
    }
    if (settings.allowedNumbers.any { sameCaller(it, number) }) return false
    if (settings.alwaysAllowFavorites && isFavorite) return false
    if (isRepeatCaller && settings.allowRepeatCallers) return false
    if (settings.blockAllCallers) return true
    if (settings.blockedPrefixes.any { matchesBlockedPrefix(number, it) }) return true
    return settings.blockUnknownCallers && !isInContacts
}

internal fun blockingScheduleActive(minutesOfDay: Int, settings: Settings): Boolean {
    if (!settings.blockingScheduleEnabled) return true
    val start = settings.blockingScheduleStartMinutes
    val end = settings.blockingScheduleEndMinutes
    return if (start <= end) {
        minutesOfDay in start until end
    } else {
        // Overnight window, e.g. 21:00–07:00.
        minutesOfDay >= start || minutesOfDay < end
    }
}

private fun matchesBlockedPrefix(number: String, prefix: String): Boolean {
    val cleanNumber = number.filter { it.isDigit() || it == '+' }
    val cleanPrefix = prefix.filter { it.isDigit() || it == '+' }
    return cleanPrefix.isNotEmpty() && cleanNumber.startsWith(cleanPrefix)
}
