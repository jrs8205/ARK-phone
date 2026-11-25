package org.jarsi.arkphone.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

/** Main-thread registrations for rings that Telecom cannot silence. */
@Singleton
class RingingScreenOff @Inject constructor(@ApplicationContext private val context: Context) {
    private class Watch(val silence: () -> Unit, var armed: Boolean)

    private val watches = mutableMapOf<String, Watch>()
    private var receiver: BroadcastReceiver? = null
    private val power = context.getSystemService(PowerManager::class.java)
    // A heads-up ring or a WhatsApp announcement may have no ARK window.
    // Keep an already interactive display from timing out, without waking it.
    @Suppress("DEPRECATION")
    private val screenLock by lazy {
        power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "ArkPhone:ringing").apply {
            setReferenceCounted(false)
        }
    }

    fun watch(key: String, silence: () -> Unit): Closeable {
        val watch = Watch(silence, power.isInteractive)
        watches[key] = watch
        if (receiver == null) register()
        if (watch.armed) protectFromTimeout()
        return Closeable { if (watches[key] === watch) stop(key) }
    }

    fun stop(key: String) {
        if (watches.remove(key) == null || watches.isNotEmpty()) return
        receiver?.let(context::unregisterReceiver)
        receiver = null
        if (screenLock.isHeld) screenLock.release()
    }

    private fun protectFromTimeout() {
        // Longer than the incoming ARK ring and WhatsApp announcement limits.
        screenLock.acquire(180_000L)
    }

    private fun register() {
        val listener = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (receiver !== this) return
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> if (power.isInteractive) {
                        protectFromTimeout()
                        watches.values.forEach { it.armed = true }
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        // Proximity blanking alone leaves the device interactive.
                        // If the safety timeout expired, the cause is ambiguous.
                        val ringing = watches.filterValues { it.armed }
                        if (power.isInteractive || ringing.isEmpty() || !screenLock.isHeld) return
                        Log.i("ArkPhone", "Ring silence source=screen_off")
                        for ((key, watch) in ringing) {
                            if (watches[key] !== watch) continue
                            stop(key)
                            watch.silence()
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, listener, IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
            addAction(Intent.ACTION_SCREEN_ON)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = listener
    }
}
