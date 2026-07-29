package org.jarsi.arkphone.data

import kotlinx.coroutines.flow.Flow
import org.jarsi.arkphone.data.model.Conversation
import org.jarsi.arkphone.data.model.Message

interface MessagesRepository {
    /** Every conversation thread, newest first. */
    fun conversations(): Flow<List<Conversation>>

    /** Messages of one thread, oldest first. */
    fun messages(threadId: Long): Flow<List<Message>>

    /**
     * Re-checks permission, starts provider observation if it just became
     * possible, and re-queries. Call after a runtime permission change.
     */
    fun refresh()

    /** Marks every unread message of the thread read. Needs the SMS role. */
    suspend fun markThreadRead(threadId: Long)

    /** Deletes the whole thread. Returns false when the delete failed. */
    suspend fun deleteThread(threadId: Long): Boolean

    /** Ids of threads whose message text contains [query]. */
    suspend fun threadIdsMatchingBody(query: String): Set<Long>
}
