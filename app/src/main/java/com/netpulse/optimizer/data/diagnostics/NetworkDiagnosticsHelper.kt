package com.netpulse.optimizer.data.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.netpulse.optimizer.data.connectivity.ConnectivityMonitor
import com.netpulse.optimizer.domain.SignalScorer
import com.netpulse.optimizer.domain.model.DiagnosticsSnapshot
import com.netpulse.optimizer.domain.model.LatencyMetrics
import com.netpulse.optimizer.domain.model.RadioMetrics
import com.netpulse.optimizer.domain.model.RadioTechnology
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs

/**
 * Reactive radio + latency probe.
 *
 * - Radio: [TelephonyCallback] (SignalStrengthsListener + DisplayInfoListener) — event driven, no polling.
 *   Some modems omit RSRQ/SINR from SignalStrength, so CellInfo is used as a fallback (AOSP API only).
 * - Cell identity: [CellIdentityReader] (eNodeB/gNodeB, PCI, TAC, band).
 * - Latency: TCP handshake RTT to anycast resolvers (1.1.1.1 / 8.8.8.8 :443). A SYN/SYN‑ACK round trip is
 *   equivalent to ICMP echo RTT but needs no raw socket / root.
 *
 * All flows are cold; they stop automatically when the UI stops collecting (battery friendly).
 */
