package com.netpulse.optimizer.vpn.dns

import com.netpulse.optimizer.domain.model.UpstreamStats
import com.netpulse.optimizer.vpn.packet.Bytes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Exempts upstream sockets from the VPN routing table (VpnService.protect). */
interface SocketProtector {
    fun protect(socket: DatagramSocket): Boolean
    fun protect(socket: Socket): Boolean
}

data class DnsUpstream(val name: String, val address: InetAddress)

/**
 * Persistent, multiplexed UDP socket pool with latency-adaptive hedged requests.
 *
 * - One connected, protected [DatagramSocket] per upstream; a single receiver coroutine demultiplexes
 *   responses by a randomised 16-bit transaction id (no per-query socket setup cost, spoof-resistant).
 * - Hedging: the fastest healthy upstream is queried first; if it has not answered within ~1.5× its EWMA
 *   RTT the next upstream is raced in, then the next. First valid answer wins. This removes the long tail
 *   caused by cellular packet loss without tripling upstream traffic.
 * - Truncated answers are re-fetched over TCP.
 */
class DnsUpstreamPool(
    private val protector: SocketProtector,
    private val upstreams: List<DnsUpstream>,
    parentScope: CoroutineScope,
) {

    private val scope = CoroutineScope(SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO)
    private val channels = AtomicReference<List<UpstreamChannel>>(emptyList())
    private val random = SecureRandom()

    fun start() {
        channels.set(openAll())
    }

    /** Drops every socket (stale NAT bindings / old radio bearer) and opens fresh ones. */
    fun refresh() {
        val old = channels.getAndSet(openAll())
        old.forEach { it.close() }
    }

    fun close() {
        channels.getAndSet(emptyList()).forEach { it.close() }
        scope.cancel()
    }

    fun warmUp() {
        channels.get().forEach { channel ->
            scope.launch {
                val probe = DnsMessage.buildQuery(WARMUP_NAME, DnsMessage.TYPE_A, 0)
                channel.query(probe, WARMUP_TIMEOUT_MS)
            }
        }
    }

    fun stats(): List<UpstreamStats> {
        val open = channels.get()
        return upstreams.map { upstream ->
            val channel = open.firstOrNull { it.upstream == upstream }
            UpstreamStats(
                name = upstream.name,
                address = upstream.address.hostAddress ?: "",
                ewmaRttMs = channel?.ewmaRttMs,
                wins = channel?.wins?.get() ?: 0L,
                healthy = channel?.healthy ?: false,
            )
        }
    }

    suspend fun resolve(query: ByteArray): ByteArray? = coroutineScope {
        val ranked = ranked()
        if (ranked.isEmpty()) return@coroutineScope null
        val hedgeDelay = ranked.first().ewmaRttMs
            ?.let { (it * 3 / 2).coerceIn(MIN_HEDGE_MS, MAX_HEDGE_MS) }
            ?: DEFAULT_HEDGE_MS

        val winner = CompletableDeferred<ByteArray?>()
        val attempts = ranked.mapIndexed { index, channel ->
            launch {
                if (index > 0) delay(hedgeDelay * index)
                if (winner.isCompleted) return@launch
                val response = channel.query(query, QUERY_TIMEOUT_MS)
                if (response != null && winner.complete(response)) channel.wins.incrementAndGet()
            }
        }
        val exhausted = launch {
            attempts.joinAll()
            winner.complete(null)
        }
        val result = withTimeoutOrNull(OVERALL_TIMEOUT_MS) { winner.await() }
        attempts.forEach { it.cancel() }
        exhausted.cancel()
        result
    }

    suspend fun resolveTcp(query: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        for (channel in ranked()) {
            val response = tcpQuery(channel.upstream.address, query)
            if (response != null) return@withContext response
        }
        null
    }

    private fun tcpQuery(address: InetAddress, query: ByteArray): ByteArray? {
        val socket = Socket()
        return try {
            socket.bind(null) // materialises the fd so protect() can mark it
            protector.protect(socket)
            socket.tcpNoDelay = true
            socket.soTimeout = TCP_TIMEOUT_MS
            socket.connect(InetSocketAddress(address, DNS_PORT), TCP_TIMEOUT_MS)
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            out.writeShort(query.size)
            out.write(query)
            out.flush()
            val input = DataInputStream(socket.getInputStream())
            val length = input.readUnsignedShort()
            if (length < DnsMessage.HEADER_SIZE) return null
            ByteArray(length).also { input.readFully(it) }
        } catch (_: IOException) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun ranked(): List<UpstreamChannel> =
        channels.get().sortedWith(compareBy<UpstreamChannel>({ !it.healthy }, { it.ewmaRttMs ?: UNKNOWN_RTT }))

    private fun openAll(): List<UpstreamChannel> = upstreams.mapNotNull { upstream ->
        try {
            UpstreamChannel(upstream)
        } catch (_: IOException) {
            null
        }
    }

    private inner class UpstreamChannel(val upstream: DnsUpstream) {

        private val socket = DatagramSocket()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
        private val ewma = AtomicLong(-1)
        private val consecutiveFailures = AtomicInteger(0)
        val wins = AtomicLong(0)

        val ewmaRttMs: Long? get() = ewma.get().takeIf { it >= 0 }
        val healthy: Boolean get() = consecutiveFailures.get() < UNHEALTHY_AFTER

        private val receiver: Job

        init {
            try {
                if (!protector.protect(socket)) throw IOException("protect() failed for ${upstream.name}")
                socket.connect(InetSocketAddress(upstream.address, DNS_PORT))
            } catch (e: Exception) {
                socket.close()
                throw if (e is IOException) e else IOException(e)
            }
            receiver = scope.launch {
                val buffer = ByteArray(MAX_UDP_RESPONSE)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: IOException) {
                        break
                    }
                    if (packet.length < DnsMessage.HEADER_SIZE) continue
                    val data = buffer.copyOf(packet.length)
                    pending.remove(Bytes.u16(data, 0))?.complete(data)
                }
            }
        }

        suspend fun query(query: ByteArray, timeoutMs: Long): ByteArray? {
            val deferred = CompletableDeferred<ByteArray>()
            var id = -1
            for (attempt in 0 until 16) {
                val candidate = random.nextInt(0x10000)
                if (pending.putIfAbsent(candidate, deferred) == null) {
                    id = candidate
                    break
                }
            }
            if (id < 0) return null

            val originalId = DnsMessage.id(query)
            val outgoing = DnsMessage.withId(query, id)
            val start = System.nanoTime()
            return try {
                withContext(Dispatchers.IO) { socket.send(DatagramPacket(outgoing, outgoing.size)) }
                val response = withTimeoutOrNull(timeoutMs) { deferred.await() }
                if (response == null) {
                    consecutiveFailures.incrementAndGet()
                    null
                } else {
                    recordRtt((System.nanoTime() - start) / 1_000_000L)
                    consecutiveFailures.set(0)
                    DnsMessage.withId(response, originalId)
                }
            } catch (_: IOException) {
                consecutiveFailures.incrementAndGet()
                null
            } finally {
                pending.remove(id)
            }
        }

        private fun recordRtt(sampleMs: Long) {
            while (true) {
                val current = ewma.get()
                val next = if (current < 0) sampleMs else (current * 7 + sampleMs * 3) / 10
                if (ewma.compareAndSet(current, next)) return
            }
        }

        fun close() {
            receiver.cancel()
            socket.close()
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    companion object {
        private const val DNS_PORT = 53
        private const val MAX_UDP_RESPONSE = 4096
        private const val QUERY_TIMEOUT_MS = 2_000L
        private const val OVERALL_TIMEOUT_MS = 3_000L
        private const val DEFAULT_HEDGE_MS = 200L
        private const val MIN_HEDGE_MS = 60L
        private const val MAX_HEDGE_MS = 400L
        private const val TCP_TIMEOUT_MS = 2_500
        private const val UNHEALTHY_AFTER = 3
        private const val UNKNOWN_RTT = Long.MAX_VALUE / 2
        private const val WARMUP_NAME = "www.google.com"
        private const val WARMUP_TIMEOUT_MS = 1_500L
    }
}
