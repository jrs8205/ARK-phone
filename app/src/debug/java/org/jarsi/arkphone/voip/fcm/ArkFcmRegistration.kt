package org.jarsi.arkphone.voip.fcm

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private var syncJob: Job? = null

    fun refresh() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            Log.i(TAG, "No Firebase configuration; push wake-up is off")
            return
        }
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            // getResult() throws on a failed task, so success is checked first.
            if (!task.isSuccessful) {
                Log.w(TAG, "FCM token unavailable", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result
            if (token.isNullOrBlank()) {
                Log.w(TAG, "FCM token blank")
                return@addOnCompleteListener
            }
            onNewToken(token)
        }
    }

    /** A rotation from Firebase; also the funnel every fresh token goes through. */
    fun onNewToken(token: String) {
        scope.launch {
            // The scope is single-threaded (Main.immediate), so the job swap
            // cannot race. A sleeping retry for the token this one replaces
            // must die with the rotation, or its late success posts the dead
            // token over the live one and the phone turns unwakeable.
            syncJob?.cancel()
            syncJob = scope.launch { syncWithRetry(token) }
        }
    }

    /**
     * A transiently failed post would otherwise leave the worker holding a
     * dead token until the next manual app open — unwakeable in the meantime.
     */
    private suspend fun syncWithRetry(token: String) {
        var delayMs = 5_000L
        repeat(4) {
            if (tokenSync.sync(token)) return
            delay(delayMs)
            delayMs *= 4
        }
        Log.w(TAG, "FCM token sync kept failing; next refresh retries")
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
