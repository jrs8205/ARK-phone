package org.jarsi.arkphone.data

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.testing.FakePermissionChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SystemSimRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun emptyWithoutReadPhoneStatePermission() = runTest {
        val repository = SystemSimRepository(
            context,
            FakePermissionChecker(),
            UnconfinedTestDispatcher(testScheduler),
        )
        assertTrue(repository.activeSims().isEmpty())
    }

    @Test
    fun mapsActiveSubscriptions() = runTest {
        val permissions = FakePermissionChecker().apply {
            grant(Manifest.permission.READ_PHONE_STATE)
        }
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val info = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(3)
            .setDisplayName("Work SIM")
            .buildSubscriptionInfo()
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(info))
        val sims = SystemSimRepository(
            context,
            permissions,
            UnconfinedTestDispatcher(testScheduler),
        ).activeSims()
        assertEquals(1, sims.size)
        assertEquals(3, sims.single().subscriptionId)
        assertEquals("Work SIM", sims.single().displayName)
    }
}
