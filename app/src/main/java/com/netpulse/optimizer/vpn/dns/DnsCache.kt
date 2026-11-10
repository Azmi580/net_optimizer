package com.netpulse.optimizer.vpn.dns

import android.os.SystemClock
import com.netpulse.optimizer.vpn.packet.Bytes

/**
 * TTL-aware LRU answer cache. On a high-RTT cellular link a cache hit turns a 60–200 ms lookup into ~0 ms.
 * Returned answers have their TTLs decremented by the time spent in cache (RFC 1035 §7.4 semantics).
 */
class DnsCache(
    private val maxEntries: Int = 2048,
    private val minTtlSeconds: Long = 5,
    private val maxTtlSeconds: Long = 3600,
) {

    private class Entry(
        val response: ByteArray,
        val ttlOffsets: IntArray,
        val originalTtls: LongArray,
        val storedAtMs: Long,
        val expiresAtMs: Long,
    )

    private val entries = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    @Synchronized
    fun get(key: String, queryId: Int): ByteArray? {
        val entry = entries[key] ?: return null
        val now = SystemClock.elapsedRealtime()
        if (now >= entry.expiresAtMs) {
            entries.remove(key)
            return null
        }
        val elapsedSeconds = (now - entry.storedAtMs) / 1000
        val copy = entry.response.copyOf()
        Bytes.put16(copy, 0, queryId)
        for (i in entry.ttlOffsets.indices) {
            val aged = (entry.originalTtls[i] - elapsedSeconds).coerceAtLeast(1)
            Bytes.put32(copy, entry.ttlOffsets[i], aged)
        }
        return copy
    }

    @Synchronized
    fun put(key: String, response: ByteArray) {
        if (response.size < DnsMessage.HEADER_SIZE || DnsMessage.isTruncated(response)) return
        val rcode = DnsMessage.rcode(response)
        if (rcode != 0 && rcode != 3) return // cache only NOERROR / NXDOMAIN
        val info = DnsMessage.ttlInfo(response) ?: return
        val ttl = info.minTtl.coerceIn(minTtlSeconds, maxTtlSeconds)
        val originals = LongArray(info.ttlOffsets.size) { Bytes.u32(response, info.ttlOffsets[it]) }
        val now = SystemClock.elapsedRealtime()
        entries[key] = Entry(response.copyOf(), info.ttlOffsets, originals, now, now + ttl * 1000)
    }

    @Synchronized
    fun clear() = entries.clear()

    @Synchronized
    fun size(): Int = entries.size
}
