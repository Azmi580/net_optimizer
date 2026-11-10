package com.netpulse.optimizer.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.netpulse.optimizer.NetPulseApp
import com.netpulse.optimizer.R
import com.netpulse.optimizer.domain.model.DnsTunnelState
import com.netpulse.optimizer.domain.model.QualityScore
import com.netpulse.optimizer.domain.model.SignalQuality
import com.netpulse.optimizer.domain.model.isActive
import com.netpulse.optimizer.ui.MainActivity
import com.netpulse.optimizer.vpn.LocalDnsVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap toggles the DNS tunnel; the subtitle shows the live network score
 * (e.g. "92/100 • Mükemmel"). Diagnostics only run while the shade is open (onStart/StopListening).
 */
class DnsTileService : TileService() {

    private val container get() = (application as NetPulseApp).container
    private var listeningScope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listeningScope = scope

        val scores = container.diagnosticsHelper.diagnostics()
            .map<_, QualityScore?> { it.score }
            .onStart { emit(null) }
            .distinctUntilChanged()

        scope.launch {
            combine(container.vpnStateRepository.state, scores) { tunnel, score -> tunnel to score }
                .collect { (tunnel, score) -> render(tunnel, score) }
        }
    }

    override fun onStopListening() {
        listeningScope?.cancel()
        listeningScope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val tunnel = container.vpnStateRepository.state.value
        when {
            tunnel.status.isActive -> LocalDnsVpnService.stop(this)
            // VPN consent dialog can only be shown from an activity.
            VpnService.prepare(this) != null -> openApp(startTunnel = true)
            !LocalDnsVpnService.tryStart(this) -> openApp(startTunnel = true)
        }
    }

    private fun render(tunnel: DnsTunnelState, score: QualityScore?) {
        val tile = qsTile ?: return
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_tunnel)
        tile.label = getString(R.string.tile_label)
        tile.state = if (tunnel.status.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (score != null && score.quality != SignalQuality.UNKNOWN) {
            "${score.score}/100 • ${score.quality.label}"
        } else {
            tunnel.status.label
        }
        tile.stateDescription = tunnel.status.label
        tile.updateTile()
    }

    private fun openApp(startTunnel: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_START_TUNNEL, startTunnel)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_OPEN_APP = 2
    }
}