class NetworkDiagnosticsHelper(
    private val context: Context,
    private val connectivityMonitor: ConnectivityMonitor,
    private val scorer: SignalScorer,
    private val cellIdentityReader: CellIdentityReader,
) {

    private val telephony: TelephonyManager = context.getSystemService(TelephonyManager::class.java)

    fun diagnostics(pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS): Flow<DiagnosticsSnapshot> =
        combine(
            radioMetrics(),
            latency(pingIntervalMs),
            connectivityMonitor.linkState,
            cellIdentityReader.cellDetails(),
        ) { radio, latency, link, cell ->
            DiagnosticsSnapshot(
                radio = radio,
                latency = latency,
                link = link,
                cell = cell,
                score = scorer.score(radio, latency),
            )
        }.conflate()

    // ---------------------------------------------------------------- Radio

    fun radioMetrics(): Flow<RadioMetrics> = callbackFlow {
        var signal: SignalStrength? = telephony.signalStrength
        var display: TelephonyDisplayInfo? = null
        var dataNetworkType: Int = readDataNetworkType()

        fun publish() {
            trySend(buildRadio(signal, display, dataNetworkType))
        }

        val signalCallback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                signal = signalStrength
                publish()
            }
        }
        val displayCallback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                display = telephonyDisplayInfo
                dataNetworkType = telephonyDisplayInfo.networkType
                publish()
            }
        }

        val executor = context.mainExecutor
        telephony.registerTelephonyCallback(executor, signalCallback)
        val displayRegistered = try {
            telephony.registerTelephonyCallback(executor, displayCallback)
            true
        } catch (_: SecurityException) {
            false
        }
        publish()

        awaitClose {
            telephony.unregisterTelephonyCallback(signalCallback)
            if (displayRegistered) telephony.unregisterTelephonyCallback(displayCallback)
        }
    }.conflate().distinctUntilChanged { old, new ->
        old.copy(timestampMs = 0) == new.copy(timestampMs = 0)
    }

    private fun readDataNetworkType(): Int =
        if (hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            try {
                telephony.dataNetworkType
            } catch (_: SecurityException) {
                TelephonyManager.NETWORK_TYPE_UNKNOWN
            }
        } else {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }

    private fun buildRadio(signal: SignalStrength?, display: TelephonyDisplayInfo?, dataType: Int): RadioMetrics {
        val nr = signal?.getCellSignalStrengths(CellSignalStrengthNr::class.java)?.firstOrNull()
        val lte = signal?.getCellSignalStrengths(CellSignalStrengthLte::class.java)?.firstOrNull()

        val nrRsrp = nr?.ssRsrp?.valid(-156..-31)
        var rsrp: Int?
        var rsrq: Int?
        var sinr: Int?
        if (nr != null && nrRsrp != null) {
            rsrp = nrRsrp
            rsrq = nr.ssRsrq.valid(-43..20)
            sinr = nr.ssSinr.valid(-23..40)
        } else {
            rsrp = lte?.rsrp?.valid(-140..-43)
            rsrq = lte?.rsrq?.valid(-34..3)
            sinr = lte?.rssnr?.valid(-20..30)
        }

        if ((rsrp == null || rsrq == null || sinr == null) && hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            val fallback = registeredCellStrength()
            if (fallback != null) {
                rsrp = rsrp ?: fallback.first
                rsrq = rsrq ?: fallback.second
                sinr = sinr ?: fallback.third
            }
        }

        return RadioMetrics(
            technology = resolveTechnology(dataType, display, nrRsrp != null),
            rsrpDbm = rsrp,
            rsrqDb = rsrq,
            sinrDb = sinr,
            level = signal?.level,
            operatorName = telephony.networkOperatorName?.takeIf { it.isNotBlank() },
            timestampMs = System.currentTimeMillis(),
        )
    }

    private fun registeredCellStrength(): Triple<Int?, Int?, Int?>? = try {
        val cells: List<CellInfo> = telephony.allCellInfo ?: emptyList()
        val registered = cells.filter { it.isRegistered }
        val nrCell = registered.filterIsInstance<CellInfoNr>().firstOrNull()
        val lteCell = registered.filterIsInstance<CellInfoLte>().firstOrNull()
        when {
            nrCell != null -> {
                val s = nrCell.cellSignalStrength as CellSignalStrengthNr
                Triple(s.ssRsrp.valid(-156..-31), s.ssRsrq.valid(-43..20), s.ssSinr.valid(-23..40))
            }
            lteCell != null -> {
                val s = lteCell.cellSignalStrength
                Triple(s.rsrp.valid(-140..-43), s.rsrq.valid(-34..3), s.rssnr.valid(-20..30))
            }
            else -> null
        }
    } catch (_: SecurityException) {
        null
    }

    private fun resolveTechnology(dataType: Int, display: TelephonyDisplayInfo?, nrMeasured: Boolean): RadioTechnology {
        val override = display?.overrideNetworkType ?: TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
        return when {
            dataType == TelephonyManager.NETWORK_TYPE_NR -> RadioTechnology.NR_SA
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> RadioTechnology.NR_NSA
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
                override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> RadioTechnology.LTE_ADVANCED
            dataType == TelephonyManager.NETWORK_TYPE_LTE ||
                dataType == TelephonyManager.NETWORK_TYPE_IWLAN -> RadioTechnology.LTE
            dataType in LEGACY_TYPES -> RadioTechnology.LEGACY
            nrMeasured -> RadioTechnology.NR_NSA
            dataType == TelephonyManager.NETWORK_TYPE_UNKNOWN && display == null -> RadioTechnology.UNKNOWN
            else -> RadioTechnology.NONE
        }
    }

    // ---------------------------------------------------------------- Latency

    fun latency(intervalMs: Long = DEFAULT_PING_INTERVAL_MS): Flow<LatencyMetrics> = flow {
        val window = ArrayDeque<Long?>()
        while (currentCoroutineContext().isActive) {
            val rtt = probeOnce()
            window.addLast(rtt)
            while (window.size > WINDOW_SIZE) window.removeFirst()

            val ok = window.filterNotNull()
            val avg = if (ok.isEmpty()) null else ok.average().toLong()
            val jitter = if (ok.size < 2) null else ok.zipWithNext { a, b -> abs(a - b) }.average().toLong()
            val loss = ((window.size - ok.size) * 100) / window.size

            emit(
                LatencyMetrics(
                    lastRttMs = rtt,
                    avgRttMs = avg,
                    jitterMs = jitter,
                    lossPercent = loss,
                    samples = window.size,
                    history = window.toList(),
                ),
            )
            delay(intervalMs)
        }
    }.flowOn(Dispatchers.IO)

    /** Parallel TCP handshakes to all targets; the fastest wins (anycast path to nearest PoP). */
    suspend fun probeOnce(): Long? = coroutineScope {
        PING_TARGETS.map { target -> async { tcpHandshakeRtt(target) } }.awaitAll().filterNotNull().minOrNull()
    }

    private fun tcpHandshakeRtt(address: InetAddress): Long? {
        val socket = Socket()
        return try {
            socket.tcpNoDelay = true
            val start = System.nanoTime()
            socket.connect(InetSocketAddress(address, PING_PORT), PING_TIMEOUT_MS)
            (System.nanoTime() - start) / 1_000_000L
        } catch (_: IOException) {
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun Int.valid(range: IntRange): Int? =
        if (this == CellInfo.UNAVAILABLE || this !in range) null else this

    companion object {
        const val DEFAULT_PING_INTERVAL_MS = 2_000L

        /** 15 samples × 2 s = the 30-second window shown in the sparkline. */
        const val WINDOW_SIZE = 15
        private const val PING_PORT = 443
        private const val PING_TIMEOUT_MS = 1_500

        private val PING_TARGETS: List<InetAddress> = listOf(
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
            InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8)),
        )

        private val LEGACY_TYPES = setOf(
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_GSM,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_EHRPD,
        )
    }
}
