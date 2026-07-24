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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.PermissionChecker
import javax.inject.Inject

internal fun callTypeFrom(systemType: Int): CallType = when (systemType) {
    CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
    CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
    CallLog.Calls.MISSED_TYPE -> CallType.MISSED
    CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
    CallLog.Calls.BLOCKED_TYPE -> CallType.BLOCKED
    else -> CallType.OTHER
}

internal fun callSourceFrom(phoneAccountComponent: String?): CallSource = when {
    phoneAccountComponent == null -> CallSource.PHONE
    phoneAccountComponent.contains("com.whatsapp") -> CallSource.WHATSAPP
    phoneAccountComponent.contains("telephony", ignoreCase = true) ||
        phoneAccountComponent.startsWith("com.android.phone") -> CallSource.PHONE
    else -> CallSource.OTHER
}

class SystemCallLogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissionChecker: PermissionChecker,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CallLogRepository {

    override fun callLog(): Flow<List<CallLogEntry>> {
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
                CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
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
                        source = callSourceFrom(cursor.getString(6)),
                    )
                }
            }
            return entries
        }

        return callbackFlow {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
            if (permissionChecker.has(Manifest.permission.READ_CALL_LOG)) {
                resolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            }
            send(Unit)
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.conflate().map { query() }.flowOn(ioDispatcher)
    }

    override suspend fun deleteCallsFor(number: String): Boolean = withContext(ioDispatcher) {
        if (!permissionChecker.has(Manifest.permission.WRITE_CALL_LOG)) return@withContext false
        runCatching {
            // Number formats vary (+358 40 vs 040), so matching rows are
            // resolved client-side and deleted by id.
            val ids = mutableListOf<Long>()
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (PhoneNumberUtils.compare(cursor.getString(1).orEmpty(), number)) {
                        ids += cursor.getLong(0)
                    }
                }
            }
            if (ids.isEmpty()) return@runCatching true
            val where = CallLog.Calls._ID + " IN (" + ids.joinToString(",") + ")"
            context.contentResolver.delete(CallLog.Calls.CONTENT_URI, where, null) >= 0
        }.getOrDefault(false)
    }
}
