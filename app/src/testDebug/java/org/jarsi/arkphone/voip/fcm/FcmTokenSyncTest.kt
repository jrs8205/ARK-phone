package org.jarsi.arkphone.voip.fcm

import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.voip.ArkAccountClient
import org.jarsi.arkphone.voip.ArkHttpResponse
import org.jarsi.arkphone.voip.FakeArkHttp
import org.jarsi.arkphone.voip.TestArkIdentityRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmTokenSyncTest {

    private val http = FakeArkHttp()
    private val identities = TestArkIdentityRepository()
    private val sync = FcmTokenSync(identities, ArkAccountClient(http, "https://w"))

    @Test
    fun anUnregisteredDeviceOnlyRemembersTheTokenAsPending() = runTest {
        assertFalse(sync.sync("fcm-1"))
        assertTrue(http.calls.isEmpty())
        assertEquals("fcm-1", sync.pendingToken.value)
        // NOT the synced marker: that once made a racing registration believe
        // the worker already held a token it had never seen.
        assertNull(identities.fcm.value)
    }

    @Test
    fun aPendingTokenIsStillPostedAfterRegistration() = runTest {
        assertFalse(sync.sync("fcm-1"))
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        http.response = ArkHttpResponse(204, "")
        assertTrue(sync.sync("fcm-1"))
        assertEquals("https://w/account/fcm-token", http.calls.single().url)
        assertEquals("fcm-1", identities.fcm.value)
    }

    @Test
    fun aRegisteredDevicePostsTheTokenAndRemembersIt() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        http.response = ArkHttpResponse(204, "")
        assertTrue(sync.sync("fcm-1"))
        assertEquals("https://w/account/fcm-token", http.calls.single().url)
        assertEquals("ARK-AAAA-AAAA.tok", http.calls.single().bearer)
        assertEquals("fcm-1", identities.fcm.value)
    }

    @Test
    fun anUnchangedTokenIsNotPostedAgain() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        identities.fcm.value = "fcm-1"
        assertTrue(sync.sync("fcm-1"))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun anEmptyTokenIsNeverSent() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        assertFalse(sync.sync("   "))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun aRejectedPostIsNotRememberedAsSynced() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok")
        http.response = ArkHttpResponse(400, "Bad request")
        assertFalse(sync.sync("fcm-1"))
        assertNull(identities.fcm.value)
    }
}
