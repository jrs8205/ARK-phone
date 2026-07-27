package org.jarsi.arkphone.telecom

import org.jarsi.arkphone.data.model.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingRulesTest {

    private fun blocked(
        number: String? = "+358 44 5552841",
        isInContacts: Boolean = false,
        isFavorite: Boolean = false,
        isRepeatCaller: Boolean = false,
        minutesOfDay: Int = 12 * 60,
        settings: Settings,
    ) = shouldBlockCall(number, isInContacts, isFavorite, isRepeatCaller, minutesOfDay, settings)

    @Test
    fun nothingIsBlockedByDefault() {
        assertFalse(blocked(settings = Settings()))
        assertFalse(blocked(number = null, settings = Settings()))
    }

    @Test
    fun hiddenNumbersAreBlockedOnlyWhenEnabled() {
        val settings = Settings(blockHiddenNumbers = true)
        assertTrue(blocked(number = null, settings = settings))
        assertTrue(blocked(number = " ", settings = settings))
        assertFalse(blocked(number = "+358 44 5552841", settings = settings))
    }

    @Test
    fun unknownCallersAreBlockedOnlyWhenEnabledAndNotInContacts() {
        val settings = Settings(blockUnknownCallers = true)
        assertTrue(blocked(isInContacts = false, settings = settings))
        assertFalse(blocked(isInContacts = true, settings = settings))
    }

    @Test
    fun anExplicitlyBlockedNumberIsBlocked() {
        val settings = Settings(blockedNumbers = setOf("+358445552841"))
        assertTrue(blocked(number = "+358 44 5552841", settings = settings))
        assertTrue(blocked(number = "044 555 2841", settings = settings))
        assertFalse(blocked(number = "0401234567", settings = settings))
    }

    @Test
    fun anExplicitBlockBeatsEveryException() {
        // Blocking one specific number is the user's most specific intent:
        // favorites, the allow list and the repeat exception must not undo it.
        val settings = Settings(
            blockedNumbers = setOf("0445552841"),
            allowedNumbers = setOf("0445552841"),
            alwaysAllowFavorites = true,
            allowRepeatCallers = true,
        )
        assertTrue(
            blocked(
                number = "0445552841",
                isInContacts = true,
                isFavorite = true,
                isRepeatCaller = true,
                settings = settings,
            ),
        )
    }

    @Test
    fun anExplicitBlockIgnoresTheSchedule() {
        val settings = Settings(
            blockedNumbers = setOf("0445552841"),
            blockingScheduleEnabled = true,
            blockingScheduleStartMinutes = 21 * 60,
            blockingScheduleEndMinutes = 7 * 60,
        )
        // Midday, outside the 21–07 window.
        assertTrue(blocked(number = "0445552841", minutesOfDay = 12 * 60, settings = settings))
    }

    @Test
    fun blockedPrefixesMatchIgnoringFormatting() {
        val settings = Settings(blockedPrefixes = setOf("+358700"))
        assertTrue(blocked(number = "+358 700 123 456", settings = settings))
        assertFalse(blocked(number = "+358 44 5552841", settings = settings))
    }

    @Test
    fun prefixesBlockEvenSavedContacts() {
        val settings = Settings(blockedPrefixes = setOf("0700"))
        assertTrue(blocked(number = "0700 123 456", isInContacts = true, settings = settings))
    }

    @Test
    fun repeatCallersBypassTheRulesWhenAllowed() {
        val settings = Settings(
            blockUnknownCallers = true,
            blockedPrefixes = setOf("0700"),
            allowRepeatCallers = true,
        )
        assertFalse(blocked(number = "0700 123 456", isRepeatCaller = true, settings = settings))
    }

    @Test
    fun repeatCallersDoNotBypassWhenTheExceptionIsOff() {
        val settings = Settings(blockUnknownCallers = true, allowRepeatCallers = false)
        assertTrue(blocked(isRepeatCaller = true, settings = settings))
    }

    @Test
    fun hiddenNumbersNeverGetTheRepeatException() {
        // A hidden caller can't be recognized as a repeat caller.
        val settings = Settings(blockHiddenNumbers = true, allowRepeatCallers = true)
        assertTrue(blocked(number = null, isRepeatCaller = true, settings = settings))
    }

    @Test
    fun allowedNumbersAlwaysGetThrough() {
        val settings = Settings(
            blockUnknownCallers = true,
            blockedPrefixes = setOf("0700"),
            allowedNumbers = setOf("+358 44 5552841", "0700123456"),
        )
        assertFalse(blocked(number = "0445552841", settings = settings))
        assertFalse(blocked(number = "0700 123 456", settings = settings))
    }

    @Test
    fun favoritesGetThroughWhenTheSwitchIsOn() {
        val settings = Settings(blockUnknownCallers = true, blockedPrefixes = setOf("0700"))
        assertFalse(blocked(number = "0700 123", isFavorite = true, settings = settings))
        assertTrue(
            blocked(
                number = "0700 123",
                isFavorite = true,
                settings = settings.copy(alwaysAllowFavorites = false),
            ),
        )
    }

    @Test
    fun blockAllBlocksEvenSavedContactsAndHiddenNumbers() {
        val settings = Settings(blockAllCallers = true)
        assertTrue(blocked(isInContacts = true, settings = settings))
        assertTrue(blocked(number = null, settings = settings))
    }

    @Test
    fun blockAllStillHonorsTheExceptionsAndTheSchedule() {
        val settings = Settings(blockAllCallers = true)
        assertFalse(blocked(isFavorite = true, settings = settings))
        assertFalse(blocked(isRepeatCaller = true, settings = settings))
        assertFalse(
            blocked(
                settings = settings.copy(allowedNumbers = setOf("+358 44 5552841")),
            ),
        )
        val nightOnly = settings.copy(
            blockingScheduleEnabled = true,
            blockingScheduleStartMinutes = 20 * 60,
            blockingScheduleEndMinutes = 7 * 60,
        )
        assertFalse(blocked(minutesOfDay = 12 * 60, settings = nightOnly))
        assertTrue(blocked(minutesOfDay = 22 * 60, isInContacts = true, settings = nightOnly))
    }

    @Test
    fun prefixBlocksStayActiveOutsideTheScheduleWindow() {
        val settings = Settings(
            blockedPrefixes = setOf("0700"),
            blockingScheduleEnabled = true,
            blockingScheduleStartMinutes = 21 * 60,
            blockingScheduleEndMinutes = 7 * 60,
        )
        assertTrue(blocked(number = "0700 123 456", minutesOfDay = 12 * 60, settings = settings))
        assertFalse(blocked(number = "+358 44 5552841", minutesOfDay = 12 * 60, settings = settings))
    }

    @Test
    fun prefixBlocksOutsideTheScheduleStillHonorTheExceptions() {
        val settings = Settings(
            blockedPrefixes = setOf("0700"),
            allowRepeatCallers = true,
            blockingScheduleEnabled = true,
            blockingScheduleStartMinutes = 21 * 60,
            blockingScheduleEndMinutes = 7 * 60,
        )
        assertFalse(
            blocked(number = "0700 123 456", isFavorite = true, minutesOfDay = 12 * 60, settings = settings),
        )
        assertFalse(
            blocked(number = "0700 123 456", isRepeatCaller = true, minutesOfDay = 12 * 60, settings = settings),
        )
        assertFalse(
            blocked(
                number = "0700 123 456",
                minutesOfDay = 12 * 60,
                settings = settings.copy(allowedNumbers = setOf("0700 123 456")),
            ),
        )
    }

    @Test
    fun prefixesMatchAcrossNationalAndInternationalForms() {
        val national = Settings(blockedPrefixes = setOf("0700"))
        assertTrue(blocked(number = "+358 700 123 456", settings = national))
        val international = Settings(blockedPrefixes = setOf("+358700"))
        assertTrue(blocked(number = "0700 123 456", settings = international))
        assertFalse(blocked(number = "+358 44 5552841", settings = national))
    }

    @Test
    fun scheduleLimitsBlockingToItsWindow() {
        val settings = Settings(
            blockUnknownCallers = true,
            blockingScheduleEnabled = true,
            blockingScheduleStartMinutes = 21 * 60,
            blockingScheduleEndMinutes = 7 * 60,
        )
        assertTrue(blocked(minutesOfDay = 23 * 60, settings = settings))
        assertTrue(blocked(minutesOfDay = 3 * 60, settings = settings))
        assertFalse(blocked(minutesOfDay = 12 * 60, settings = settings))
        val daytime = settings.copy(
            blockingScheduleStartMinutes = 9 * 60,
            blockingScheduleEndMinutes = 17 * 60,
        )
        assertTrue(blocked(minutesOfDay = 10 * 60, settings = daytime))
        assertFalse(blocked(minutesOfDay = 20 * 60, settings = daytime))
    }
}
