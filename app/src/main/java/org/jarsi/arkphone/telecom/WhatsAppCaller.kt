package org.jarsi.arkphone.telecom

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.di.IoDispatcher
import org.jarsi.arkphone.util.internationalDigits
import org.jarsi.arkphone.util.nationalSignificantDigits
import javax.inject.Inject
import javax.inject.Singleton

private const val WHATSAPP_PACKAGE = "com.whatsapp"
private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

/** The voip.call contact-row MIME of a WhatsApp variant; the package name is
 *  part of the type ("vnd.com.whatsapp.voip.call", "vnd.com.whatsapp.w4b.voip.call"). */
internal fun voipCallMime(packageName: String) =
    "vnd.android.cursor.item/vnd.$packageName.voip.call"

/** Fire-and-forget entry point for starting a WhatsApp call from the UI.
 *  [sourcePackage] is the WhatsApp variant that carried the original call,
 *  or null to use whichever variant is installed. */
fun interface WhatsAppCallLauncher {
    fun startCall(number: String?, name: String?, sourcePackage: String?)
}

/** Whether any WhatsApp variant is installed, for hiding WhatsApp UI. */
fun interface WhatsAppAvailability {
    fun isAnyVariantInstalled(): Boolean
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

/** Chat fallback link — wa.me only accepts an international number without
 *  the trunk zero, so national forms are converted only when the device
 *  region confirms Finland; anything else ambiguous gets no link. */
internal fun waMeUri(number: String, regionCountryIso: String?): Uri? {
    val international = internationalDigits(number)
    if (international != null) return "https://wa.me/$international".toUri()
    if (!"FI".equals(regionCountryIso, ignoreCase = true)) return null
    val significant = nationalSignificantDigits(number) ?: return null
    return "https://wa.me/358$significant".toUri()
}

/** Which installed WhatsApp variant a callback should go through: the one
 *  that carried the call when it is still installed, else personal, else
 *  Business — so Business-only devices stay callable. */
internal fun preferredWhatsAppPackage(
    sourcePackage: String?,
    isInstalled: (String) -> Boolean,
): String? =
    (listOfNotNull(sourcePackage) + listOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE))
        .firstOrNull(isInstalled)

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
) : WhatsAppCallLauncher, WhatsAppAvailability {
    override fun isAnyVariantInstalled(): Boolean =
        preferredWhatsAppPackage(sourcePackage = null, isInstalled = ::isInstalled) != null

    override fun startCall(number: String?, name: String?, sourcePackage: String?) {
        scope.launch {
            val intent = withContext(ioDispatcher) { callIntent(number, name, sourcePackage) }
                ?: return@launch
            runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun callIntent(number: String?, name: String?, sourcePackage: String?): Intent? {
        val packageName = preferredWhatsAppPackage(sourcePackage, ::isInstalled) ?: return null
        val mime = voipCallMime(packageName)
        val rowId = voipCallRowId(number, name, mime)
        return when {
            rowId != null -> Intent(Intent.ACTION_VIEW)
                .setDataAndType(
                    ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, rowId),
                    mime,
                )
                .setPackage(packageName)
            !number.isNullOrBlank() ->
                waMeUri(number, deviceRegion())?.let { Intent(Intent.ACTION_VIEW, it).setPackage(packageName) }
                    ?: context.packageManager.getLaunchIntentForPackage(packageName)
            else -> context.packageManager.getLaunchIntentForPackage(packageName)
        }
    }

    private fun isInstalled(packageName: String): Boolean =
        runCatching { context.packageManager.getPackageInfo(packageName, 0) }.isSuccess

    private fun deviceRegion(): String? {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null
        return telephony.simCountryIso?.takeIf { it.isNotBlank() }
            ?: telephony.networkCountryIso?.takeIf { it.isNotBlank() }
    }

    private fun voipCallRowId(number: String?, name: String?, mime: String): Long? {
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
                arrayOf(mime),
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
