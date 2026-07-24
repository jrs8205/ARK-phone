package org.jarsi.arkphone

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.jarsi.arkphone.data.SettingsCache
import javax.inject.Inject

@HiltAndroidApp
class ArkPhoneApp : Application() {

    // Injected only to create the cache at process start: the in-call service
    // reads it synchronously the moment a call rings, and a cache created
    // lazily at that point would still hold defaults (voice-only rang the
    // ringtone because of exactly that).
    @Inject lateinit var settingsCache: SettingsCache
}
