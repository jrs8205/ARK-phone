package org.jarsi.arkphone.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony

/** Minimal in-memory stand-in for the telephony providers. Tests seed
 *  [conversationRows], [canonicalAddresses] and [smsRows] directly; queries
 *  answer from them by URI shape. Only the columns the app reads exist. */
class FakeTelephonyProvider : ContentProvider() {

    /** Column order: _id, date, message_count, recipient_ids, snippet, read */
    val conversationRows = mutableListOf<Array<Any?>>()
    val canonicalAddresses = mutableMapOf<Long, String>()
    val smsRows = mutableListOf<ContentValues>()
    val deletedUris = mutableListOf<Uri>()
    val updatedUris = mutableListOf<Pair<Uri, ContentValues>>()
    private var nextSmsId = 100L

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val path = uri.toString()
        return when {
            path.startsWith("content://mms-sms/conversations") -> {
                val cursor = MatrixCursor(
                    arrayOf("_id", "date", "message_count", "recipient_ids", "snippet", "read"),
                )
                conversationRows.forEach(cursor::addRow)
                cursor
            }
            path.startsWith("content://mms-sms/threadID") -> {
                MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(42L)) }
            }
            path.startsWith("content://mms-sms/canonical-address/") -> {
                val id = uri.lastPathSegment!!.toLong()
                MatrixCursor(arrayOf("address")).apply {
                    canonicalAddresses[id]?.let { addRow(arrayOf(it)) }
                }
            }
            uri == Telephony.Sms.CONTENT_URI && selection?.startsWith(Telephony.Sms.BODY) == true -> {
                val regex = likePatternToRegex(selectionArgs!!.first())
                val cursor = MatrixCursor(arrayOf("thread_id"))
                smsRows
                    .filter { regex.matches(it.getAsString(Telephony.Sms.BODY).orEmpty()) }
                    .forEach { cursor.addRow(arrayOf(it.getAsLong(Telephony.Sms.THREAD_ID))) }
                cursor
            }
            uri == Telephony.Sms.CONTENT_URI -> {
                val threadIdArg = selectionArgs?.firstOrNull()?.toLongOrNull()
                val cursor = MatrixCursor(
                    arrayOf("_id", "thread_id", "address", "body", "date", "type", "status", "read", "sub_id"),
                )
                smsRows
                    .filter { threadIdArg == null || it.getAsLong(Telephony.Sms.THREAD_ID) == threadIdArg }
                    .sortedBy { it.getAsLong(Telephony.Sms.DATE) }
                    .forEach { row ->
                        cursor.addRow(
                            arrayOf(
                                row.getAsLong(Telephony.Sms._ID),
                                row.getAsLong(Telephony.Sms.THREAD_ID),
                                row.getAsString(Telephony.Sms.ADDRESS),
                                row.getAsString(Telephony.Sms.BODY),
                                row.getAsLong(Telephony.Sms.DATE),
                                row.getAsInteger(Telephony.Sms.TYPE),
                                row.getAsInteger(Telephony.Sms.STATUS),
                                row.getAsInteger(Telephony.Sms.READ),
                                row.getAsInteger(Telephony.Sms.SUBSCRIPTION_ID),
                            ),
                        )
                    }
                cursor
            }
            else -> MatrixCursor(projection ?: emptyArray())
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri == Telephony.Sms.CONTENT_URI && values != null) {
            val id = nextSmsId++
            smsRows += ContentValues(values).apply { put(Telephony.Sms._ID, id) }
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        deletedUris += uri
        return 1
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int {
        updatedUris += uri to (values ?: ContentValues())
        return 1
    }

    override fun getType(uri: Uri): String? = null

    /** SQLite LIKE with ESCAPE '\', as a case-insensitive regex. */
    private fun likePatternToRegex(pattern: String): Regex {
        val regex = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    regex.append(Regex.escape(pattern[i + 1].toString()))
                    i++
                }
                c == '%' -> regex.append(".*")
                c == '_' -> regex.append('.')
                else -> regex.append(Regex.escape(c.toString()))
            }
            i++
        }
        return Regex(regex.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }
}
