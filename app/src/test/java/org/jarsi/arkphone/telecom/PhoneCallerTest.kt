package org.jarsi.arkphone.telecom

import android.Manifest
import android.content.ComponentName
import android.net.Uri
import android.telecom.PhoneAccountHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.data.model.SimAccount
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.jarsi.arkphone.testing.FakeSimAccountRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PhoneCallerTest {

    private val placed = mutableListOf<Pair<Uri, PhoneAccountHandle?>>()
    private val simAccounts = FakeSimAccountRepository()

    private fun sim(id: String, label: String) {
        simAccounts.accounts += SimAccount(id, label, null)
        simAccounts.handles[id] =
            PhoneAccountHandle(ComponentName("com.android.phone", "TelephonyService"), id)
    }

    /** PhoneCaller reads the cache synchronously, so warm it first — awaiting
     *  is what lets the cache's collector run on the test scheduler. */
    private suspend fun caller(
        settings: Settings = Settings(),
        permissions: FakePermissionChecker = FakePermissionChecker().apply {
            grant(Manifest.permission.CALL_PHONE)
        },
        scope: CoroutineScope,
    ): PhoneCaller {
        val cache = SettingsCache(FakeSettingsRepository(settings), scope)
        cache.await()
        return PhoneCaller(
            permissionChecker = permissions,
            simAccountRepository = simAccounts,
            settingsCache = cache,
            callPlacer = { uri, accountHandle -> placed += uri to accountHandle },
        )
    }

    @Test
    fun withoutAChosenSimTheCallGoesOutOnTheSystemDefault() = runTest {
        assertTrue(caller(scope = backgroundScope).placeCall("0401234567"))
        assertEquals("0401234567", placed.single().first.schemeSpecificPart)
        assertNull(placed.single().second)
    }

    @Test
    fun theChosenSimIsUsedForTheCall() = runTest {
        sim("sub-1", "DNA")
        sim("sub-2", "Elisa")
        val caller = caller(Settings(callSimAccountId = "sub-2"), scope = backgroundScope)
        assertTrue(caller.placeCall("0401234567"))
        assertEquals("sub-2", placed.single().second?.id)
    }

    @Test
    fun aRemovedSimFallsBackToTheSystemDefault() = runTest {
        sim("sub-1", "DNA")
        val caller = caller(Settings(callSimAccountId = "sub-9"), scope = backgroundScope)
        assertTrue(caller.placeCall("0401234567"))
        assertNull(placed.single().second)
    }

    @Test
    fun aBlankNumberIsNotCalled() = runTest {
        assertFalse(caller(scope = backgroundScope).placeCall("  "))
        assertTrue(placed.isEmpty())
    }

    @Test
    fun withoutTheCallPermissionNothingIsPlaced() = runTest {
        val caller = caller(permissions = FakePermissionChecker(), scope = backgroundScope)
        assertFalse(caller.placeCall("0401234567"))
        assertTrue(placed.isEmpty())
    }

    @Test
    fun voicemailIsAlsoPlacedOnTheChosenSim() = runTest {
        sim("sub-1", "DNA")
        sim("sub-2", "Elisa")
        val caller = caller(Settings(callSimAccountId = "sub-1"), scope = backgroundScope)
        assertTrue(caller.placeVoicemailCall())
        assertEquals("voicemail", placed.single().first.scheme)
        assertEquals("sub-1", placed.single().second?.id)
    }
}
