package com.netpulse.optimizer.core.di

import android.content.Context
import com.netpulse.optimizer.data.connectivity.ConnectivityMonitor
import com.netpulse.optimizer.data.diagnostics.NetworkDiagnosticsHelper
import com.netpulse.optimizer.data.vpn.VpnStateRepository
import com.netpulse.optimizer.domain.SignalScorer
import com.netpulse.optimizer.optimizer.NetworkOptimizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual, compile-time-safe dependency graph.
 *
 * The graph has ~6 nodes and a single process, so a hand-written container gives the same
 * guarantees as Hilt without annotation processing (faster CI builds, zero reflection).
 * Everything here is a process-wide singleton shared by the UI and [com.netpulse.optimizer.vpn.LocalDnsVpnService].
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /** Long-lived scope for process-wide hot flows; never cancelled while the process lives. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val signalScorer: SignalScorer = SignalScorer()

    val vpnStateRepository: VpnStateRepository = VpnStateRepository()

    val connectivityMonitor: ConnectivityMonitor = ConnectivityMonitor(appContext, applicationScope)

    val diagnosticsHelper: NetworkDiagnosticsHelper =
        NetworkDiagnosticsHelper(appContext, connectivityMonitor, signalScorer)

    val networkOptimizer: NetworkOptimizer =
        NetworkOptimizer(appContext, vpnStateRepository, connectivityMonitor, signalScorer)
}
