package org.jarsi.arkphone.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.jarsi.arkphone.util.PermissionChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
class SystemMessagesRepositoryTest {

    private lateinit var provider: FakeTelephonyProvider
    private lateinit var repository: SystemMessagesRepository
    private var hasReadSms = true

    @Before
    fun setUp() {
        provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        val context = ApplicationProvider.getApplicationContext<Application>()
        // The same instance answers for the sms and mms authorities too.
        ShadowContentResolver.registerProviderInternal("sms", provider)
        ShadowContentResolver.registerProviderInternal("mms", provider)
        repository = SystemMessagesRepository(
            context = context,
            permissionChecker = object : PermissionChecker {
                override fun has(permission: String): Boolean = hasReadSms
            },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
    }

    @Test
    fun `conversations resolve recipient addresses and unread state`() = runTest {
        provider.canonicalAddresses[7L] = "+358441234567"
        provider.conversationRows += arrayOf<Any?>(3L, 1_722_000_000_000L, 5, "7", "Hei!", 0)
        val conversations = repository.conversations().first()
        assertEquals(1, conversations.size)
        with(conversations.single()) {
            assertEquals(3L, threadId)
            assertEquals(listOf("+358441234567"), addresses)
            assertEquals("Hei!", snippet)
            assertTrue(unread)
        }
    }

    @Test
    fun `group thread carries every address`() = runTest {
        provider.canonicalAddresses[1L] = "+358401111111"
        provider.canonicalAddresses[2L] = "+358402222222"
        provider.conversationRows += arrayOf<Any?>(9L, 1L, 2, "1 2", null, 1)
        assertEquals(
            listOf("+358401111111", "+358402222222"),
            repository.conversations().first().single().addresses,
        )
    }

    @Test
    fun `no permission yields empty list`() = runTest {
        hasReadSms = false
        provider.conversationRows += arrayOf<Any?>(3L, 1L, 1, "7", "x", 0)
        assertTrue(repository.conversations().first().isEmpty())
    }
}
