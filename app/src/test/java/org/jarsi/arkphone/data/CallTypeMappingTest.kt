package org.jarsi.arkphone.data

import android.provider.CallLog
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.junit.Assert.assertEquals
import org.junit.Test

class CallTypeMappingTest {

    @Test
    fun mapsKnownTypes() {
        assertEquals(CallType.INCOMING, callTypeFrom(CallLog.Calls.INCOMING_TYPE))
        assertEquals(CallType.OUTGOING, callTypeFrom(CallLog.Calls.OUTGOING_TYPE))
        assertEquals(CallType.MISSED, callTypeFrom(CallLog.Calls.MISSED_TYPE))
        assertEquals(CallType.REJECTED, callTypeFrom(CallLog.Calls.REJECTED_TYPE))
        assertEquals(CallType.BLOCKED, callTypeFrom(CallLog.Calls.BLOCKED_TYPE))
    }

    @Test
    fun mapsUnknownTypesToOther() {
        assertEquals(CallType.OTHER, callTypeFrom(99))
    }

    @Test
    fun derivesTheWhatsAppVariantFromThePhoneAccountComponent() {
        assertEquals(
            "com.whatsapp",
            whatsAppPackageFrom("com.whatsapp/com.whatsapp.voipcalling.CallConnectionService"),
        )
        assertEquals(
            "com.whatsapp.w4b",
            whatsAppPackageFrom("com.whatsapp.w4b/com.whatsapp.voipcalling.CallConnectionService"),
        )
        assertEquals(null, whatsAppPackageFrom(null))
        assertEquals(null, whatsAppPackageFrom("org.telegram.messenger/x.CallService"))
    }

    @Test
    fun mapsPhoneAccountComponentsToSources() {
        assertEquals(CallSource.PHONE, callSourceFrom(null))
        assertEquals(
            CallSource.PHONE,
            callSourceFrom("com.android.phone/com.android.services.telephony.TelephonyConnectionService"),
        )
        assertEquals(
            CallSource.WHATSAPP,
            callSourceFrom("com.whatsapp/com.whatsapp.voipcalling.CallConnectionService"),
        )
        assertEquals(
            CallSource.WHATSAPP,
            callSourceFrom("com.whatsapp.w4b/com.whatsapp.voipcalling.CallConnectionService"),
        )
        assertEquals(CallSource.OTHER, callSourceFrom("org.telegram.messenger/x.CallService"))
    }
}
