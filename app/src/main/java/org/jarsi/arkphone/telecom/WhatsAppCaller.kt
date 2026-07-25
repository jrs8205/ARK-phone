package org.jarsi.arkphone.telecom

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton

private const val WHATSAPP_PACKAGE = "com.whatsapp"
private const val VOIP_CALL_MIME = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"

/** Fire-and-forget entry point for starting a WhatsApp call from the UI. */
fun interface WhatsAppCallLauncher {
    fun startCall(number: String?, name: String?)
}

/** True when a WhatsApp voip.call contact row belongs to the given caller. */
internal fun whatsAppRowMatches(
    number: String?,
    name: String?,
    rowNumber: String?,
    rowName: String?,
): Boolean {
    if (!number.isNullOrBlank() && !rowNumber.isNullOrBlank()) {
        return PhoneNumberUtils.compare(number, rowNumber)
    }
    return name != null && name == rowName
}

/** Chat fallback link: https://wa.me/<digits of the international number>. */
internal fun waMeUri(number: String): Uri =
    Uri.parse("https://wa.me/" + number.filter { it.isDigit() })

/**
 * Starts WhatsApp voice calls from the call log: preferably straight through
 * the contact's WhatsApp call row, else by opening the chat for the number,
 * else by just launching WhatsApp.
 */
@Singleton
class WhatsAppCaller @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : WhatsAppCallLauncher {
    override fun startCall(number: String?, name: String?) {
        scope.launch {
            val intent = withContext(ioDispatcher) { callIntent(number, name) } ?: return@launch
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun callIntent(number: String?, name: String?): Intent? {
        val rowId = voipCallRowId(number, name)
        return when {
            rowId != null -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId),
                    VOIP_CALL_MIME,
                )
                .setPackage(WHATSAPP_PACKAGE)
            !number.isNullOrBlank() ->
                Intent(Intent.ACTION_VIEW, waMeUri(number)).setPackage(WHATSAPP_PACKAGE)
            else -> context.packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)
        }
    }

    private fun voipCallRowId(number: String?, name: String?): Long? {
        if (number.isNullOrBlank() && name.isNullOrBlank()) return null
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.Data._ID,
                    ContactsContract.Data.DATA1,
                    ContactsContract.Data.DISPLAY_NAME,
                ),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(VOIP_CALL_MIME),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    // DATA1 is the caller's jid, e.g. "358445552841@s.whatsapp.net".
                    val rowNumber = cursor.getString(1)?.substringBefore('@')
                    if (whatsAppRowMatches(number, name, rowNumber, cursor.getString(2))) {
                        return cursor.getLong(0)
                    }
                }
            }
        }
        return null
    }
}
