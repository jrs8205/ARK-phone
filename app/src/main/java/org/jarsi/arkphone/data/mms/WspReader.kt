package org.jarsi.arkphone.data.mms

/** Byte-level cursor over one WSP-encoded PDU. Every read moves [position]
 *  forward; malformed input surfaces as IndexOutOfBoundsException, which the
 *  parse entry points turn into null. */
internal class WspReader(private val data: ByteArray) {

    var position: Int = 0

    fun hasMore(): Boolean = position < data.size

    fun peek(): Int = data[position].toInt() and 0xFF

    fun readByte(): Int = data[position++].toInt() and 0xFF

    fun readUintvar(): Int {
        var result = 0
        while (true) {
            val byte = readByte()
            result = (result shl 7) or (byte and 0x7F)
            if (byte and 0x80 == 0) break
        }
        return result
    }

    fun readTextString(): String {
        // A leading Quote (0x7F) escapes text whose first char is >= 0x80.
        if (peek() == 0x7F) position++
        val start = position
        while (data[position].toInt() != 0) position++
        val text = String(data, start, position - start, Charsets.UTF_8)
        position++
        return text
    }

    /** Value-length: a byte below 31, or 31 followed by a uintvar. */
    fun readValueLength(): Int {
        val first = readByte()
        return if (first == 0x1F) readUintvar() else first
    }

    fun readLongInteger(): Long {
        val length = readByte()
        var value = 0L
        repeat(length) { value = (value shl 8) or readByte().toLong() }
        return value
    }

    fun readBytes(count: Int): ByteArray {
        if (position + count > data.size) throw IndexOutOfBoundsException()
        val out = data.copyOfRange(position, position + count)
        position += count
        return out
    }

    /** Skips one header value using the WSP first-byte rule. */
    fun skipValue() {
        val next = peek()
        when {
            next < 0x1F -> {
                position++
                position += next
            }
            next == 0x1F -> {
                position++
                // Two statements on purpose: "position += readUintvar()"
                // loads position before the call advances it, losing the
                // uintvar bytes and landing the skip short.
                val length = readUintvar()
                position += length
            }
            next >= 0x80 -> position++
            else -> readTextString()
        }
    }
}
