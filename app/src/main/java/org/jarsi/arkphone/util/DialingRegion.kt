package org.jarsi.arkphone.util

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** The region numbers are dialed in, as an upper-case ISO country code. */
fun interface DialingRegion {
    fun countryIso(): String
}

/** The SIM's country first — the device language is routinely English on a
 *  Finnish phone, and a wrong region makes libphonenumber reject every
 *  national-format number — then the network's, then the locale's. */
fun androidDialingRegion(context: Context): DialingRegion = DialingRegion {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    (
        telephony?.simCountryIso?.takeIf { it.isNotBlank() }
            ?: telephony?.networkCountryIso?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().country
        ).uppercase(Locale.US)
}
