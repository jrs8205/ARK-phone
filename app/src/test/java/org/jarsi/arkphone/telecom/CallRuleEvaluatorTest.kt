package org.jarsi.arkphone.telecom

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.data.model.ContactMatch
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.testing.FakeCallLogRepository
import org.jarsi.arkphone.testing.FakeContactsRepository
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.jarsi.arkphone.util.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallRuleEvaluatorTest {

    private val clock = Clock { 12 * 60 * 60_000L }

    @Test
    fun blocksUnknownCallersWhenContactsAreReadable() = runTest {
        val evaluator = CallRuleEvaluator(
            SettingsCache(FakeSettingsRepository(Settings(blockUnknownCallers = true)), backgroundScope),
            FakeContactsRepository(),
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertTrue(evaluator.evaluate("0401234567").block)
    }

    @Test
    fun savedContactsAreNotBlockedAsUnknown() = runTest {
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["0401234567"] = ContactMatch("Alice", null)
        }
        val evaluator = CallRuleEvaluator(
            SettingsCache(FakeSettingsRepository(Settings(blockUnknownCallers = true)), backgroundScope),
            contacts,
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertFalse(evaluator.evaluate("0401234567").block)
    }

    @Test
    fun unknownCallerRuleFailsOpenWithoutContactsPermission() = runTest {
        // Without READ_CONTACTS every caller would look unknown; blocking
        // must not silently reject saved contacts then.
        val evaluator = CallRuleEvaluator(
            SettingsCache(FakeSettingsRepository(Settings(blockUnknownCallers = true)), backgroundScope),
            FakeContactsRepository(),
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker(),
        )
        assertFalse(evaluator.evaluate("0401234567").block)
    }

    @Test
    fun skipsAllProviderWorkWhenNoRuleCanBlock() = runTest {
        val contacts = FakeContactsRepository()
        val callLog = FakeCallLogRepository()
        val evaluator = CallRuleEvaluator(
            SettingsCache(FakeSettingsRepository(Settings()), backgroundScope),
            contacts,
            callLog,
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertFalse(evaluator.evaluate("0401234567").block)
        assertEquals(0, contacts.lookupCalls)
        assertEquals(0, callLog.callLogCollections)
        assertEquals(emptyList<Long>(), callLog.callsSinceRequests)
    }

    @Test
    fun prefixRuleWithoutContactDependentRulesSkipsTheContactLookup() = runTest {
        val contacts = FakeContactsRepository()
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(
                    Settings(blockedPrefixes = setOf("0700"), alwaysAllowFavorites = false),
                ),
                backgroundScope,
            ),
            contacts,
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertTrue(evaluator.evaluate("0700123456").block)
        assertEquals(0, contacts.lookupCalls)
    }

    @Test
    fun repeatQueryIsSkippedWhenTheExceptionIsDisabled() = runTest {
        val callLog = FakeCallLogRepository()
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(
                    Settings(blockAllCallers = true, allowRepeatCallers = false),
                ),
                backgroundScope,
            ),
            FakeContactsRepository(),
            callLog,
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertTrue(evaluator.evaluate("0401234567").block)
        assertEquals(0, callLog.callLogCollections)
        assertEquals(emptyList<Long>(), callLog.callsSinceRequests)
    }

    @Test
    fun repeatQueryIsBoundedByTheConfiguredWindow() = runTest {
        val callLog = FakeCallLogRepository()
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(
                    Settings(blockAllCallers = true, repeatCallerWindowMinutes = 15),
                ),
                backgroundScope,
            ),
            FakeContactsRepository(),
            callLog,
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        evaluator.evaluate("0401234567")
        assertEquals(listOf(clock.nowMillis() - 15 * 60_000L), callLog.callsSinceRequests)
        assertEquals(0, callLog.callLogCollections)
    }

    @Test
    fun favoritesExceptionStillLooksUpTheContact() = runTest {
        val contacts = FakeContactsRepository().apply {
            matchesByNumber["0401234567"] = ContactMatch("Alice", null, starred = true)
        }
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(Settings(blockAllCallers = true, allowRepeatCallers = false)),
                backgroundScope,
            ),
            contacts,
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertFalse(evaluator.evaluate("0401234567").block)
        assertEquals(1, contacts.lookupCalls)
    }

    @Test
    fun anExplicitlyBlockedNumberBlocksWithoutOtherRules() = runTest {
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(Settings(blockedNumbers = setOf("+358445552841"))),
                backgroundScope,
            ),
            FakeContactsRepository(),
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertTrue(evaluator.evaluate("0445552841").block)
    }

    @Test
    fun anExplicitBlockAppliesOnEverySim() = runTest {
        // The SIM restriction scopes the general rules; blocking one specific
        // number mirrors the old system-wide block and ignores it.
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(
                    Settings(
                        blockedNumbers = setOf("0445552841"),
                        blockingSimAccountId = "sub-1",
                    ),
                ),
                backgroundScope,
            ),
            FakeContactsRepository(),
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertTrue(evaluator.evaluate("0445552841", simAccountId = "sub-2").block)
    }

    @Test
    fun rulesLimitedToOneSimSkipCallsOnAnotherSim() = runTest {
        val contacts = FakeContactsRepository()
        val callLog = FakeCallLogRepository()
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(
                    Settings(blockAllCallers = true, blockingSimAccountId = "sub-1"),
                ),
                backgroundScope,
            ),
            contacts,
            callLog,
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertFalse(evaluator.evaluate("0401234567", simAccountId = "sub-2").block)
        // A skipped SIM must not cost a contact lookup or a log read either.
        assertEquals(0, contacts.lookupCalls)
        assertEquals(emptyList<Long>(), callLog.callsSinceRequests)
    }

    @Test
    fun rulesLimitedToOneSimStillBlockOnThatSim() = runTest {
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(
                    Settings(blockAllCallers = true, blockingSimAccountId = "sub-1"),
                ),
                backgroundScope,
            ),
            FakeContactsRepository(),
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertTrue(evaluator.evaluate("0401234567", simAccountId = "sub-1").block)
    }

    @Test
    fun withoutASimRestrictionEveryCallIsEvaluated() = runTest {
        val evaluator = CallRuleEvaluator(
            SettingsCache(FakeSettingsRepository(Settings(blockAllCallers = true)), backgroundScope),
            FakeContactsRepository(),
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertTrue(evaluator.evaluate("0401234567", simAccountId = "sub-2").block)
        assertTrue(evaluator.evaluate("0401234567", simAccountId = null).block)
    }

    @Test
    fun aCallWithoutAKnownSimIsStillEvaluatedUnderARestriction() = runTest {
        // The account is missing on some devices and older API levels; a
        // silent pass would leave the rules off entirely there.
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(
                    Settings(blockAllCallers = true, blockingSimAccountId = "sub-1"),
                ),
                backgroundScope,
            ),
            FakeContactsRepository(),
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker().apply { grant(Manifest.permission.READ_CONTACTS) },
        )
        assertTrue(evaluator.evaluate("0401234567", simAccountId = null).block)
    }

    @Test
    fun prefixBlocksStillApplyWithoutContactsPermission() = runTest {
        val evaluator = CallRuleEvaluator(
            SettingsCache(
                FakeSettingsRepository(Settings(blockedPrefixes = setOf("0700"))),
                backgroundScope,
            ),
            FakeContactsRepository(),
            FakeCallLogRepository(),
            clock,
            FakePermissionChecker(),
        )
        assertTrue(evaluator.evaluate("0700123456").block)
    }
}
