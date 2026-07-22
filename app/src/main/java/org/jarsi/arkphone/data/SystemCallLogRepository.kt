package org.jarsi.arkphone.data

import android.Manifest
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

internal fun callTypeFrom(systemType: Int): CallType = when (systemType) {
    CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
    CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
    CallLog.Calls.MISSED_TYPE -> CallType.MISSED
    CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
    else -> CallType.OTHER
}

class SystemCallLogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CallLogRepository {

    override fun callLog(): Flow<List<CallLogEntry>> = callbackFlow {
        val resolver = context.contentResolver
        fun query(): List<CallLogEntry> {
            if (!permissionChecker.has(Manifest.permission.READ_CALL_LOG)) return emptyList()
            val projection = arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
            )
            val entries = mutableListOf<CallLogEntry>()
            resolver.query(
                CallLog.Calls.CONTENT_URI, projection, null, null,
                CallLog.Calls.DATE + " DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    entries += CallLogEntry(
                        id = cursor.getLong(0),
                        number = cursor.getString(1).orEmpty(),
                        displayName = cursor.getString(2)?.takeIf { it.isNotBlank() },
                        type = callTypeFrom(cursor.getInt(3)),
                        timestampMillis = cursor.getLong(4),
                        durationSeconds = cursor.getLong(5),
                    )
                }
            }
            return entries
        }

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(query())
            }
        }
        if (permissionChecker.has(Manifest.permission.READ_CALL_LOG)) {
            resolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
        }
        trySend(query())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(ioDispatcher)
}
