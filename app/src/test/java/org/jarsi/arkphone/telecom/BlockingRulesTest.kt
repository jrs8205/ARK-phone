package org.jarsi.arkphone.telecom

import org.jarsi.arkphone.data.model.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockingRulesTest {

    private fun blocked(
        number: String? = "+358 44 5552841",
        isInContacts: Boolean = false,
        isRepeatCaller: Boolean = false,
        settings: Settings,
    ) = shouldBlockCall(number, isInContacts, isRepeatCaller, settings)

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
}
