package org.jarsi.arkphone.telecom

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.SettingsRepository
import org.jarsi.arkphone.data.model.AnnounceMode
import org.jarsi.arkphone.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/** Environment check for the announcement: silent/vibrate and Do Not Disturb suppress it. */
fun interface AnnounceGate {
    fun canAnnounce(): Boolean
}

class AndroidAnnounceGate @Inject constructor(
    @ApplicationContext private val context: Context,
) : AnnounceGate {
    override fun canAnnounce(): Boolean {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return false
        val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return false
        return audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
    }
}

/**
 * Speaks the caller's name while a call is ringing. WITH_RINGTONE speaks
 * twice (immediately and at [REPEAT_DELAY_MILLIS]); VOICE_ONLY keeps
 * repeating at the user-set interval until ringing stops.
 */
@Singleton
class CallerAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val contactsRepository: ContactsRepository,
    private val speechEngine: SpeechEngine,
    private val announceGate: AnnounceGate,
    @ApplicationScope private val scope: CoroutineScope,
) {
    companion object {
        const val REPEAT_DELAY_MILLIS = 8_000L

        /** Stops the WhatsApp repeat loop even if a removal event is lost. */
        const val WHATSAPP_ANNOUNCE_WINDOW_MILLIS = 120_000L
    }

    private var ringingCallId: String? = null
    private var job: Job? = null
    private var whatsAppKey: String? = null
    private var whatsAppJob: Job? = null

    fun onRinging(info: CallInfo) {
        if (ringingCallId == info.id) return
        ringingCallId = info.id
        job?.cancel()
        job = scope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.announceMode == AnnounceMode.OFF) return@launch
            if (!announceGate.canAnnounce()) return@launch
            val name = info.displayName?.takeIf { it.isNotBlank() }
                ?: info.number?.let { number ->
                    contactsRepository.lookupContact(number)?.displayName?.takeIf { it.isNotBlank() }
                }
            val text = if (name != null) {
                context.getString(R.string.announce_caller, name)
            } else {
                context.getString(R.string.announce_unknown_caller)
            }
            when (settings.announceMode) {
                AnnounceMode.WITH_RINGTONE -> {
                    speechEngine.speak(text)
                    delay(REPEAT_DELAY_MILLIS)
                    speechEngine.speak(text)
                }
                AnnounceMode.VOICE_ONLY -> {
                    while (true) {
                        speechEngine.speak(text)
                        delay(settings.announceIntervalSeconds * 1_000L)
                    }
                }
                AnnounceMode.OFF -> Unit
            }
        }
    }

    fun onRingingStopped(callId: String) {
        if (ringingCallId != callId) return
        ringingCallId = null
        job?.cancel()
        speechEngine.stop()
    }

    fun onWhatsAppRinging(notificationKey: String, callerName: String?, callerNumber: String? = null) {
        if (whatsAppKey == notificationKey) return
        whatsAppKey = notificationKey
        whatsAppJob?.cancel()
        whatsAppJob = scope.launch {
            val settings = settingsRepository.settings.first()
            if (!settings.announceWhatsApp) return@launch
            if (!announceGate.canAnnounce()) return@launch
            val name = callerName
                ?: callerNumber?.let { number ->
                    contactsRepository.lookupContact(number)?.displayName?.takeIf { it.isNotBlank() }
                }
                ?: callerNumber
            val text = if (name != null) {
                context.getString(R.string.announce_whatsapp_caller, name)
            } else {
                context.getString(R.string.announce_whatsapp_unknown_caller)
            }
            val intervalMillis = settings.announceIntervalSeconds * 1_000L
            var elapsedMillis = 0L
            while (elapsedMillis <= WHATSAPP_ANNOUNCE_WINDOW_MILLIS) {
                speechEngine.speak(text)
                delay(intervalMillis)
                elapsedMillis += intervalMillis
            }
        }
    }

    fun onWhatsAppRingingStopped(notificationKey: String) {
        if (whatsAppKey != notificationKey) return
        whatsAppKey = null
        whatsAppJob?.cancel()
        speechEngine.stop()
    }
}
