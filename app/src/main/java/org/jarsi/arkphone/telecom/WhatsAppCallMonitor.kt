package org.jarsi.arkphone.telecom

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.WhatsAppCallLogRepository
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.WhatsAppCallRecord
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.util.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the lifecycle of WhatsApp call notifications into call log records:
 * a ringing notification that disappears without an ongoing call is missed,
 * one followed by an ongoing call is an answered incoming call, and an
 * ongoing call with no preceding ring is outgoing.
 */
@Singleton
class WhatsAppCallMonitor @Inject constructor(
    private val repository: WhatsAppCallLogRepository,
    private val contactsRepository: ContactsRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ArkPhone"

        /** How long after the ringing notification vanishes an ongoing call may still claim it. */
        const val ANSWER_GRACE_MILLIS = 5_000L

        /** A ringing notification whose removal event was lost is discarded after this. */
        const val STALE_RINGING_MILLIS = 600_000L
    }

    private data class Ringing(val key: String, val caller: WhatsAppCall, val startMillis: Long)

    private data class Ongoing(
        val key: String,
        val caller: WhatsAppCall,
        val startMillis: Long,
        val answered: Ringing?,
        /** When the call was confirmed connected — dialing/connecting phases don't count as talk time. */
        val establishedAtMillis: Long?,
    )

    private var ringing: Ringing? = null
    private var ongoing: Ongoing? = null
    private var missedJob: Job? = null

    internal fun onCallNotificationPosted(
        key: String,
        kind: WhatsAppCallNotificationKind,
        caller: WhatsAppCall,
        established: Boolean = false,
    ): WhatsAppCallNotificationKind? {
        when (kind) {
            WhatsAppCallNotificationKind.RINGING -> {
                // WhatsApp reuses one notification key for the whole call, and
                // a stray ringing-shaped update can arrive mid-call — it must
                // not resurrect the ringing state (or the announcement).
                if (ongoing?.key == key) return WhatsAppCallNotificationKind.ONGOING
                val current = ringing
                ringing = if (current?.key == key) {
                    current.copy(caller = caller)
                } else {
                    Ringing(key, caller, clock.nowMillis())
                }
            }
            WhatsAppCallNotificationKind.ONGOING -> {
                val current = ongoing
                if (current?.key == key) {
                    if (current.establishedAtMillis == null && established) {
                        ongoing = current.copy(establishedAtMillis = clock.nowMillis())
                    }
                    return WhatsAppCallNotificationKind.ONGOING
                }
                val answered = ringing?.takeIf {
                    clock.nowMillis() - it.startMillis < STALE_RINGING_MILLIS
                }
                missedJob?.cancel()
                ringing = null
                ongoing = Ongoing(
                    key = key,
                    caller = caller,
                    startMillis = clock.nowMillis(),
                    answered = answered,
                    establishedAtMillis = if (established) clock.nowMillis() else null,
                )
            }
        }
        return kind
    }

    internal fun onCallNotificationRemoved(key: String) {
        val call = ongoing
        if (call?.key == key) {
            ongoing = null
            if (ringing?.key == key) ringing = null
            val answered = call.answered
            val caller = answered?.caller
                ?.takeIf { it.callerName != null || it.callerNumber != null }
                ?: call.caller
            val durationSeconds = call.establishedAtMillis
                ?.let { (clock.nowMillis() - it) / 1_000 }
                ?: if (answered != null) (clock.nowMillis() - call.startMillis) / 1_000 else 0
            record(
                type = if (answered != null) CallType.INCOMING else CallType.OUTGOING,
                caller = caller,
                timestampMillis = answered?.startMillis ?: call.startMillis,
                durationSeconds = durationSeconds,
            )
            return
        }
        val ring = ringing
        if (ring?.key == key) {
            missedJob?.cancel()
            missedJob = scope.launch {
                delay(ANSWER_GRACE_MILLIS)
                if (ringing != ring) return@launch
                ringing = null
                record(CallType.MISSED, ring.caller, ring.startMillis, durationSeconds = 0)
            }
        }
    }

    private fun record(
        type: CallType,
        caller: WhatsAppCall,
        timestampMillis: Long,
        durationSeconds: Long,
    ) {
        scope.launch {
            repository.record(
                WhatsAppCallRecord(
                    callerName = caller.callerName,
                    callerNumber = caller.callerNumber ?: numberFromContacts(caller.callerName),
                    type = type,
                    timestampMillis = timestampMillis,
                    durationSeconds = durationSeconds,
                    isVideo = caller.isVideo,
                ),
            )
            Log.i(TAG, "WhatsApp call recorded: type=$type duration=${durationSeconds}s")
        }
    }

    /** WhatsApp shows a contact's name instead of the number; the number makes
     *  the log row callable and lets the detail view group it, so look it up. */
    private suspend fun numberFromContacts(name: String?): String? {
        if (name == null) return null
        return contactsRepository.contacts().first()
            .firstOrNull { it.displayName == name }
            ?.phoneNumber
    }
}
