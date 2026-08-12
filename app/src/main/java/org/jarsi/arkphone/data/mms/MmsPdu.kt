package org.jarsi.arkphone.data.mms

import java.io.ByteArrayOutputStream

data class NotificationInd(
    val transactionId: String,
    val contentLocation: String,
    val from: String?,
)

class MmsPart(
    val mimeType: String,
    val body: ByteArray,
    val fileName: String?,
) {
    override fun equals(other: Any?): Boolean =
        other is MmsPart && other.mimeType == mimeType &&
            other.body.contentEquals(body) && other.fileName == fileName

    override fun hashCode(): Int = 31 * mimeType.hashCode() + body.contentHashCode()
}

data class RetrieveConf(
    val from: String?,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val timestampSeconds: Long,
    val parts: List<MmsPart>,
)

private const val HEADER_BCC = 0x81
private const val HEADER_CC = 0x82
private const val HEADER_CONTENT_LOCATION = 0x83
private const val HEADER_CONTENT_TYPE = 0x84
private const val HEADER_DATE = 0x85
private const val HEADER_FROM = 0x89
private const val HEADER_MESSAGE_TYPE = 0x8C
private const val HEADER_MMS_VERSION = 0x8D
private const val HEADER_TO = 0x97
private const val HEADER_TRANSACTION_ID = 0x98

private const val MESSAGE_TYPE_SEND_REQ = 0x80
private const val MESSAGE_TYPE_NOTIFICATION_IND = 0x82
private const val MESSAGE_TYPE_RETRIEVE_CONF = 0x84

private const val ADDRESS_PRESENT_TOKEN = 0x80
private const val INSERT_ADDRESS_TOKEN = 0x81
private const val CONTENT_TYPE_MULTIPART_MIXED = 0xA3

private const val PLMN_SUFFIX = "/TYPE=PLMN"

/** WSP well-known media types (table 40), the handful MMS traffic uses. */
private val WELL_KNOWN_MEDIA = mapOf(
    0x03 to "text/plain",
    0x1D to "image/gif",
    0x1E to "image/jpeg",
    0x1F to "image/tiff",
    0x20 to "image/png",
    0x21 to "image/vnd.wap.wbmp",
    0x23 to "application/vnd.wap.multipart.mixed",
    0x33 to "application/vnd.wap.multipart.related",
)

internal fun parseNotificationInd(pdu: ByteArray): NotificationInd? = runCatching {
    val reader = WspReader(pdu)
    var messageType = -1
    var transactionId: String? = null
    var contentLocation: String? = null
    var from: String? = null
    while (reader.hasMore()) {
        val header = reader.readByte()
        if (header < 0x80) return@runCatching null
        when (header) {
            HEADER_MESSAGE_TYPE -> messageType = reader.readByte()
            HEADER_TRANSACTION_ID -> transactionId = reader.readTextString()
            HEADER_CONTENT_LOCATION -> contentLocation = reader.readTextString()
            HEADER_FROM -> from = reader.readFromValue()
            HEADER_CONTENT_TYPE -> return@runCatching null
            else -> reader.skipValue()
        }
    }
    if (messageType != MESSAGE_TYPE_NOTIFICATION_IND) return@runCatching null
    NotificationInd(
        transactionId = transactionId ?: return@runCatching null,
        contentLocation = contentLocation ?: return@runCatching null,
        from = from?.removeSuffix(PLMN_SUFFIX),
    )
}.getOrNull()

/** Accepts m-retrieve-conf and, for the codec round trip, m-send-req. */
internal fun parseRetrieveConf(pdu: ByteArray): RetrieveConf? = runCatching {
    val reader = WspReader(pdu)
    var messageType = -1
    var from: String? = null
    val to = mutableListOf<String>()
    val cc = mutableListOf<String>()
    var dateSeconds = 0L
    var parts: List<MmsPart>? = null
    while (reader.hasMore()) {
        val header = reader.readByte()
        if (header < 0x80) return@runCatching null
        when (header) {
            HEADER_MESSAGE_TYPE -> messageType = reader.readByte()
            HEADER_FROM -> from = reader.readFromValue()
            HEADER_TO -> to += reader.readEncodedString().removeSuffix(PLMN_SUFFIX)
            // One list for both: the Cc/Bcc split changes nothing about
            // which thread the message belongs to.
            HEADER_CC, HEADER_BCC -> cc += reader.readEncodedString().removeSuffix(PLMN_SUFFIX)
            HEADER_DATE -> dateSeconds = reader.readLongInteger()
            HEADER_CONTENT_TYPE -> {
                reader.skipContentTypeValue()
                parts = reader.readMultipartBody()
            }
            else -> reader.skipValue()
        }
        if (parts != null) break
    }
    if (messageType != MESSAGE_TYPE_RETRIEVE_CONF && messageType != MESSAGE_TYPE_SEND_REQ) {
        return@runCatching null
    }
    RetrieveConf(
        from = from?.removeSuffix(PLMN_SUFFIX),
        to = to,
        cc = cc,
        timestampSeconds = dateSeconds,
        parts = parts ?: return@runCatching null,
    )
}.getOrNull()

