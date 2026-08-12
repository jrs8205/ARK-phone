package org.jarsi.arkphone.messaging

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jarsi.arkphone.R
import org.jarsi.arkphone.data.BlockedNumbersRepository
import org.jarsi.arkphone.data.ContactsRepository
import org.jarsi.arkphone.data.MessagesRepository
import org.jarsi.arkphone.data.mms.MmsPart
import org.jarsi.arkphone.data.mms.RetrieveConf
import org.jarsi.arkphone.data.mms.parseNotificationInd
import org.jarsi.arkphone.data.mms.parseRetrieveConf
import org.jarsi.arkphone.di.ApplicationScope
import org.jarsi.arkphone.di.IoDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Turns a WAP push into a pending MMS row, downloads the real message from
 *  the carrier and replaces the placeholder with its parts. */
@Singleton
class MmsDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val blockedNumbers: BlockedNumbersRepository,
    private val contactsRepository: ContactsRepository,
    private val messageNotifier: MessageNotifier,
    private val messagesRepository: MessagesRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    companion object {
        const val ACTION_MMS_DOWNLOADED = "org.jarsi.arkphone.action.MMS_DOWNLOADED"
        const val EXTRA_MESSAGE_ID = "org.jarsi.arkphone.extra.MMS_MESSAGE_ID"

        /** m-notification-ind: the not-yet-downloaded placeholder. */
        const val MESSAGE_TYPE_NOTIFICATION = 130

        /** m-retrieve-conf: the fully downloaded message. */
        const val MESSAGE_TYPE_RETRIEVED = 132

        private const val ADDRESS_TYPE_CC = 130
        private const val ADDRESS_TYPE_FROM = 137
        private const val ADDRESS_TYPE_TO = 151
        private const val CHARSET_UTF8 = 106
        private const val TEXT_PLAIN = "text/plain"
    }

    suspend fun onPush(pdu: ByteArray, subscriptionId: Int = -1) {
        val notification = parseNotificationInd(pdu) ?: return
        val sender = notification.from ?: return
        withContext(ioDispatcher) {
            runCatching {
                // The carrier re-sends an unacknowledged notification; the
                // same transaction must not become a second message copy.
                if (transactionExists(notification.transactionId)) return@runCatching
                val blocked = blockedNumbers.isBlocked(sender)
                val values = ContentValues().apply {
                    put(
                        Telephony.Mms.THREAD_ID,
                        Telephony.Threads.getOrCreateThreadId(context, dialable(sender)),
                    )
                    put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000)
                    put(Telephony.Mms.READ, if (blocked) 1 else 0)
                    put(Telephony.Mms.SEEN, if (blocked) 1 else 0)
                    put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
                    put(Telephony.Mms.MESSAGE_TYPE, MESSAGE_TYPE_NOTIFICATION)
                    put(Telephony.Mms.CONTENT_LOCATION, notification.contentLocation)
                    put(Telephony.Mms.TRANSACTION_ID, notification.transactionId)
                    // Remembered on the row so a later retry still downloads
                    // over the right SIM.
                    if (subscriptionId >= 0) {
                        put(Telephony.Mms.SUBSCRIPTION_ID, subscriptionId)
                    }
                }
                val rowUri = context.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)
                    ?: return@runCatching
                val messageId = ContentUris.parseId(rowUri)
                context.contentResolver.insert(
                    "content://mms/$messageId/addr".toUri(),
                    ContentValues().apply {
                        put(Telephony.Mms.Addr.ADDRESS, sender)
                        put(Telephony.Mms.Addr.TYPE, ADDRESS_TYPE_FROM)
                        put(Telephony.Mms.Addr.MSG_ID, messageId)
                        put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF8)
                    },
                )
                messagesRepository.refresh()
                startDownload(messageId, notification.contentLocation)
            }
        }
    }

    private fun transactionExists(transactionId: String): Boolean =
        context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(Telephony.Mms._ID),
            Telephony.Mms.TRANSACTION_ID + " = ?",
            arrayOf(transactionId),
            null,
        )?.use { it.count > 0 } ?: false

    suspend fun retryDownload(messageId: Long) {
        withContext(ioDispatcher) {
            val contentLocation = queryColumn(messageId, Telephony.Mms.CONTENT_LOCATION)
                ?: return@withContext
            runCatching { startDownload(messageId, contentLocation) }
        }
    }

    suspend fun onDownloaded(messageId: Long) {
        withContext(ioDispatcher) {
            runCatching {
                val file = downloadFileFor(messageId)
                val conf = file.takeIf { it.exists() }
                    ?.readBytes()
                    ?.let(::parseRetrieveConf)
                file.delete()
                // A parse failure — or a parse yielding no parts — keeps the
                // placeholder; the thread shows a tap-to-retry row instead of
                // an empty bubble that has lost the message.
                if (conf == null || conf.parts.isEmpty()) return@runCatching
                val movedFromThread = storeRetrieved(messageId, conf)
                messagesRepository.refresh()
                // The push filed the message under the sender's 1:1 thread.
                // When the move emptied that thread it would linger as a
                // ghost conversation; when messages remain, its cached
                // unread flag is stale and needs the recompute.
                movedFromThread?.let {
                    if (!messagesRepository.pruneEmptyThread(it)) {
                        messagesRepository.recomputeThreadRead(it)
                    }
                }
                notifyUnlessBlocked(messageId, conf)
            }
        }
    }

    internal fun downloadFileFor(messageId: Long): File =
        File(File(context.cacheDir, "mms"), "mms-$messageId.pdu")

    private fun startDownload(messageId: Long, contentLocation: String) {
        val file = downloadFileFor(messageId).apply { parentFile?.mkdirs() }
        val fileUri = FileProvider.getUriForFile(context, MmsFileProvider.AUTHORITY, file)
        // The platform mms service writes into our cache through this grant.
        listOf("com.android.phone", "com.android.mms.service").forEach { pkg ->
            runCatching {
                context.grantUriPermission(
                    pkg,
                    fileUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        val downloaded = PendingIntent.getBroadcast(
            context,
            messageId.toInt(),
            Intent(context, MmsDownloadReceiver::class.java)
                .setAction(ACTION_MMS_DOWNLOADED)
                .putExtra(EXTRA_MESSAGE_ID, messageId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val subscriptionId =
            queryColumn(messageId, Telephony.Mms.SUBSCRIPTION_ID)?.toIntOrNull() ?: -1
        smsManager(validSubscriptionOrDefault(subscriptionId))
            .downloadMultimediaMessage(context, contentLocation, fileUri, null, downloaded)
    }

    /** A row can outlive its SIM: a retry pinned to a removed or swapped
     *  subscription fails forever, so a provably dead id degrades to the
     *  default manager. An unreadable subscription list proves nothing and
     *  keeps the stored id. */
    @SuppressLint("MissingPermission")
    internal fun validSubscriptionOrDefault(subscriptionId: Int): Int {
        if (subscriptionId < 0) return subscriptionId
        val active = runCatching {
            context.getSystemService(SubscriptionManager::class.java)
                ?.activeSubscriptionInfoList
                ?.map { it.subscriptionId }
        }.getOrNull() ?: return subscriptionId
        return if (active.isEmpty() || subscriptionId in active) {
            subscriptionId
        } else {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        }
    }

    /** The download must ride the SIM the notification arrived on: the
     *  default subscription's APN reaches the wrong carrier's MMSC. */
    private fun smsManager(subscriptionId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(SmsManager::class.java)
            if (subscriptionId >= 0) manager.createForSubscriptionId(subscriptionId) else manager
        } else {
            @Suppress("DEPRECATION")
            if (subscriptionId >= 0) {
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            } else {
                SmsManager.getDefault()
            }
        }

    /** Returns the thread the message was moved away from, if any. */
    private fun storeRetrieved(messageId: Long, conf: RetrieveConf): Long? {
        val originalThreadId = queryColumn(messageId, Telephony.Mms.THREAD_ID)?.toLongOrNull()
        var newThreadId: Long? = null
        val update = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_TYPE, MESSAGE_TYPE_RETRIEVED)
            if (conf.timestampSeconds > 0) put(Telephony.Mms.DATE, conf.timestampSeconds)
            // An insert-address-token retrieve-conf hides its sender, but
            // the push already stored the real one on the row.
            val sender = conf.from ?: querySender(messageId)
            groupRecipients(conf, sender)?.let { group ->
                // A group MMS belongs to the thread of the whole group, not
                // to the 1:1 thread of its sender the push was filed under.
                newThreadId = Telephony.Threads.getOrCreateThreadId(
                    context,
                    group.mapTo(mutableSetOf(), ::dialable),
                )
                put(Telephony.Mms.THREAD_ID, newThreadId)
            }
        }
        context.contentResolver.update(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId),
            update,
            null,
            null,
        )
        conf.to.forEach { recipient -> insertAddr(messageId, recipient, ADDRESS_TYPE_TO) }
        conf.cc.forEach { recipient -> insertAddr(messageId, recipient, ADDRESS_TYPE_CC) }
        conf.parts.forEach { part -> insertPart(messageId, part) }
        return originalThreadId?.takeIf { newThreadId != null && newThreadId != it }
    }

    /** The sender plus the To recipients that are not this phone's own
     *  numbers; null when that resolves to a plain 1:1 conversation.
     *  While any active SIM keeps its own number to itself, a lone To entry
     *  can be this phone, so only two or more other recipients count as a
     *  group — one known number must not pass for knowing them all. */
    private fun groupRecipients(conf: RetrieveConf, sender: String?): Set<String>? {
        if (sender == null) return null
        val own = ownNumbers()
        val others = (conf.to + conf.cc)
            .filter { it.isNotBlank() }
            .filterNot { PhoneNumberUtils.compare(it, sender) }
            .filterNot { candidate -> own.numbers.any { PhoneNumberUtils.compare(candidate, it) } }
        val isGroup = if (own.complete) others.isNotEmpty() else others.size >= 2
        if (!isGroup) return null
        return (others + sender).toSet()
    }

    /** Thread resolution must see one spelling per member: an MMSC delivers
     *  display-formatted or suffixed forms that would mint a parallel
     *  canonical row next to the clean one. An email originator is left
     *  alone — digit-normalizing it would corrupt it. */
    private fun dialable(address: String): String =
        if ('@' in address) {
            address
        } else {
            PhoneNumberUtils.normalizeNumber(address).ifEmpty { address }
        }

    private class OwnNumbers(val numbers: Set<String>, val complete: Boolean)

    // READ_PHONE_STATE is granted with the core call permissions; runCatching
    // answers a refusal with the empty, incomplete set.
    @SuppressLint("MissingPermission")
    private fun ownNumbers(): OwnNumbers = runCatching {
        val manager = context.getSystemService(SubscriptionManager::class.java)
            ?: return@runCatching OwnNumbers(emptySet(), complete = false)
        val subscriptions = manager.activeSubscriptionInfoList.orEmpty()
        val numbers = subscriptions
            .mapNotNull { info ->
                // getPhoneNumber also asks the carrier and IMS; the legacy
                // SIM-record number is blank on many operators, and a missed
                // own number puts this phone into its own group threads —
                // forking every received group into a parallel conversation.
                val modern = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    runCatching { manager.getPhoneNumber(info.subscriptionId) }.getOrNull()
                } else {
                    null
                }
                @Suppress("DEPRECATION")
                (modern?.takeIf { it.isNotBlank() } ?: info.number)?.takeIf { it.isNotBlank() }
            }
            .toSet()
        OwnNumbers(numbers, complete = subscriptions.isNotEmpty() && numbers.size == subscriptions.size)
    }.getOrDefault(OwnNumbers(emptySet(), complete = false))

    private fun insertAddr(messageId: Long, address: String, type: Int) {
        runCatching {
            context.contentResolver.insert(
                "content://mms/$messageId/addr".toUri(),
                ContentValues().apply {
                    put(Telephony.Mms.Addr.ADDRESS, address)
                    put(Telephony.Mms.Addr.TYPE, type)
                    put(Telephony.Mms.Addr.MSG_ID, messageId)
                    put(Telephony.Mms.Addr.CHARSET, CHARSET_UTF8)
                },
            )
        }
    }

    private fun insertPart(messageId: Long, part: MmsPart) {
        val values = ContentValues().apply {
            put(Telephony.Mms.Part.MSG_ID, messageId)
            put(Telephony.Mms.Part.CONTENT_TYPE, part.mimeType)
            if (part.mimeType == TEXT_PLAIN) {
                put(Telephony.Mms.Part.TEXT, String(part.body, Charsets.UTF_8))
                put(Telephony.Mms.Part.CHARSET, CHARSET_UTF8)
            }
        }
        val partUri = context.contentResolver.insert(
            "content://mms/$messageId/part".toUri(),
            values,
        ) ?: return
        if (part.mimeType != TEXT_PLAIN) {
            runCatching {
                context.contentResolver.openOutputStream(partUri)?.use { it.write(part.body) }
            }
        }
    }

    private suspend fun notifyUnlessBlocked(messageId: Long, conf: RetrieveConf) {
        // The row is already read when the thread was open on screen while
        // the content landed, or when this was a manual retry — a
        // notification posted now would stick with nothing to cancel it.
        if (queryColumn(messageId, Telephony.Mms.READ)?.toIntOrNull() == 1) return
        val sender = conf.from ?: querySender(messageId) ?: return
        if (blockedNumbers.isBlocked(sender)) return
        val threadId = queryColumn(messageId, Telephony.Mms.THREAD_ID)?.toLongOrNull() ?: return
        val body = conf.parts.firstOrNull { it.mimeType == TEXT_PLAIN }
            ?.let { String(it.body, Charsets.UTF_8) }
            ?: context.getString(R.string.mms_picture_message)
        val displayName = contactsRepository.lookupContact(sender)?.displayName
        val timestampMillis = conf.timestampSeconds
            .takeIf { it > 0 }
            ?.times(1000)
            ?: System.currentTimeMillis()
        val subscriptionId =
            queryColumn(messageId, Telephony.Mms.SUBSCRIPTION_ID)?.toIntOrNull() ?: -1
        val conversationTitle = groupRecipients(conf, sender)
            ?.map { member -> contactsRepository.lookupContact(member)?.displayName ?: member }
            ?.joinToString(", ")
        messageNotifier.notifyMessage(
            threadId,
            sender,
            displayName,
            body,
            timestampMillis,
            subscriptionId,
            conversationTitle,
        )
    }

    private fun querySender(messageId: Long): String? =
        context.contentResolver.query(
            "content://mms/$messageId/addr".toUri(),
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) == ADDRESS_TYPE_FROM) return cursor.getString(0)
            }
            null
        }

    private fun queryColumn(messageId: Long, column: String): String? =
        context.contentResolver.query(
            ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, messageId),
            arrayOf(column),
            null,
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
}

/** Fired by the platform once [SmsManager.downloadMultimediaMessage] wrote
 *  the retrieve-conf into our cache file. */
@AndroidEntryPoint
class MmsDownloadReceiver : BroadcastReceiver() {

    @Inject lateinit var downloader: MmsDownloader

    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MmsDownloader.ACTION_MMS_DOWNLOADED) return
        val messageId = intent.getLongExtra(MmsDownloader.EXTRA_MESSAGE_ID, -1L)
        if (messageId < 0) return
        val pendingResult = goAsync()
        scope.launch {
            try {
                downloader.onDownloaded(messageId)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}
