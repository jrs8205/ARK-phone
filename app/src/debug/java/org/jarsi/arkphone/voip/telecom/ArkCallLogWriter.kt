package org.jarsi.arkphone.voip.telecom

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import org.jarsi.arkphone.data.model.ARK_PHONE_ACCOUNT_ID
import javax.inject.Inject
import javax.inject.Singleton

enum class ArkCallType { INCOMING, OUTGOING, MISSED }

data class ArkCallRecord(
    val number: String?,
    val displayName: String?,
    val type: ArkCallType,
    val startedAtMillis: Long,
    val durationSeconds: Long,
)

/**
 * A call that was never answered is missed on the receiving side; the caller's
 * own decline is a deliberate choice, so it is an ordinary incoming row. An
 * answer whose media then failed is no less deliberate — the user pressed
 * Answer, so a missed-call row and its notification would call them a liar.
 */
fun arkCallRecordOf(
    handle: VoipCallHandle,
    direction: VoipCallDirection,
    endReason: String,
    endedAtMillis: Long,
    answeredByUser: Boolean = false,
): ArkCallRecord {
    val connectedAt = handle.connectTimeMillis
    val answered = connectedAt > 0L
    val type = when {
        direction == VoipCallDirection.OUTGOING -> ArkCallType.OUTGOING
        answered || answeredByUser -> ArkCallType.INCOMING
        endReason == LOCAL_REJECT -> ArkCallType.INCOMING
        else -> ArkCallType.MISSED
    }
    return ArkCallRecord(
        number = handle.number,
        displayName = handle.displayName,
        type = type,
        startedAtMillis = if (answered) connectedAt else endedAtMillis,
        durationSeconds = if (answered) (endedAtMillis - connectedAt) / 1_000L else 0L,
    )
}

private const val LOCAL_REJECT = "local-reject"

interface ArkCallLog {
    fun record(record: ArkCallRecord)

    /** Unread missed calls across the whole log, for the notification tally. */
    fun unreadMissedCount(): Int
}

@Singleton
class SystemArkCallLog @Inject constructor(
    @ApplicationContext private val context: Context,
) : ArkCallLog {

    // ARK holds WRITE_CALL_LOG as the default dialer; runCatching covers the
    // window where the role has been taken away.
    @SuppressLint("MissingPermission")
    override fun record(record: ArkCallRecord) {
        val values = ContentValues().apply {
            put(CallLog.Calls.NUMBER, record.number.orEmpty())
            put(CallLog.Calls.CACHED_NAME, record.displayName)
            put(
                CallLog.Calls.TYPE,
                when (record.type) {
                    ArkCallType.INCOMING -> CallLog.Calls.INCOMING_TYPE
                    ArkCallType.OUTGOING -> CallLog.Calls.OUTGOING_TYPE
                    ArkCallType.MISSED -> CallLog.Calls.MISSED_TYPE
                },
            )
            put(CallLog.Calls.DATE, record.startedAtMillis)
            put(CallLog.Calls.DURATION, record.durationSeconds)
            put(CallLog.Calls.NEW, if (record.type == ArkCallType.MISSED) 1 else 0)
            put(CallLog.Calls.IS_READ, if (record.type == ArkCallType.MISSED) 0 else 1)
            // The marker that says this row was an ARK internet call.
            put(CallLog.Calls.PHONE_ACCOUNT_ID, ARK_PHONE_ACCOUNT_ID)
        }
        runCatching { context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values) }
            .onFailure { Log.w(TAG, "ARK call not written to the call log", it) }
    }

    // Carrier and ARK rows both count: the notification tallies what the user
    // has not seen, not which network the calls came over.
    @SuppressLint("MissingPermission")
    override fun unreadMissedCount(): Int = runCatching {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls.IS_READ} = 0",
            arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
            null,
        )?.use { it.count } ?: 0
    }.getOrDefault(0)

    private companion object {
        const val TAG = "ArkPhone"
    }
}
