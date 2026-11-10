package com.netpulse.optimizer.vpn.dns

import com.netpulse.optimizer.vpn.packet.Bytes

/** Zero-dependency RFC 1035 helpers operating directly on wire-format messages. */
object DnsMessage {

    const val HEADER_SIZE = 12
    const val TYPE_A = 1
    private const val TYPE_OPT = 41
    private const val RCODE_SERVFAIL = 2
    private const val NEGATIVE_DEFAULT_TTL = 30L

    class TtlInfo(val minTtl: Long, val ttlOffsets: IntArray)

    fun id(message: ByteArray): Int = Bytes.u16(message, 0)

    fun isResponse(message: ByteArray): Boolean = (message[2].toInt() and 0x80) != 0

    fun isTruncated(message: ByteArray): Boolean = (message[2].toInt() and 0x02) != 0

    fun rcode(message: ByteArray): Int = message[3].toInt() and 0x0F

    fun withId(message: ByteArray, id: Int): ByteArray {
        val copy = message.copyOf()
        Bytes.put16(copy, 0, id)
        return copy
    }

    /** "name|qtype|qclass" (lower-cased) for the single question, or null for unusual messages. */
    fun questionKey(message: ByteArray): String? {
        if (message.size < HEADER_SIZE + 5 || Bytes.u16(message, 4) != 1) return null
        val sb = StringBuilder(64)
        var off = HEADER_SIZE
        while (true) {
            if (off >= message.size) return null
            val len = message[off].toInt() and 0xFF
            if (len == 0) {
                off++
                break
            }
            if ((len and 0xC0) != 0 || off + 1 + len > message.size) return null
            for (i in 1..len) sb.append(Char(message[off + i].toInt() and 0xFF).lowercaseChar())
            sb.append('.')
            off += len + 1
        }
        if (off + 4 > message.size) return null
        sb.append('|').append(Bytes.u16(message, off)).append('|').append(Bytes.u16(message, off + 2))
        return sb.toString()
    }

    /** Cache key = question + flags that change the answer (CD bit, EDNS present -> DNSSEC records). */
    fun cacheKey(query: ByteArray): String? {
        val question = questionKey(query) ?: return null
        val cd = (query[3].toInt() and 0x10) != 0
        val edns = Bytes.u16(query, 10) > 0
        return "$question|${if (cd) 1 else 0}|${if (edns) 1 else 0}"
    }

    /** Walks every RR and records TTL offsets so cached answers can be aged precisely. */
    fun ttlInfo(message: ByteArray): TtlInfo? {
        if (message.size < HEADER_SIZE) return null
        val questions = Bytes.u16(message, 4)
        val records = Bytes.u16(message, 6) + Bytes.u16(message, 8) + Bytes.u16(message, 10)
        var off = HEADER_SIZE
        repeat(questions) {
            off = skipName(message, off)
            if (off < 0 || off + 4 > message.size) return null
            off += 4
        }
        val offsets = ArrayList<Int>(records)
        var min = Long.MAX_VALUE
        repeat(records) {
            off = skipName(message, off)
            if (off < 0 || off + 10 > message.size) return null
            val type = Bytes.u16(message, off)
            val ttl = Bytes.u32(message, off + 4)
            val rdLength = Bytes.u16(message, off + 8)
            if (type != TYPE_OPT) {
                offsets += off + 4
                if (ttl < min) min = ttl
            }
            off += 10 + rdLength
            if (off > message.size) return null
        }
        return TtlInfo(if (min == Long.MAX_VALUE) NEGATIVE_DEFAULT_TTL else min, offsets.toIntArray())
    }

    /** SERVFAIL mirroring the client's question, so the stub resolver fails fast and retries. */
    fun servfail(query: ByteArray): ByteArray {
        val questionEnd = questionEnd(query)
        val response = if (questionEnd > 0) query.copyOf(questionEnd) else query.copyOf(HEADER_SIZE)
        response[2] = ((query[2].toInt() and 0x79) or 0x80).toByte()
        response[3] = (0x80 or RCODE_SERVFAIL).toByte()
        if (questionEnd <= 0) Bytes.put16(response, 4, 0)
        Bytes.put16(response, 6, 0)
        Bytes.put16(response, 8, 0)
        Bytes.put16(response, 10, 0)
        return response
    }

    /** Recursion-desired query used for upstream warm-up and RTT priming. */
    fun buildQuery(name: String, type: Int, id: Int): ByteArray {
        val labels = name.trimEnd('.').split('.').filter { it.isNotEmpty() }
        val size = HEADER_SIZE + labels.sumOf { it.length + 1 } + 1 + 4
        val message = ByteArray(size)
        Bytes.put16(message, 0, id)
        Bytes.put16(message, 2, 0x0100)
        Bytes.put16(message, 4, 1)
        var off = HEADER_SIZE
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            message[off++] = bytes.size.toByte()
            System.arraycopy(bytes, 0, message, off, bytes.size)
            off += bytes.size
        }
        message[off++] = 0
        Bytes.put16(message, off, type)
        Bytes.put16(message, off + 2, 1)
        return message
    }

    private fun questionEnd(message: ByteArray): Int {
        if (message.size < HEADER_SIZE || Bytes.u16(message, 4) < 1) return -1
        val nameEnd = skipName(message, HEADER_SIZE)
        if (nameEnd < 0 || nameEnd + 4 > message.size) return -1
        return nameEnd + 4
    }

    private fun skipName(message: ByteArray, start: Int): Int {
        var off = start
        while (off < message.size) {
            val len = message[off].toInt() and 0xFF
            when {
                len == 0 -> return off + 1
                (len and 0xC0) == 0xC0 -> return if (off + 2 <= message.size) off + 2 else -1
                (len and 0xC0) != 0 -> return -1
                else -> off += len + 1
            }
        }
        return -1
    }
}
