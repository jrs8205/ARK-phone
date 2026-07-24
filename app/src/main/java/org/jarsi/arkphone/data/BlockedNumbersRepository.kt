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
 * without extra permissions, and blocking applies system-wide.
 */
class AndroidBlockedNumbersRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BlockedNumbersRepository {

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
}
