package org.jarsi.arkphone.voip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Brings the VoIP engine up without anyone opening the app. A sideloaded
 * update leaves the app force-stopped, and a force-stopped app receives no
 * FCM — every hand-installed build silently lost its wake path until the
 * user happened to open it once (field-hit 2026-08-09 18:10, and the whole
 * 2026-08-08 night before it). The system delivers MY_PACKAGE_REPLACED to
 * the new version right after an update, and running this receiver both
 * clears the stopped state and reconnects the inbox. BOOT_COMPLETED does
 * the same after a reboot.
 */
@AndroidEntryPoint
class ArkPackageEventReceiver : BroadcastReceiver() {

    @Inject lateinit var startup: VoipStartup

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_BOOT_COMPLETED -> {
                Log.i(TAG, "Package event ${intent.action}; starting the VoIP engine")
                startup.onAppStart()
            }
        }
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
