package org.jarsi.arkphone.data

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.di.IoDispatcher
import javax.inject.Inject

interface BlockedNumbersRepository {
    /** False also when this user cannot manage the block list at all. */
    suspend fun canBlock(): Boolean
    suspend fun isBlocked(number: String): Boolean
    suspend fun block(number: String): Boolean
    suspend fun unblock(number: String): Boolean
}

/**
 * Android's system block list. The default dialer may read and write it
 * without extra permissions, and blocking applies system-wide. No longer
 * where the app blocks numbers — the platform rejects these before the app
 * sees the call, which is why [BlockedNumbersMigration] drains this list
 * into the app-owned one at start.
 */
class AndroidBlockedNumbersRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BlockedNumbersRepository, SystemBlockedNumberList {

    override suspend fun canBlock(): Boolean = withContext(ioDispatcher) {
        runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }
            .getOrDefault(false)
    }

    override suspend fun isBlocked(number: String): Boolean = withContext(ioDispatcher) {
        runCatching { BlockedNumberContract.isBlocked(context, number) }.getOrDefault(false)
    }

    override suspend fun block(number: String): Boolean = withContext(ioDispatcher) {
        runCatching {
            val values = ContentValues().apply {
                put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number)
            }
            context.contentResolver.insert(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI, values,
            ) != null
        }.getOrDefault(false)
    }

    override suspend fun unblock(number: String): Boolean = withContext(ioDispatcher) {
        runCatching {
            BlockedNumberContract.unblock(context, number) > 0
        }.getOrDefault(false)
    }

    override suspend fun numbers(): List<String> = withContext(ioDispatcher) {
        runCatching {
            context.contentResolver.query(
                BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
                null, null, null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    override suspend fun remove(number: String) {
        unblock(number)
    }
}
