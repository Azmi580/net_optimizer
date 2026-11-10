package com.netpulse.optimizer.core.di

import android.content.Context
import com.netpulse.optimizer.data.connectivity.ConnectivityMonitor
import com.netpulse.optimizer.data.diagnostics.CellIdentityReader
import com.netpulse.optimizer.data.diagnostics.NetworkDiagnosticsHelper
import com.netpulse.optimizer.data.settings.DnsSettingsRepository
import com.netpulse.optimizer.data.vpn.VpnStateRepository
import com.netpulse.optimizer.domain.SignalScorer
import com.netpulse.optimizer.optimizer.NetworkOptimizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual, compile-time-safe dependency graph.
 *
 * The graph is small and single-process, so a hand-written container gives the same guarantees as
 * Hilt without annotation processing (faster CI builds, zero reflection). Everything here is a
 * process-wide singleton shared by the UI, the VPN service and the Quick Settings tile.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Long-lived scope for process-wide hot flows; never cancelled while the process lives. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val signalScorer: SignalScorer = SignalScorer()

    val vpnStateRepository: VpnStateRepository = VpnStateRepository()

    val dnsSettingsRepository: DnsSettingsRepository = DnsSettingsRepository(appContext)

    val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor(appContext, applicationScope)

    val diagnosticsHelper: NetworkDiagnosticsHelper = NetworkDiagnosticsHelper(
        context = appContext,
        connectivityMonitor = connectivityMonitor,
        scorer = signalScorer,
        cellIdentityReader = CellIdentityReader(appContext),
    )

    val networkOptimizer: NetworkOptimizer =
        NetworkOptimizer(appContext, vpnStateRepository, connectivityMonitor, signalScorer)
}