internal fun composeSendReq(from: String?, to: List<String>, parts: List<MmsPart>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(HEADER_MESSAGE_TYPE)
    out.write(MESSAGE_TYPE_SEND_REQ)
    out.write(HEADER_TRANSACTION_ID)
    out.writeTextString("ark-${System.currentTimeMillis()}")
    out.write(HEADER_MMS_VERSION)
    out.write(0x92) // 1.2
    out.write(HEADER_DATE)
    out.writeLongInteger(System.currentTimeMillis() / 1000)
    out.write(HEADER_FROM)
    if (from.isNullOrBlank()) {
        out.write(1)
        out.write(INSERT_ADDRESS_TOKEN)
    } else {
        val encoded = (from.asPlmn()).toByteArray(Charsets.UTF_8)
        out.writeValueLength(encoded.size + 2)
        out.write(ADDRESS_PRESENT_TOKEN)
        out.write(encoded)
        out.write(0)
    }
    to.forEach { recipient ->
        out.write(HEADER_TO)
        out.writeTextString(recipient.asPlmn())
    }
    out.write(HEADER_CONTENT_TYPE)
    out.write(CONTENT_TYPE_MULTIPART_MIXED)
    out.writeUintvar(parts.size)
    parts.forEach { part ->
        // Extension-media form: the mime type as a plain text string.
        val headers = part.mimeType.toByteArray(Charsets.UTF_8) + 0
        out.writeUintvar(headers.size)
        out.writeUintvar(part.body.size)
        out.write(headers)
        out.write(part.body)
    }
    return out.toByteArray()
}

/** Phone-number addresses travel with an explicit PLMN address type. */
private fun String.asPlmn(): String =
    if (all { it.isDigit() || it == '+' }) this + PLMN_SUFFIX else this

private fun WspReader.readFromValue(): String? {
    val length = readValueLength()
    val end = position + length
    val token = readByte()
    val value = if (token == ADDRESS_PRESENT_TOKEN && position < end) readEncodedString() else null
    position = end
    return value
}

/** Encoded-string: plain text, or value-length + charset + text. */
private fun WspReader.readEncodedString(): String {
    if (peek() > 0x1F) return readTextString()
    val length = readValueLength()
    val end = position + length
    if (peek() >= 0x80) position++ // single-byte charset
    val text = readTextString()
    position = end
    return text
}

private fun WspReader.skipContentTypeValue() {
    when {
        peek() <= 0x1F -> {
            // Two statements on purpose: "position += readValueLength()"
            // loads position before the call advances it, losing the
            // length-prefix bytes and landing the skip short.
            val length = readValueLength()
            position += length
        }
        peek() >= 0x80 -> position++
        else -> readTextString()
    }
}

private fun WspReader.readContentTypeValue(): String = when {
    peek() >= 0x80 -> WELL_KNOWN_MEDIA[readByte() and 0x7F] ?: "application/octet-stream"
    peek() <= 0x1F -> {
        val length = readValueLength()
        val end = position + length
        val media = if (peek() >= 0x80) {
            WELL_KNOWN_MEDIA[readByte() and 0x7F] ?: "application/octet-stream"
        } else {
            readTextString()
        }
        position = end
        media
    }
    else -> readTextString()
}

private fun WspReader.readMultipartBody(): List<MmsPart> {
    val parts = mutableListOf<MmsPart>()
    val count = readUintvar()
    repeat(count) {
        val headersLength = readUintvar()
        val dataLength = readUintvar()
        val headerEnd = position + headersLength
        val mimeType = readContentTypeValue()
        position = headerEnd
        parts += MmsPart(mimeType, readBytes(dataLength), fileName = null)
    }
    return parts
}

private fun ByteArrayOutputStream.writeTextString(text: String) {
    write(text.toByteArray(Charsets.UTF_8))
    write(0)
}

private fun ByteArrayOutputStream.writeUintvar(value: Int) {
    var remaining = value
    val stack = ArrayDeque<Int>()
    stack.addFirst(remaining and 0x7F)
    remaining = remaining ushr 7
    while (remaining > 0) {
        stack.addFirst((remaining and 0x7F) or 0x80)
        remaining = remaining ushr 7
    }
    stack.forEach(::write)
}

private fun ByteArrayOutputStream.writeValueLength(length: Int) {
    if (length < 0x1F) {
        write(length)
    } else {
        write(0x1F)
        writeUintvar(length)
    }
}

private fun ByteArrayOutputStream.writeLongInteger(value: Long) {
    var bytes = 0
    var probe = value
    do {
        bytes++
        probe = probe ushr 8
    } while (probe > 0)
    write(bytes)
    for (shift in (bytes - 1) downTo 0) {
        write(((value ushr (shift * 8)) and 0xFF).toInt())
    }
}
