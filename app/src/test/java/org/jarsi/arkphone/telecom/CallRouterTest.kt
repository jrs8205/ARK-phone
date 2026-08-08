package org.jarsi.arkphone.telecom

import android.Manifest
import android.net.Uri
import android.telecom.PhoneAccountHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.data.model.Settings
import org.jarsi.arkphone.testing.FakeArkLinkRepository
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.jarsi.arkphone.testing.FakeSettingsRepository
import org.jarsi.arkphone.testing.FakeSimAccountRepository
import org.jarsi.arkphone.testing.FakeVoipCallGateway
import org.jarsi.arkphone.voip.ArkLinkCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Optional

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class CallRouterTest {

    private val placed = mutableListOf<Pair<Uri, PhoneAccountHandle?>>()
    private val links = FakeArkLinkRepository()
    private val gateway = FakeVoipCallGateway()

    private suspend fun router(
        settings: Settings = Settings(),
        withGateway: Boolean = true,
        scope: CoroutineScope,
    ): CallRouter {
        val settingsCache = SettingsCache(FakeSettingsRepository(settings), scope)
        settingsCache.await()
        val linkCache = ArkLinkCache(links, scope)
        linkCache.await()
        val phoneCaller = PhoneCaller(
            permissionChecker = FakePermissionChecker().apply {
                grant(Manifest.permission.CALL_PHONE)
            },
            simAccountRepository = FakeSimAccountRepository(),
            settingsCache = settingsCache,
            callPlacer = { uri, accountHandle -> placed += uri to accountHandle },
        )
        return CallRouter(
            phoneCaller = phoneCaller,
            settingsCache = settingsCache,
            linkCache = linkCache,
            voipCallGateway = if (withGateway) Optional.of(gateway) else Optional.empty(),
        )
    }

    private suspend fun givenLink() {
        links.link("+358 44 5552841", "ARK-BBBB-BBBB", "Jarsi", "pk", 1_000L)
    }

    @Test
    fun anUnlinkedNumberGoesStraightToTheCarrier() = runTest {
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("+358 40 1112223"))
        assertEquals("+358 40 1112223", placed.single().first.schemeSpecificPart)
        assertTrue(gateway.started.isEmpty())
    }

    @Test
    fun aLinkedNumberIsHandedToTheVoipGateway() = runTest {
        givenLink()
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("044 555 2841"))
        assertEquals("ARK-BBBB-BBBB", gateway.started.single().code)
        assertTrue(placed.isEmpty())
    }

    @Test
    fun theGatewaysFallbackPlacesTheCarrierCall() = runTest {
        givenLink()
        val router = router(scope = backgroundScope)
        router.placeCall("044 555 2841")
        gateway.lastFallback!!()
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun aRefusedVoipAttemptBecomesACarrierCallImmediately() = runTest {
        givenLink()
        gateway.accept = false
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("044 555 2841"))
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun aThrowingGatewayStillPlacesAPhoneCall() = runTest {
        givenLink()
        gateway.throwOnStart = true
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("044 555 2841"))
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun theMasterSwitchKeepsLinksButStopsRouting() = runTest {
        givenLink()
        val router = router(
            settings = Settings(arkInternetCallsEnabled = false),
            scope = backgroundScope,
        )
        assertTrue(router.placeCall("044 555 2841"))
        assertTrue(gateway.started.isEmpty())
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun aBuildWithoutTheEngineRoutesEverythingToTheCarrier() = runTest {
        givenLink()
        val router = router(withGateway = false, scope = backgroundScope)
        assertTrue(router.placeCall("044 555 2841"))
        assertEquals("044 555 2841", placed.single().first.schemeSpecificPart)
    }

    @Test
    fun emergencyAndUssdNumbersAreUntouched() = runTest {
        givenLink()
        val router = router(scope = backgroundScope)
        assertTrue(router.placeCall("112"))
        assertTrue(router.placeCall("*#06#"))
        assertTrue(gateway.started.isEmpty())
        assertEquals(listOf("112", "*#06#"), placed.map { it.first.schemeSpecificPart })
    }

    @Test
    fun aBlankNumberIsStillNotCalled() = runTest {
        val router = router(scope = backgroundScope)
        assertFalse(router.placeCall("  "))
        assertTrue(placed.isEmpty())
    }

    @Test
    fun voicemailNeverGoesOverTheInternet() = runTest {
        givenLink()
        val router = router(scope = backgroundScope)
        assertTrue(router.placeVoicemailCall())
        assertEquals("voicemail", placed.single().first.scheme)
        assertTrue(gateway.started.isEmpty())
    }
}
