package org.jarsi.arkphone.telecom

import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.util.internationalDigits
import org.jarsi.arkphone.util.nationalSignificantDigits
import org.jarsi.arkphone.util.sameCaller

/**
 * The call blocking rule: hidden numbers, callers not in contacts and
 * blocked prefixes — overridden by the allow list, favorites and the
 * repeat-caller exception. The schedule window scopes every rule except the
 * blocked prefixes, which are a black list like the per-number blocks and
 * stay active around the clock.
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
    if (settings.allowedNumbers.any { sameCaller(it, number) }) return false
    if (settings.alwaysAllowFavorites && isFavorite) return false
    if (isRepeatCaller && settings.allowRepeatCallers) return false
    if (settings.blockedPrefixes.any { matchesBlockedPrefix(number, it) }) return true
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
    // "0700" must also catch "+358 700…" and "+358700" must catch "0700…".
    // The country-code length is unknown, so try one to three digits.
    val numberIntl = internationalDigits(number)
    val prefixSignificant = nationalSignificantDigits(prefix)
    if (numberIntl != null && prefixSignificant != null && prefixSignificant.length >= 2 &&
        (1..3).any { numberIntl.drop(it).startsWith(prefixSignificant) }
    ) {
        return true
    }
    val prefixIntl = internationalDigits(prefix)
    val numberSignificant = nationalSignificantDigits(number)
    return prefixIntl != null && numberSignificant != null &&
        (1..3).any { countryCodeLength ->
            val rest = prefixIntl.drop(countryCodeLength)
            rest.isNotEmpty() && numberSignificant.startsWith(rest)
        }
}
