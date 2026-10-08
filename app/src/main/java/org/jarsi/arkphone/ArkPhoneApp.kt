package org.jarsi.arkphone

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jarsi.arkphone.backup.BackupStore
import org.jarsi.arkphone.data.BlockedNumbersMigration
import org.jarsi.arkphone.data.SettingsCache
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.telecom.CallNotifications
import org.jarsi.arkphone.voip.VoipStartup
import java.util.Optional
import javax.inject.Inject

@HiltAndroidApp
class ArkPhoneApp : Application() {

    // Injected only to create the cache at process start: the in-call service
    // reads it synchronously the moment a call rings, and a cache created
    // lazily at that point would still hold defaults (voice-only rang the
    // ringtone because of exactly that).
    @Inject lateinit var settingsCache: SettingsCache

    @Inject lateinit var callNotifications: CallNotifications

    @Inject lateinit var blockedNumbersMigration: BlockedNumbersMigration

    @Inject lateinit var backupStore: BackupStore

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    @Inject lateinit var voipStartup: Optional<VoipStartup>

    override fun onCreate() {
        super.onCreate()
        // Also done when a call arrives, but doing it at start means a channel
        // definition that changed in an update takes effect before the first
        // call rather than during it.
        callNotifications.ensureChannels()
        appScope.launch { blockedNumbersMigration.migrate() }
        // A restore the last process did not get to finish (its preferences
        // committed, its tables did not) is finished before anything reads
        // the tables as current.
        appScope.launch {
            runCatching { backupStore.recoverInterruptedRestore() }
                .onFailure { Log.w(TAG, "ARK restore recovery failed", it) }
        }
        // Empty in release: no engine, no socket, no push.
        voipStartup.ifPresent(VoipStartup::onAppStart)
    }

    private companion object {
        const val TAG = "ArkPhone"
    }
}
