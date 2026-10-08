package org.jarsi.arkphone.backup

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.voip.ArkCallAdmission
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finishes at process start a restore the last process did not get to
 * finish ([BackupStore.recoverInterruptedRestore]). ARK calls are held off
 * meanwhile: the identity in the preferences is already the restored one
 * while the tables, and the link cache built from them, may still be the
 * old ones — a call would be checked against the wrong links.
 */
@Singleton
class BackupRecoveryStartup @Inject constructor(
    private val store: BackupStore,
    private val admission: ArkCallAdmission,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Call on the main thread, before the VoIP engine starts. */
    fun onAppStart() {
        val held = admission.holdForRestore()
        scope.launch {
            try {
                store.recoverInterruptedRestore()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "ARK restore recovery failed", e)
            } finally {
                if (held) withContext(NonCancellable) { admission.releaseRestoreHold() }
            }
        }
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
