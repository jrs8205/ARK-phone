package org.jarsi.arkphone.voip.fcm

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.data.DataStoreArkIdentityRepository
import org.jarsi.arkphone.testing.InMemoryPreferencesDataStore
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
    fun aPostFinishingUnderAnotherIdentityDoesNotMarkThatIdentitySynced() = runTest {
        identities.state.value = ArkIdentity("ARK-AAAA-AAAA", "A", "tok-a")
        val gate = CompletableDeferred<ArkHttpResponse?>()
        http.stallNextPost = gate
        val posting = async { sync.sync("fcm-1") }
        runCurrent()
        // A backup restore swaps the identity while A's POST is on the wire.
        identities.state.value = ArkIdentity("ARK-BBBB-BBBB", "B", "tok-b")
        gate.complete(ArkHttpResponse(204, ""))
        assertFalse(posting.await())
        // B has not posted its token yet; a marker here would stop it from ever doing so.
        assertNull(identities.fcm.value)
    }

    @Test
    fun aRestoreLandingBetweenTheSyncsReadAndItsWriteLeavesNoMarker() = runTest {
        // The identity re-read and the marker write used to be two DataStore
        // operations; a restore's edit between them left B marked as synced
        // for a token the worker only holds for A.
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreArkIdentityRepository(store)
        repository.save(ArkIdentity("ARK-AAAA-AAAA", "A", "tok-a"))
        http.response = ArkHttpResponse(204, "")
        store.beforeNextUpdate = { repository.save(ArkIdentity("ARK-BBBB-BBBB", "B", "tok-b")) }

        assertFalse(FcmTokenSync(repository, ArkAccountClient(http, "https://w")).sync("fcm-1"))

        assertEquals("ARK-AAAA-AAAA.tok-a", http.calls.single().bearer)
        assertNull(repository.syncedFcmToken.first())
    }

    @Test
    fun aMarkerLeftByAnotherAccountDoesNotCountAsSynced() = runTest {
        // The unchanged-token shortcut must compare the account too, or the
        // new identity never posts its own token.
        val store = InMemoryPreferencesDataStore()
        val repository = DataStoreArkIdentityRepository(store)
        val a = ArkIdentity("ARK-AAAA-AAAA", "A", "tok-a")
        repository.save(a)
        assertTrue(repository.markFcmTokenSynced(a, "fcm-1"))
        repository.save(ArkIdentity("ARK-BBBB-BBBB", "B", "tok-b"))
        http.response = ArkHttpResponse(204, "")

        assertTrue(FcmTokenSync(repository, ArkAccountClient(http, "https://w")).sync("fcm-1"))

        assertEquals("ARK-BBBB-BBBB.tok-b", http.calls.single().bearer)
        assertEquals("fcm-1", repository.syncedFcmToken.first())
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
