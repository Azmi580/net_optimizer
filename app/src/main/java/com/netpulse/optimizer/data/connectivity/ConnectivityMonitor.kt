package com.netpulse.optimizer.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.netpulse.optimizer.domain.model.LinkMetrics
import com.netpulse.optimizer.domain.model.LinkTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Tracks the *physical* (non-VPN) network that actually carries traffic.
 *
 * While our VPN is up, [ConnectivityManager.getActiveNetwork] returns the VPN itself for this UID,
 * so the underlying cellular / Wi‑Fi network is resolved by ranking every non-VPN internet network.
 */
class ConnectivityMonitor(context: Context, scope: CoroutineScope) {

    private val cm: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)

    /** Emits Unit whenever any internet-capable network (VPN included) changes. */
    private val changes: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(Unit)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                trySend(Unit)
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        cm.registerNetworkCallback(request, callback)
        trySend(Unit)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.conflate()

    val linkState: StateFlow<LinkMetrics> = changes
        .map { snapshot() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), snapshot())

    /** The best physical network, de-duplicated; used by the VPN service to re-bind its upstream sockets. */
    val underlyingNetwork: Flow<Network?> = changes
        .map { bestUnderlyingNetwork() }
        .distinctUntilChanged()

    fun bestUnderlyingNetwork(): Network? {
        val active = cm.activeNetwork
        val activeCaps = active?.let { cm.getNetworkCapabilities(it) }
        if (active != null && activeCaps != null && !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return active
        }
        @Suppress("DEPRECATION")
        val all = cm.allNetworks
        return all.mapNotNull { network -> cm.getNetworkCapabilities(network)?.let { network to it } }
            .filter { (_, caps) ->
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .maxByOrNull { (_, caps) -> rank(caps) }
            ?.first
    }

    fun isVpnActive(): Boolean {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    fun linkMtu(network: Network?): Int? {
        val mtu = network?.let { cm.getLinkProperties(it) }?.mtu ?: return null
        return mtu.takeIf { it in 576..9000 }
    }

    fun isCellular(network: Network?): Boolean =
        network?.let { cm.getNetworkCapabilities(it) }
            ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

    /** Forces the platform to re-run captive-portal / validation probes on a broken network. */
    fun reportBroken(network: Network) {
        runCatching { cm.reportNetworkConnectivity(network, false) }
    }

    private fun snapshot(): LinkMetrics {
        val network = bestUnderlyingNetwork() ?: return LinkMetrics(vpnActive = isVpnActive())
        val caps = cm.getNetworkCapabilities(network) ?: return LinkMetrics(vpnActive = isVpnActive())
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LinkTransport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LinkTransport.WIFI
            else -> LinkTransport.OTHER
        }
        return LinkMetrics(
            transport = transport,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            downstreamKbps = caps.linkDownstreamBandwidthKbps,
            upstreamKbps = caps.linkUpstreamBandwidthKbps,
            linkMtu = linkMtu(network),
            vpnActive = isVpnActive(),
        )
    }

    private fun rank(caps: NetworkCapabilities): Int {
        var r = 0
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) r += 100
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) r += 10
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) r += 5
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) r += 3
        return r
    }
}
