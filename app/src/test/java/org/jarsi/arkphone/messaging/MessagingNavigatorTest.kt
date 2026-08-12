package org.jarsi.arkphone.messaging

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jarsi.arkphone.data.FakeTelephonyProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ContentProviderController

@RunWith(RobolectricTestRunner::class)
class MessagingNavigatorTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var provider: FakeTelephonyProvider
    private lateinit var navigator: MessagingNavigator

    @Before
    fun setUp() {
        provider = ContentProviderController.of(FakeTelephonyProvider())
            .create("mms-sms").get()
        navigator = MessagingNavigator(
            appContext = context,
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `display formatted recipients resolve the thread the send side uses`() {
        navigator.openConversation(context, listOf("+358 44 5552841", "0443342131"))

        assertEquals(
            listOf(listOf("+358445552841", "0443342131")),
            provider.threadLookups,
        )
    }
}
