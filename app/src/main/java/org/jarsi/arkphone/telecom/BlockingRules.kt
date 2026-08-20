package org.jarsi.arkphone.telecom

import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.util.internationalDigits
import org.jarsi.arkphone.util.nationalSignificantDigits
import org.jarsi.arkphone.util.sameCaller

/**
 * The call blocking rule: individually blocked numbers, hidden numbers,
 * callers not in contacts and blocked prefixes — overridden by the allow
 * list and favorites, except that blocking one specific number is the
 * user's most specific intent and beats them all. The repeat-caller
 * exception bypasses only the general rules (schedule, block-all,
 * block-unknown): per-number and prefix blocks name the unwanted callers
 * deliberately, so a redialing robocaller must not ride through them.
 * The schedule window scopes every rule except the per-number blocks and
 * the blocked prefixes, which stay active around the clock.
 */
internal fun shouldBlockCall(
    number: String?,
    isInContacts: Boolean,
    isFavorite: Boolean,
    isRepeatCaller: Boolean,
    minutesOfDay: Int,
    settings: Settings,
): Boolean {
    if (number.isNullOrBlank()) {
        return blockingScheduleActive(minutesOfDay, settings) &&
            (settings.blockHiddenNumbers || settings.blockAllCallers)
    }
    if (settings.blockedNumbers.any { sameCaller(it, number) }) return true
    if (settings.allowedNumbers.any { sameCaller(it, number) }) return false
    if (settings.alwaysAllowFavorites && isFavorite) return false
    if (settings.blockedPrefixes.any { matchesBlockedPrefix(number, it) }) return true
    if (isRepeatCaller && settings.allowRepeatCallers) return false
    if (!blockingScheduleActive(minutesOfDay, settings)) return false
    if (settings.blockAllCallers) return true
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
    if (cleanPrefix.none { it.isDigit() }) return false
    if (cleanNumber.startsWith(cleanPrefix)) return true
    val numberIntl = internationalDigits(number)
    val prefixIntl = internationalDigits(prefix)
    // "+44" and "0044" spell the same international prefix, so the two
    // forms must also be compared digits-to-digits.
    if (numberIntl != null && prefixIntl != null && numberIntl.startsWith(prefixIntl)) return true
    // "0700" must also catch "+358 700…" and "+358700" must catch "0700…".
    // The country-code length is unknown, so try one to three digits.
    val prefixSignificant = nationalSignificantDigits(prefix)
    if (numberIntl != null && prefixSignificant != null && prefixSignificant.length >= 2 &&
        (1..3).any { numberIntl.drop(it).startsWith(prefixSignificant) }
    ) {
        return true
    }
    val numberSignificant = nationalSignificantDigits(number)
    return prefixIntl != null && numberSignificant != null &&
        (1..3).any { countryCodeLength ->
            val rest = prefixIntl.drop(countryCodeLength)
            rest.isNotEmpty() && numberSignificant.startsWith(rest)
        }
}
