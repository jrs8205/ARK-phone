package org.jarsi.arkphone.telecom

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val screenOff: RingingScreenOff,
) {
    companion object {
        const val REPEAT_DELAY_MILLIS = 8_000L

        /** Stops the WhatsApp repeat loop even if a removal event is lost. */
        const val WHATSAPP_ANNOUNCE_WINDOW_MILLIS = 120_000L
    }

    // The speech engine flushes the previous utterance. A silence request
    // for an overlapping ring must only stop the utterance it still owns.
    private var speechOwner: String? = null

    private fun speak(owner: String, text: String) {
        speechOwner = owner
        speechEngine.speak(text)
    }

    private fun stopSpeech(owner: String) {
        if (speechOwner != owner) return
        speechOwner = null
        speechEngine.stop()
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
                    speak(info.id, text)
                    delay(REPEAT_DELAY_MILLIS)
                    speak(info.id, text)
                }
                AnnounceMode.VOICE_ONLY -> {
                    while (true) {
                        speak(info.id, text)
                        delay(settings.announceIntervalSeconds * 1_000L)
                    }
                }
                AnnounceMode.OFF -> Unit
            }
        }
    }

    fun onRingingStopped(callId: String) {
        if (ringingCallId == callId) {
            ringingCallId = null
            job?.cancel()
        }
        stopSpeech(callId)
    }

    fun onWhatsAppRinging(notificationKey: String, callerName: String?, callerNumber: String? = null) {
        if (whatsAppKey == notificationKey) return
        whatsAppKey = notificationKey
        whatsAppJob?.cancel()
        whatsAppJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val announcement = coroutineContext.job
            val owner = "whatsapp:$notificationKey"
            screenOff.watch(owner) {
                // Keep the key until removal: WhatsApp can refresh it during
                // the same silent ring. Cancel pending lookup and repeats too.
                announcement.cancel()
                stopSpeech(owner)
            }.use {
                withTimeoutOrNull(WHATSAPP_ANNOUNCE_WINDOW_MILLIS) {
                    val settings = settingsRepository.settings.first()
                    if (!settings.announceWhatsApp || !announceGate.canAnnounce()) return@withTimeoutOrNull
                    val name = callerName ?: callerNumber?.let {
                        contactsRepository.lookupContact(it)?.displayName?.takeIf(String::isNotBlank)
                    } ?: callerNumber
                    val text = if (name != null) {
                        context.getString(R.string.announce_whatsapp_caller, name)
                    } else {
                        context.getString(R.string.announce_whatsapp_unknown_caller)
                    }
                    while (true) {
                        speak(owner, text)
                        delay(settings.announceIntervalSeconds * 1_000L)
                    }
                }
                // The last utterance can still be playing when the window ends.
                stopSpeech(owner)
            }
        }
    }

    fun onWhatsAppListenerDisconnected() {
        whatsAppKey?.let(::onWhatsAppRingingStopped)
    }

    fun onWhatsAppRingingStopped(notificationKey: String) {
        if (whatsAppKey != notificationKey) return
        whatsAppKey = null
        whatsAppJob?.cancel()
        whatsAppJob = null
        stopSpeech("whatsapp:$notificationKey")
    }
}
