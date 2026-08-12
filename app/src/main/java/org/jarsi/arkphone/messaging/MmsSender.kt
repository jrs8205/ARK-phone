package org.jarsi.arkphone.messaging

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.CarrierConfigManager
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.data.mms.MmsPart
import org.jarsi.arkphone.data.mms.composeSendReq
import org.jarsi.arkphone.di.IoDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Sends one MMS — an image, a text, or both — to one or more recipients.
 *  A multi-recipient send is a group MMS: every receiver sees the whole
 *  group. The subscription id picks the SIM; -1 is the default messaging
 *  SIM. */
interface MmsSender {
    /** Returns the provider row URI, or null when the send could not start. */
    suspend fun send(
        addresses: List<String>,
        text: String?,
        imageUri: Uri?,
        subscriptionId: Int = -1,
    ): Uri?
}

/** Hands one composed PDU file to the platform MMS service. */
fun interface MmsTransport {
    fun sendPdu(pduFile: File, rowUri: Uri, subscriptionId: Int)
}

@Singleton
class PlatformMmsTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : MmsTransport {

    override fun sendPdu(pduFile: File, rowUri: Uri, subscriptionId: Int) {
        val fileUri = FileProvider.getUriForFile(context, MmsFileProvider.AUTHORITY, pduFile)
        // The platform mms service reads the PDU through this grant.
        listOf("com.android.phone", "com.android.mms.service").forEach { pkg ->
            runCatching {
                context.grantUriPermission(
                    pkg,
                    fileUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        val sent = PendingIntent.getBroadcast(
            context,
            rowUri.hashCode(),
            Intent(ACTION_MMS_SENT, rowUri, context, SmsSendStatusReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE or
                PendingIntent.FLAG_ONE_SHOT,
        )
        context.smsManagerFor(subscriptionId)
            .sendMultimediaMessage(context, fileUri, null, null, sent)
    }
}

@Singleton
class AndroidMmsSender @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageShrinker: ImageShrinker,
    private val transport: MmsTransport,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MmsSender {

    companion object {
        const val MESSAGE_BOX_FAILED = 5

        /** The staged outgoing PDU; deleted once the platform reports the
         *  send outcome (see [SmsSendStatusReceiver]). */
        internal fun sendPduFileFor(context: Context, messageId: Long): File =
            File(File(context.cacheDir, "mms"), "mms-send-$messageId.pdu")

        private const val DEFAULT_MAX_MESSAGE_BYTES = 300 * 1024
        private const val ADDRESS_TYPE_TO = 151
        private const val CHARSET_UTF8 = 106
        private const val MESSAGE_TYPE_SEND_REQ = 128

        /** m-retrieve-conf, what the provider expects stored rows to be. */
        private const val MESSAGE_TYPE_RETRIEVED = 132
    }

    override suspend fun send(
        addresses: List<String>,
        text: String?,
        imageUri: Uri?,
        subscriptionId: Int,
    ): Uri? = withContext(ioDispatcher) {
        runCatching {
            if (addresses.isEmpty()) return@runCatching null
            val imageBytes = imageUri?.let { uri ->
                val budget = maxMessageBytes() * 9 / 10
                imageShrinker.shrink(uri, budget) ?: return@runCatching null
            }
            val parts = buildList {
                imageBytes?.let { add(MmsPart("image/jpeg", it, "image.jpg")) }
                text?.takeIf { it.isNotBlank() }?.let {
                    add(MmsPart("text/plain", it.toByteArray(Charsets.UTF_8), null))
                }
            }
            if (parts.isEmpty()) return@runCatching null
            val pdu = composeSendReq(from = null, to = addresses, parts = parts)
            val rowUri = storeOutboxRow(addresses, parts, subscriptionId)
                ?: return@runCatching null
            val messageId = ContentUris.parseId(rowUri)
            val file = sendPduFileFor(context, messageId).apply { parentFile?.mkdirs() }
            file.writeBytes(pdu)
            runCatching { transport.sendPdu(file, rowUri, subscriptionId) }
                .onFailure {
                    // The platform never took the file, so no status broadcast
                    // will come to clean it up.
                    file.delete()
                    // The row stays visible as failed instead of a stuck outbox.
                    context.contentResolver.update(
                        rowUri,
                        ContentValues().apply {
                            put(Telephony.Mms.MESSAGE_BOX, MESSAGE_BOX_FAILED)
                        },
                        null,
                        null,
                    )
                }
            rowUri
        }.getOrNull()
    }

    private fun storeOutboxRow(
        addresses: List<String>,
        parts: List<MmsPart>,
        subscriptionId: Int,
    ): Uri? {
        val values = ContentValues().apply {
            put(
                Telephony.Mms.THREAD_ID,
                Telephony.Threads.getOrCreateThreadId(context, addresses.toSet()),
            )
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.MESSAGE_TYPE, MESSAGE_TYPE_RETRIEVED)
            if (subscriptionId >= 0) {
                put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
            }
        }
        val rowUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
            ?: return null
        val messageId = ContentUris.parseId(rowUri)
        addresses.forEach { address ->
            context.contentResolver.insert(
                "content://mms/$messageId/addr".toUri(),
                ContentValues().apply {
                    put(Telephony.Mms.Addr.ADDRESS, address)
                    put(Telephony.Mms.Addr.TYPE, ADDRESS_TYPE_TO)
                    put(Telephony.Mms.Addr.MSG_ID, messageId)
                    put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF8)
                },
            )
        }
        parts.forEach { part ->
            val partValues = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, messageId)
                put(Telephony.Mms.Part.CONTENT_TYPE, part.mimeType)
                if (part.mimeType == "text/plain") {
                    put(Telephony.Mms.Part.TEXT, String(part.body, Charsets.UTF_8))
                    put(Telephony.Mms.Part.CHARSET, CHARSET_UTF8)
                }
            }
            val partUri = context.contentResolver.insert(
                "content://mms/$messageId/part".toUri(),
                partValues,
            )
            if (partUri != null && part.mimeType != "text/plain") {
                runCatching {
                    context.contentResolver.openOutputStream(partUri)?.use { it.write(part.body) }
                }
            }
        }
        return rowUri
    }

    // READ_PHONE_STATE is granted with the core call permissions; runCatching
    // covers the refusal case with the carrier default.
    @SuppressLint("MissingPermission")
    private fun maxMessageBytes(): Int = runCatching {
        context.getSystemService(CarrierConfigManager::class.java)
            ?.getConfigForSubId(defaultMessagingSubscriptionId())
            ?.getInt(CarrierConfigManager.KEY_MMS_MAX_MESSAGE_SIZE_INT)
            ?.takeIf { it > 0 }
    }.getOrNull() ?: DEFAULT_MAX_MESSAGE_BYTES
}
