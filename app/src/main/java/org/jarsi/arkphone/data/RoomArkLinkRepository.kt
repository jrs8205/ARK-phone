package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.jarsi.arkphone.voip.ArkLink
import org.jarsi.arkphone.voip.ArkLinkRepository
import org.jarsi.arkphone.voip.arkLinkKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomArkLinkRepository @Inject constructor(
    private val dao: ArkLinkDao,
) : ArkLinkRepository {

    override val links: Flow<List<ArkLink>> = dao.links().map { rows ->
        rows.map { row ->
            ArkLink(
                numberKey = row.numberKey,
                number = row.number,
                code = row.code,
                nickname = row.nickname,
                publicKey = row.publicKey,
                linkedAtMillis = row.linkedAtMillis,
            )
        }
    }

    override suspend fun link(
        number: String,
        code: String,
        nickname: String,
        publicKey: String,
        atMillis: Long,
    ) {
        dao.upsert(
            ArkLinkEntity(
                numberKey = arkLinkKey(number),
                number = number,
                code = code,
                nickname = nickname,
                publicKey = publicKey,
                linkedAtMillis = atMillis,
            ),
        )
    }

    override suspend fun unlink(number: String) {
        dao.delete(arkLinkKey(number))
    }
}
