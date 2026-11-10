package com.netpulse.optimizer.optimizer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import com.netpulse.optimizer.data.connectivity.ConnectivityMonitor
import com.netpulse.optimizer.data.vpn.VpnStateRepository
import com.netpulse.optimizer.domain.SignalScorer
import com.netpulse.optimizer.domain.model.DiagnosticsSnapshot
import com.netpulse.optimizer.domain.model.TunnelStatus
import com.netpulse.optimizer.vpn.LocalDnsVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class OptimizationReport(
    val steps: List<String>,
    val recommendOperatorSettings: Boolean,
    val vpnPermissionRequired: Boolean,
)

/**
 * "Optimize" pipeline. What can legitimately be improved from an unprivileged app:
 *  1. DNS path: rebuild the upstream socket pool (fresh NAT bindings on the current bearer), flush the
 *     answer cache, re-measure MTU and hand over to a fresh TUN — or start the tunnel if it is off.
 *  2. Validation: if the link is losing packets, ask the platform to re-validate it so Android can fail
 *     over (e.g. to Wi‑Fi) or reset the data stall detector.
 *  3. Radio: band / RAT selection is modem-controlled; when coverage or interference is the bottleneck,
 *     the user is taken to the operator network screen to re-select network / preferred mode.
 */
class NetworkOptimizer(
    private val context: Context,
    private val vpnState: VpnStateRepository,
    private val connectivityMonitor: ConnectivityMonitor,
    private val scorer: SignalScorer,
) {

    suspend fun optimize(snapshot: DiagnosticsSnapshot): OptimizationReport = withContext(Dispatchers.Default) {
        val steps = mutableListOf<String>()
        var permissionRequired = false

        when (vpnState.state.value.status) {
            TunnelStatus.RUNNING, TunnelStatus.REFRESHING -> {
                val generation = vpnState.state.value.tunnelGeneration
                LocalDnsVpnService.refresh(context)
                val settled = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
                    vpnState.state.first { it.status == TunnelStatus.RUNNING && it.tunnelGeneration > generation }
                }
                steps += "DNS soket havuzu yeniden kuruldu, önbellek temizlendi"
                steps += if (settled != null) {
                    "VPN tüneli tazelendi (MTU ${settled.mtu})"
                } else {
                    "VPN tüneli tazeleniyor…"
                }
            }
            else -> {
                if (VpnService.prepare(context) == null) {
                    LocalDnsVpnService.start(context)
                    steps += "Güvenli DNS tüneli başlatıldı (1.1.1.1 · 1.0.0.1 · 8.8.8.8)"
                } else {
                    permissionRequired = true
                    steps += "DNS tüneli için VPN izni gerekiyor"
                }
            }
        }

        val underlying = connectivityMonitor.bestUnderlyingNetwork()
        if (underlying != null && snapshot.latency.lossPercent >= LOSS_REVALIDATE_THRESHOLD) {
            connectivityMonitor.reportBroken(underlying)
            steps += "Yüksek paket kaybı: Android ağ doğrulaması yeniden tetiklendi"
        }

        val weak = scorer.isWeakRadio(snapshot.radio)
        steps += if (weak) {
            "Zayıf sinyal/bant tespit edildi → operatör ağ ayarlarına yönlendiriliyor"
        } else {
            "Radyo seviyesi yeterli (${snapshot.score.quality.label}); bant değişikliği gerekmiyor"
        }

        OptimizationReport(steps, recommendOperatorSettings = weak, vpnPermissionRequired = permissionRequired)
    }

    /** Opens the operator network screen, falling back through progressively more generic settings pages. */
    fun openOperatorSettings(activityContext: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            Intent(Settings.ACTION_DATA_ROAMING_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in candidates) {
            try {
                activityContext.startActivity(intent)
                return true
            } catch (_: ActivityNotFoundException) {
                continue
            } catch (_: SecurityException) {
                continue
            }
        }
        return false
    }

    companion object {
        private const val SETTLE_TIMEOUT_MS = 3_000L
        private const val LOSS_REVALIDATE_THRESHOLD = 30
    }
}
