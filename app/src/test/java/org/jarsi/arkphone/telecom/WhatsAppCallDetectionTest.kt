package org.jarsi.arkphone.telecom

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WhatsAppCallDetectionTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun notification(
        category: String? = Notification.CATEGORY_CALL,
        title: String? = "Jarsi",
        callType: Int? = 1,
        fullScreen: Boolean = false,
    ): Notification {
        val builder = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
        category?.let { builder.setCategory(it) }
        callType?.let { builder.extras.putInt("android.callType", it) }
        if (fullScreen) {
            builder.setFullScreenIntent(
                PendingIntent.getActivity(
                    context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE,
                ),
                true,
            )
        }
        return builder.build().also { built ->
            callType?.let { built.extras.putInt("android.callType", it) }
        }
    }

    @Test
    fun recognizesAnIncomingWhatsAppCallAndItsCaller() {
        val call = whatsAppIncomingCall("com.whatsapp", notification())
        assertEquals("Jarsi", call?.callerName)
    }

    @Test
    fun otherPackagesAreIgnored() {
        assertNull(whatsAppIncomingCall("org.telegram.messenger", notification()))
    }

    @Test
    fun nonCallNotificationsAreIgnored() {
        assertNull(whatsAppIncomingCall("com.whatsapp", notification(category = null)))
    }

    @Test
    fun ongoingCallsAreIgnored() {
        assertNull(whatsAppIncomingCall("com.whatsapp", notification(callType = 2)))
    }

    @Test
    fun legacyFormatNeedsAFullScreenIntent() {
        assertNull(whatsAppIncomingCall("com.whatsapp", notification(callType = null)))
        val call = whatsAppIncomingCall(
            "com.whatsapp",
            notification(callType = null, fullScreen = true),
        )
        assertEquals("Jarsi", call?.callerName)
    }

    @Test
    fun plainRingingNotificationIsRecognizedByItsTag() {
        val call = whatsAppIncomingCall(
            "com.whatsapp",
            notification(callType = null),
            tag = "ringing_call_notification:abc@s.whatsapp.net",
        )
        assertEquals("Jarsi", call?.callerName)
    }

    @Test
    fun unrelatedTagsDoNotMarkACallIncoming() {
        assertNull(
            whatsAppIncomingCall(
                "com.whatsapp",
                notification(callType = null),
                tag = "call_notification_group",
            ),
        )
    }

    @Test
    fun ongoingCallTypeWinsOverARingingTag() {
        assertNull(
            whatsAppIncomingCall(
                "com.whatsapp",
                notification(callType = 2),
                tag = "ringing_call_notification",
            ),
        )
    }

    @Test
    fun blankTitleMeansAnUnknownCaller() {
        val call = whatsAppIncomingCall("com.whatsapp.w4b", notification(title = " "))
        assertEquals(null, call?.callerName)
        assertEquals(true, call != null)
    }
}
