package org.jarsi.arkphone.voip.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks Firebase for the current registration token and hands it to the worker.
 * A checkout without google-services.json has no initialized FirebaseApp, so
 * this is a no-op there rather than a crash.
 */
@Singleton
class ArkFcmRegistration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenSync: FcmTokenSync,
    @ApplicationScope private val scope: CoroutineScope,
) {
    fun refresh() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.i(TAG, "No Firebase configuration; push wake-up is off")
            return
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            val token = task.result
            if (!task.isSuccessful || token.isNullOrBlank()) {
                Log.w(TAG, "FCM token unavailable", task.exception)
                return@addOnCompleteListener
            }
            scope.launch { tokenSync.sync(token) }
        }
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
