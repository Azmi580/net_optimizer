package com.netpulse.optimizer.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.InetAddresses
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.netpulse.optimizer.NetPulseApp
import com.netpulse.optimizer.R
import com.netpulse.optimizer.domain.model.DnsProfile
import com.netpulse.optimizer.domain.model.TunnelStatus
import com.netpulse.optimizer.ui.MainActivity
import com.netpulse.optimizer.vpn.dns.DnsCache
import com.netpulse.optimizer.vpn.dns.DnsMessage
import com.netpulse.optimizer.vpn.dns.DnsUpstream
import com.netpulse.optimizer.vpn.dns.DnsUpstreamPool
import com.netpulse.optimizer.vpn.dns.SocketProtector
import com.netpulse.optimizer.vpn.packet.IpPacketCodec
import com.netpulse.optimizer.vpn.packet.TunPacket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * DNS-only split tunnel.
 *
 * The TUN interface owns a private /32 and routes *only* two virtual resolver addresses
 * (10.111.222.2 / .3) into the tunnel. The OS resolver is told to use those addresses, so every
 * app's DNS lookup enters the TUN while all other traffic keeps its normal, zero-overhead path
 * on the physical network. Queries are answered from a TTL cache or forwarded through a protected,
 * hedged socket pool to the resolvers of the user-selected DNS profile (switchable while running).
 */
class LocalDnsVpnService : VpnService() {

