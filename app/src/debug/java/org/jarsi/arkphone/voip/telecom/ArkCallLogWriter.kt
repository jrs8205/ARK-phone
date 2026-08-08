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
 * own decline is a deliberate choice, so it is an ordinary incoming row.
 */
fun arkCallRecordOf(
    handle: VoipCallHandle,
    direction: VoipCallDirection,
    endReason: String,
    endedAtMillis: Long,
): ArkCallRecord {
    val connectedAt = handle.connectTimeMillis
    val answered = connectedAt > 0L
    val type = when {
        direction == VoipCallDirection.OUTGOING -> ArkCallType.OUTGOING
        answered -> ArkCallType.INCOMING
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

    private companion object {
        const val TAG = "ArkPhone"
    }
}
