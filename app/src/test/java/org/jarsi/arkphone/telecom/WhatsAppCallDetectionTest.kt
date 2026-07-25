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
        text: String? = null,
        callType: Int? = 1,
        fullScreen: Boolean = false,
    ): Notification {
        val builder = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(title)
        text?.let { builder.setContentText(it) }
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
    fun accountTitleYieldsTheCallerNumberFromTheText() {
        val call = whatsAppIncomingCall(
            "com.whatsapp",
            notification(
                title = "[ +358 45 76387898 ]",
                text = "Äänipuhelu henkilöltä +358 44 5552841",
                callType = null,
            ),
            tag = "0045E9B0ringing_call3c30ab12",
        )
        assertNull(call?.callerName)
        assertEquals("+358 44 5552841", call?.callerNumber)
    }

    @Test
    fun accountTitleYieldsTheCallerNameFromTheText() {
        val call = whatsAppIncomingCall(
            "com.whatsapp",
            notification(
                title = "[ +358 45 76387898 ]",
                text = "Videopuhelu henkilöltä Matti Meikäläinen",
                callType = null,
            ),
            tag = "ringing_call",
        )
        assertEquals("Matti Meikäläinen", call?.callerName)
        assertNull(call?.callerNumber)
    }

    @Test
    fun englishCallerTextIsRecognizedToo() {
        val call = whatsAppIncomingCall(
            "com.whatsapp",
            notification(title = "[ +358 45 76387898 ]", text = "Incoming voice call from Bob"),
        )
        assertEquals("Bob", call?.callerName)
    }

    @Test
    fun accountTitleWithoutACallerInTheTextMeansAnUnknownCaller() {
        val call = whatsAppIncomingCall(
            "com.whatsapp",
            notification(title = "[ +358 45 76387898 ]", text = null),
        )
        assertEquals(true, call != null)
        assertNull(call?.callerName)
        assertNull(call?.callerNumber)
    }

    @Test
    fun blankTitleMeansAnUnknownCaller() {
        val call = whatsAppIncomingCall("com.whatsapp.w4b", notification(title = " "))
        assertEquals(null, call?.callerName)
        assertEquals(true, call != null)
    }

    @Test
    fun ringingTagClassifiesAsRinging() {
        assertEquals(
            WhatsAppCallNotificationKind.RINGING,
            classifyWhatsAppCallNotification(
                "com.whatsapp",
                notification(callType = null),
                "0045E9B0ringing_call3c30ab12",
            ),
        )
    }

    @Test
    fun missedCallSummaryIsNotALiveCall() {
        assertNull(
            classifyWhatsAppCallNotification(
                "com.whatsapp",
                notification(callType = null),
                "missed_calls3c30ab122598758f",
            ),
        )
    }

    @Test
    fun otherCallNotificationsClassifyAsOngoing() {
        assertEquals(
            WhatsAppCallNotificationKind.ONGOING,
            classifyWhatsAppCallNotification(
                "com.whatsapp",
                notification(callType = null),
                "0099call_in_progress",
            ),
        )
    }

    @Test
    fun callStyleTypesClassifyDirectly() {
        assertEquals(
            WhatsAppCallNotificationKind.RINGING,
            classifyWhatsAppCallNotification("com.whatsapp", notification(callType = 1), null),
        )
        assertEquals(
            WhatsAppCallNotificationKind.ONGOING,
            classifyWhatsAppCallNotification("com.whatsapp", notification(callType = 2), null),
        )
    }

    @Test
    fun otherPackagesAndCategoriesDoNotClassify() {
        assertNull(
            classifyWhatsAppCallNotification(
                "org.telegram.messenger",
                notification(),
                "ringing_call",
            ),
        )
        assertNull(
            classifyWhatsAppCallNotification(
                "com.whatsapp",
                notification(category = null),
                "ringing_call",
            ),
        )
    }

    @Test
    fun videoCallTextMarksTheCallAsVideo() {
        val call = whatsAppCaller(
            notification(
                title = "[ +358 45 76387898 ]",
                text = "Videopuhelu henkilöltä Matti Meikäläinen",
            ),
        )
        assertEquals(true, call.isVideo)
        assertEquals("Matti Meikäläinen", call.callerName)
    }

    @Test
    fun voiceCallIsNotVideo() {
        val call = whatsAppCaller(notification(text = "Äänipuhelu henkilöltä Jarsi"))
        assertEquals(false, call.isVideo)
        assertEquals("Jarsi", call.callerName)
    }
}
