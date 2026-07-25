package org.jarsi.arkphone.data

import android.telephony.PhoneNumberUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jarsi.arkphone.data.model.CallLogEntry
import org.jarsi.arkphone.data.model.CallSource
import org.jarsi.arkphone.data.model.CallType
import org.jarsi.arkphone.data.model.WhatsAppCallRecord
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomWhatsAppCallLogRepository @Inject constructor(
    private val dao: WhatsAppCallDao,
) : WhatsAppCallLogRepository {

    override fun calls(): Flow<List<CallLogEntry>> =
        dao.calls().map { entities -> entities.map { it.toEntry() } }

    override suspend fun record(call: WhatsAppCallRecord) {
        dao.insert(
            WhatsAppCallEntity(
                callerName = call.callerName,
                callerNumber = call.callerNumber,
                type = call.type.name,
                timestampMillis = call.timestampMillis,
                durationSeconds = call.durationSeconds,
                isVideo = call.isVideo,
            ),
        )
    }

    override suspend fun deleteCallsFor(number: String) {
        val ids = dao.callsOnce()
            .filter { it.callerNumber != null && PhoneNumberUtils.compare(it.callerNumber, number) }
            .map { it.id }
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    override suspend fun deleteCallsForName(name: String) {
        val ids = dao.callsOnce()
            .filter { it.callerNumber == null && it.callerName == name }
            .map { it.id }
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    private fun WhatsAppCallEntity.toEntry() = CallLogEntry(
        id = id,
        number = callerNumber.orEmpty(),
        displayName = callerName,
        type = runCatching { CallType.valueOf(type) }.getOrDefault(CallType.OTHER),
        timestampMillis = timestampMillis,
        durationSeconds = durationSeconds,
        source = CallSource.WHATSAPP,
    )
}
