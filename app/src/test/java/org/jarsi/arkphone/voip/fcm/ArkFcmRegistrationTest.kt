package org.jarsi.arkphone.voip.fcm

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.data.ArkIdentity
import org.jarsi.arkphone.voip.ArkAccountClient
import org.jarsi.arkphone.voip.ArkHttpResponse
import org.jarsi.arkphone.voip.FakeArkHttp
import org.jarsi.arkphone.voip.TestArkIdentityRepository
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ArkFcmRegistrationTest {

    private val http = FakeArkHttp()
    private val identities = TestArkIdentityRepository(ArkIdentity("ARK-AAAA-AAAA", "A", "tok"))
    private val sync = FcmTokenSync(identities, ArkAccountClient(http, "https://w"))

    private fun registration(scope: CoroutineScope) =
        ArkFcmRegistration(ApplicationProvider.getApplicationContext(), sync, scope)

    @Test
    fun aFailedRotationPostIsRetried() = runTest {
        http.response = ArkHttpResponse(500, "boom")
        registration(backgroundScope).onNewToken("fcm-a")
        runCurrent()
        assertEquals(1, http.calls.size)
        http.response = ArkHttpResponse(204, "")
        advanceTimeBy(5_100)
        runCurrent()
        assertEquals(2, http.calls.size)
        assertEquals("fcm-a", identities.fcm.value)
    }

    @Test
    fun aRotationWaitsOutThePredecessorsInFlightPostBeforePostingItself() = runTest {
        val stalled = CompletableDeferred<ArkHttpResponse?>()
        http.stallNextPost = stalled
        val registration = registration(backgroundScope)
        registration.onNewToken("fcm-a")
        runCurrent()
        assertEquals(1, http.calls.size)
        // Token A's post is still on the wire — cancellation cannot recall
        // it. B must not post until A's request has finished, or the worker
        // keeps whichever token happens to land last.
        http.response = ArkHttpResponse(204, "")
        registration.onNewToken("fcm-b")
        runCurrent()
        assertEquals(1, http.calls.size)
        stalled.complete(ArkHttpResponse(204, ""))
        runCurrent()
        assertEquals(2, http.calls.size)
        assertEquals("fcm-b", identities.fcm.value)
    }

    @Test
    fun aSleepingRetryNeverOverwritesANewerToken() = runTest {
        http.response = ArkHttpResponse(500, "boom")
        val registration = registration(backgroundScope)
        registration.onNewToken("fcm-a")
        runCurrent()
        // Firebase rotates while token A's retry sleeps: the retry must die
        // with the rotation, or its late post resurrects the dead token and
        // wake pushes fail until the next refresh.
        http.response = ArkHttpResponse(204, "")
        registration.onNewToken("fcm-b")
        runCurrent()
        assertEquals("fcm-b", identities.fcm.value)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("fcm-b", identities.fcm.value)
        assertEquals(2, http.calls.size)
    }
}
