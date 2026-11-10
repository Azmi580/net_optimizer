package com.netpulse.optimizer.vpn

import android.net.Network
import com.netpulse.optimizer.data.connectivity.ConnectivityMonitor

/**
 * Chooses the TUN MTU inside the 1400–1500 window.
 *
 * - If the carrier advertises an MTU (PCO → LinkProperties.getMtu), it is honoured so our synthesized
 *   DNS responses never trigger double fragmentation.
 * - Cellular without an advertised value → 1440: leaves headroom for GTP-U/IPsec overhead seen on
 *   LTE/NR NSA cores while staying far above the largest typical DNS response.
 * - Wi‑Fi / unknown → 1500.
 */
object MtuResolver {
    const val MIN_MTU = 1400
    const val MAX_MTU = 1500
    private const val CELLULAR_FALLBACK_MTU = 1440

    fun resolve(monitor: ConnectivityMonitor, network: Network?): Int {
        val advertised = monitor.linkMtu(network)
        val base = when {
            advertised != null -> advertised
            monitor.isCellular(network) -> CELLULAR_FALLBACK_MTU
            else -> MAX_MTU
        }
        return base.coerceIn(MIN_MTU, MAX_MTU)
    }
}