    private val container get() = (application as NetPulseApp).container
    private val repository get() = container.vpnStateRepository
    private val dnsSettings get() = container.dnsSettingsRepository

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> Log.e(TAG, "Unhandled tunnel error", t) },
    )
    private val lifecycleMutex = Mutex()
    private val cache = DnsCache()

    private val totalQueries = AtomicLong()
    private val cacheHits = AtomicLong()
    private val failures = AtomicLong()

    @Volatile private var tunnel: ActiveTunnel? = null
    @Volatile private var pool: DnsUpstreamPool? = null
    private var statsJob: Job? = null
    private var networkJob: Job? = null
    private var settingsJob: Job? = null

    private class ActiveTunnel(val pfd: ParcelFileDescriptor, val mtu: Int, val loop: Job, val wake: WakePipe) {
        /** Unblocks the infinite poll(), stops the loop and releases the TUN fd. */
        fun close() {
            wake.signal()
            loop.cancel()
            runCatching { pfd.close() }
        }
    }

    /**
     * Self-pipe used to wake the packet loop. Lets poll() block with an *infinite* timeout, so an idle
     * tunnel causes zero CPU wake-ups instead of polling on a timer.
     */
    private class WakePipe {
        private val fds: Array<FileDescriptor> = Os.pipe()
        private val closed = AtomicBoolean(false)
        val readFd: FileDescriptor get() = fds[0]

        fun signal() {
            if (closed.get()) return
            try {
                Os.write(fds[1], byteArrayOf(1), 0, 1)
            } catch (_: ErrnoException) {
            } catch (_: IOException) {
            }
        }

        fun close() {
            if (closed.compareAndSet(false, true)) {
                runCatching { Os.close(fds[0]) }
                runCatching { Os.close(fds[1]) }
            }
        }
    }

    private val protector = object : SocketProtector {
        override fun protect(socket: DatagramSocket): Boolean = this@LocalDnsVpnService.protect(socket)
        override fun protect(socket: Socket): Boolean = this@LocalDnsVpnService.protect(socket)
    }

    // ------------------------------------------------------------------ Lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceScope.launch { shutdown() }
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                promoteToForeground(tunnel?.mtu ?: 0)
                serviceScope.launch { refresh() }
            }
            // ACTION_START, null (sticky restart) or android.net.VpnService (always-on VPN boot)
            else -> {
                promoteToForeground(tunnel?.mtu ?: 0)
                serviceScope.launch { start() }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked consent from system settings.
        serviceScope.launch { shutdown() }
    }

    /**
     * Swiping the app away from Recents only removes the UI task. The tunnel lives in this
     * independent foreground service (stopWithTask=false), so it re-asserts foreground state and keeps running.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        tunnel?.let { promoteToForeground(it.mtu) }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        statsJob?.cancel()
        networkJob?.cancel()
        settingsJob?.cancel()
        tunnel?.close()
        tunnel = null
        pool?.close()
        pool = null
        serviceScope.cancel()
        repository.reset()
        super.onDestroy()
    }

    private suspend fun start() {
        lifecycleMutex.withLock {
            if (tunnel != null) return
            if (VpnService.prepare(this) != null) {
                failLocked("VPN izni verilmedi")
                return
            }
            val profile = dnsSettings.settings.value.activeProfile
            repository.update {
                it.copy(status = TunnelStatus.STARTING, lastError = null, profileTitle = profile.title)
            }

            val newPool = DnsUpstreamPool(protector, upstreamsFor(profile), serviceScope)
            newPool.start()
            pool = newPool

            if (!establishTunnelLocked()) {
                newPool.close()
                pool = null
                return
            }
            newPool.warmUp()
            startStatsTicker()
            observeUnderlyingNetwork()
            observeDnsProfile()
        }
    }

    private fun upstreamsFor(profile: DnsProfile): List<DnsUpstream> = profile.servers.mapNotNull { server ->
        try {
            DnsUpstream(server.name, InetAddresses.parseNumericAddress(server.address))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun refresh() {
        val needsStart = lifecycleMutex.withLock {
            if (tunnel == null) return@withLock true
            repository.update { it.copy(status = TunnelStatus.REFRESHING) }
            cache.clear()
            pool?.refresh()
            establishTunnelLocked() // seamless handover: new TUN is up before the old one is closed
            pool?.warmUp()
            false
        }
        if (needsStart) start()
    }

    private suspend fun shutdown() {
        lifecycleMutex.withLock { teardownLocked() }
        repository.reset()
        withContext(Dispatchers.Main) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun teardownLocked() {
        statsJob?.cancel()
        statsJob = null
        networkJob?.cancel()
        networkJob = null
        settingsJob?.cancel()
        settingsJob = null
        tunnel?.close()
        tunnel = null
        pool?.close()
        pool = null
        cache.clear()
    }

    private suspend fun failLocked(message: String) {
        Log.w(TAG, message)
        teardownLocked()
        repository.update { it.copy(status = TunnelStatus.ERROR, lastError = message) }
        withContext(Dispatchers.Main) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------ TUN

    private suspend fun establishTunnelLocked(): Boolean {
        val monitor = container.connectivityMonitor
        val underlying = monitor.bestUnderlyingNetwork()
        val mtu = MtuResolver.resolve(monitor, underlying)

        val builder = Builder()
            .setSession(getString(R.string.vpn_session_name))
            .setMtu(mtu)
            .addAddress(TUN_ADDRESS, 32)
            .addRoute(DNS_PRIMARY, 32)
            .addRoute(DNS_SECONDARY, 32)
            .addDnsServer(DNS_PRIMARY)
            .addDnsServer(DNS_SECONDARY)
            // Without this, IPv6 traffic would be blocked because the VPN declares no IPv6 config.
            .allowFamily(OsConstants.AF_INET6)
            .setBlocking(true)
            .setMetered(false) // inherit metered-ness from the underlying network
            .setConfigureIntent(mainActivityPendingIntent())
        builder.setUnderlyingNetworks(underlying?.let { arrayOf(it) })

        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish() failed", e)
            null
        }
        if (pfd == null) {
            failLocked("TUN arabirimi kurulamadı")
            return false
        }

        val previous = tunnel
        val wake = WakePipe()
        val loop = serviceScope.launch { runPacketLoop(pfd, mtu, wake) }
        tunnel = ActiveTunnel(pfd, mtu, loop, wake)
        previous?.close()

        repository.update {
            it.copy(status = TunnelStatus.RUNNING, mtu = mtu, lastError = null, tunnelGeneration = it.tunnelGeneration + 1)
        }
        updateNotification(mtu)
        return true
    }

    private suspend fun runPacketLoop(pfd: ParcelFileDescriptor, mtu: Int, wake: WakePipe) = coroutineScope {
        val tunFd = pfd.fileDescriptor
        val input = FileInputStream(tunFd)
        val output = FileOutputStream(tunFd)
        val outbound = Channel<ByteArray>(OUTBOUND_QUEUE)

        val writer = launch {
            for (packet in outbound) {
                try {
                    output.write(packet)
                } catch (_: IOException) {
                    break
                }
            }
        }

        val inflight = Semaphore(MAX_INFLIGHT_QUERIES)
        val buffer = ByteArray(MAX_PACKET_SIZE)
        val tunPoll = StructPollfd().apply {
            fd = tunFd
            events = OsConstants.POLLIN.toShort()
        }
        val wakePoll = StructPollfd().apply {
            fd = wake.readFd
            events = OsConstants.POLLIN.toShort()
        }
        val pollFds = arrayOf(tunPoll, wakePoll)
        val errorMask = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL

        try {
            while (isActive) {
                // Infinite timeout: the thread sleeps in the kernel until a DNS packet or a wake signal arrives.
                val ready = try {
                    Os.poll(pollFds, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue else break
                }
                if (ready <= 0) continue
                if (wakePoll.revents.toInt() != 0) break
                val revents = tunPoll.revents.toInt()
                if ((revents and errorMask) != 0) break
                if ((revents and OsConstants.POLLIN) == 0) continue

                val length = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break
                }
                if (length <= 0) continue

                when (val packet = IpPacketCodec.parse(buffer, length)) {
                    is TunPacket.Udp -> if (packet.dstPort == DNS_PORT) {
                        inflight.acquire()
                        launch {
                            try {
                                handleDnsQuery(packet, mtu, outbound)
                            } finally {
                                inflight.release()
                            }
                        }
                    }
                    is TunPacket.Tcp -> IpPacketCodec.buildTcpReset(packet)?.let { outbound.trySend(it) }
                    null -> Unit
                }
            }
        } finally {
            outbound.close()
            writer.cancel()
            wake.close()
        }
    }

    private suspend fun handleDnsQuery(packet: TunPacket.Udp, mtu: Int, outbound: SendChannel<ByteArray>) {
        val query = packet.payload
        if (query.size < DnsMessage.HEADER_SIZE || DnsMessage.isResponse(query)) return
        totalQueries.incrementAndGet()

        val queryId = DnsMessage.id(query)
        val key = DnsMessage.cacheKey(query)
        val cached = key?.let { cache.get(it, queryId) }
        val response = if (cached != null) {
            cacheHits.incrementAndGet()
            cached
        } else {
            resolveUpstream(query, key, queryId)
        }

        IpPacketCodec.buildUdp(
            srcIp = packet.dstIp,
            dstIp = packet.srcIp,
            srcPort = packet.dstPort,
            dstPort = packet.srcPort,
            payload = response,
            mtu = mtu,
        ).forEach { outbound.trySend(it) }
    }

    private suspend fun resolveUpstream(query: ByteArray, key: String?, queryId: Int): ByteArray {
        val activePool = pool
        if (activePool == null) {
            failures.incrementAndGet()
            return DnsMessage.servfail(query)
        }

        var response = activePool.resolve(query)
        if (response != null && DnsMessage.questionKey(response) != DnsMessage.questionKey(query)) {
            response = null // mismatched question: never hand a foreign answer to the client
        }
        if (response != null && DnsMessage.isTruncated(response)) {
            val full = activePool.resolveTcp(query)
            if (full != null) response = full
        }

        val finalResponse = response
        if (finalResponse == null) {
            failures.incrementAndGet()
            return DnsMessage.servfail(query)
        }
        if (key != null) cache.put(key, finalResponse)
        return DnsMessage.withId(finalResponse, queryId)
    }

    // ------------------------------------------------------------------ Observers

    /**
     * Publishes counters once per second, but only while someone (UI or QS tile) observes the state.
     * With the app in the background nothing subscribes, so this costs zero wake-ups.
     */
    private fun startStatsTicker() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            repository.observerCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    while (observed) {
                        repository.update {
                            it.copy(
                                totalQueries = totalQueries.get(),
                                cacheHits = cacheHits.get(),
                                failures = failures.get(),
                                upstreams = pool?.stats() ?: emptyList(),
                            )
                        }
                        delay(STATS_INTERVAL_MS)
                    }
                }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeUnderlyingNetwork() {
        networkJob?.cancel()
        networkJob = serviceScope.launch {
            container.connectivityMonitor.underlyingNetwork
                .drop(1)
                .debounce(NETWORK_DEBOUNCE_MS)
                .collect { network ->
                    // Radio handover / Wi‑Fi <-> cellular: re-point accounting and rebuild the socket pool
                    setUnderlyingNetworks(network?.let { arrayOf(it) })
                    lifecycleMutex.withLock {
                        cache.clear()
                        pool?.refresh()
                        pool?.warmUp()
                    }
                }
        }
    }

    /** Profile switch while running: swap the upstream pool in place; the TUN stays up (no DNS gap). */
    private fun observeDnsProfile() {
        settingsJob?.cancel()
        settingsJob = serviceScope.launch {
            dnsSettings.settings
                .map { it.activeProfile }
                .distinctUntilChanged()
                .drop(1)
                .collect { profile ->
                    lifecycleMutex.withLock {
                        val current = tunnel ?: return@withLock
                        val replacement = DnsUpstreamPool(protector, upstreamsFor(profile), serviceScope)
                        replacement.start()
                        val old = pool
                        pool = replacement
                        old?.close()
                        cache.clear()
                        replacement.warmUp()
                        repository.update { it.copy(profileTitle = profile.title) }
                        updateNotification(current.mtu)
                    }
                }
        }
    }

    // ------------------------------------------------------------------ Notification

    private fun promoteToForeground(mtu: Int) {
        ensureChannel()
        val notification = buildNotification(mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(mtu: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(mtu))
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(mtu: Int): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalDnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val profile = dnsSettings.settings.value.activeProfile
        val text = if (mtu > 0) "${profile.title} profili · ${profile.servers.first().address} · MTU $mtu" else "Tünel hazırlanıyor…"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tunnel)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(mainActivityPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.notification_stop), stopIntent)
            .build()
    }

    private fun mainActivityPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "LocalDnsVpnService"

        const val ACTION_START = "com.netpulse.optimizer.vpn.START"
        const val ACTION_STOP = "com.netpulse.optimizer.vpn.STOP"
        const val ACTION_REFRESH = "com.netpulse.optimizer.vpn.REFRESH"

        const val TUN_ADDRESS = "10.111.222.1"
        const val DNS_PRIMARY = "10.111.222.2"
        const val DNS_SECONDARY = "10.111.222.3"
        private const val DNS_PORT = 53

        private const val MAX_PACKET_SIZE = 32_767
        private const val OUTBOUND_QUEUE = 1_024
        private const val MAX_INFLIGHT_QUERIES = 128
        private const val STATS_INTERVAL_MS = 1_000L
        private const val NETWORK_DEBOUNCE_MS = 800L

        private const val CHANNEL_ID = "dns_tunnel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            tryStart(context)
        }

        /**
         * Returns false when the platform refuses a foreground-service start (e.g. from a background
         * context on Android 12+), so callers such as the Quick Settings tile can fall back to the UI.
         */
        fun tryStart(context: Context): Boolean = try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LocalDnsVpnService::class.java).setAction(ACTION_START),
            )
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Foreground service start refused", e)
            false
        } catch (e: SecurityException) {
            Log.w(TAG, "Foreground service start refused", e)
            false
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(Intent(context, LocalDnsVpnService::class.java).setAction(ACTION_STOP))
            }
        }

        fun refresh(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LocalDnsVpnService::class.java).setAction(ACTION_REFRESH),
                )
            }
        }
    }
}
