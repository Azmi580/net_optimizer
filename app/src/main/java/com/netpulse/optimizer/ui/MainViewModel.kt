package com.netpulse.optimizer.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.netpulse.optimizer.NetPulseApp
import com.netpulse.optimizer.data.diagnostics.NetworkDiagnosticsHelper
import com.netpulse.optimizer.data.settings.DnsSettingsRepository
import com.netpulse.optimizer.data.vpn.VpnStateRepository
import com.netpulse.optimizer.domain.model.DiagnosticsSnapshot
import com.netpulse.optimizer.domain.model.DnsProfileId
import com.netpulse.optimizer.domain.model.DnsSettings
import com.netpulse.optimizer.domain.model.DnsTunnelState
import com.netpulse.optimizer.domain.model.isActive
import com.netpulse.optimizer.optimizer.NetworkOptimizer
import com.netpulse.optimizer.vpn.LocalDnsVpnService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OptimizationUi(
    val running: Boolean = false,
    val steps: List<String> = emptyList(),
)

data class MainUiState(
    val diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot(),
    val tunnel: DnsTunnelState = DnsTunnelState(),
    val optimization: OptimizationUi = OptimizationUi(),
    val permissionsGranted: Boolean = true,
    val dnsSettings: DnsSettings = DnsSettings(),
) {
    val vpnEnabled: Boolean get() = tunnel.status.isActive
}

sealed interface MainEvent {
    data class RequestVpnPermission(val intent: Intent) : MainEvent
    data object OpenOperatorSettings : MainEvent
    data class Message(val text: String) : MainEvent
    data class OptimizationCompleted(val summary: String) : MainEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val app: Application,
    private val diagnosticsHelper: NetworkDiagnosticsHelper,
    private val optimizer: NetworkOptimizer,
    private val dnsSettingsRepository: DnsSettingsRepository,
    vpnRepository: VpnStateRepository,
) : ViewModel() {

    private enum class PendingAction { START, OPTIMIZE }

    /** Bumped after runtime permissions change so the telephony callbacks are re-registered. */
    private val permissionEpoch = MutableStateFlow(0)
    private val optimization = MutableStateFlow(OptimizationUi())
    private val permissionsGranted = MutableStateFlow(hasCorePermissions(app))
    private var pendingAfterVpnPermission: PendingAction? = null

    private val events = Channel<MainEvent>(Channel.BUFFERED)
    val eventFlow: Flow<MainEvent> = events.receiveAsFlow()

    /**
     * True only between Activity ON_START and ON_STOP. While false, TelephonyCallback, cell polling and
     * the ping loop are fully unregistered/cancelled (not just un-collected) and the last snapshot is held,
     * so the background cost is zero and the screen does not flash empty on return.
     */
    private val uiVisible = MutableStateFlow(false)
    @Volatile private var lastSnapshot = DiagnosticsSnapshot()

    private val diagnostics: Flow<DiagnosticsSnapshot> = combine(permissionEpoch, uiVisible) { _, visible -> visible }
        .flatMapLatest { visible ->
            if (visible) {
                diagnosticsHelper.diagnostics().onEach { lastSnapshot = it }
            } else {
                flowOf(lastSnapshot)
            }
        }
        .onStart { emit(lastSnapshot) }

    val uiState: StateFlow<MainUiState> = combine(
        diagnostics,
        vpnRepository.state,
        optimization,
        permissionsGranted,
        dnsSettingsRepository.settings,
    ) { diag, tunnel, opt, granted, dns ->
        MainUiState(diag, tunnel, opt, granted, dns)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    // ------------------------------------------------------------------ Lifecycle

    fun setUiVisible(visible: Boolean) {
        uiVisible.value = visible
    }

    // ------------------------------------------------------------------ Permissions

    fun onPermissionsResult() {
        permissionsGranted.value = hasCorePermissions(app)
        permissionEpoch.value += 1
    }

    fun refreshPermissionsIfChanged() {
        if (hasCorePermissions(app) != permissionsGranted.value) onPermissionsResult()
    }

    // ------------------------------------------------------------------ Tunnel

    fun onVpnToggle(enabled: Boolean) {
        if (enabled) {
            val consent = VpnService.prepare(app)
            if (consent != null) {
                pendingAfterVpnPermission = PendingAction.START
                events.trySend(MainEvent.RequestVpnPermission(consent))
            } else {
                LocalDnsVpnService.start(app)
            }
        } else {
            LocalDnsVpnService.stop(app)
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        val pending = pendingAfterVpnPermission
        pendingAfterVpnPermission = null
        if (!granted) {
            events.trySend(MainEvent.Message("VPN izni reddedildi; DNS tüneli başlatılamadı"))
            return
        }
        when (pending) {
            PendingAction.OPTIMIZE -> optimize()
            PendingAction.START, null -> LocalDnsVpnService.start(app)
        }
    }

    // ------------------------------------------------------------------ DNS profiles

    fun selectDnsProfile(id: DnsProfileId) {
        dnsSettingsRepository.selectProfile(id)
    }

    fun saveCustomDns(primary: String, secondary: String) {
        when (val result = dnsSettingsRepository.saveCustomServers(listOf(primary, secondary))) {
            DnsSettingsRepository.SaveResult.Saved ->
                events.trySend(MainEvent.Message("Özel DNS kaydedildi"))
            DnsSettingsRepository.SaveResult.Empty ->
                events.trySend(MainEvent.Message("En az bir DNS IP adresi girin"))
            is DnsSettingsRepository.SaveResult.Invalid ->
                events.trySend(MainEvent.Message("Geçersiz IP: ${result.inputs.joinToString()}"))
        }
    }

    // ------------------------------------------------------------------ Optimise

    fun optimize() {
        if (optimization.value.running) return
        viewModelScope.launch {
            optimization.update { it.copy(running = true) }
            try {
                val result = optimizer.optimize(uiState.value.diagnostics)
                optimization.value = OptimizationUi(running = false, steps = result.steps)
                if (result.vpnPermissionRequired) {
                    VpnService.prepare(app)?.let { consent ->
                        pendingAfterVpnPermission = PendingAction.OPTIMIZE
                        events.send(MainEvent.RequestVpnPermission(consent))
                    }
                } else {
                    events.send(MainEvent.OptimizationCompleted("Optimizasyon tamamlandı"))
                }
                if (result.recommendOperatorSettings) events.send(MainEvent.OpenOperatorSettings)
            } finally {
                optimization.update { it.copy(running = false) }
            }
        }
    }

    fun openOperatorSettings(activityContext: Context) {
        if (!optimizer.openOperatorSettings(activityContext)) {
            events.trySend(MainEvent.Message("Ağ ayarları açılamadı"))
        }
    }

    companion object {
        val RUNTIME_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )

        private val CORE_PERMISSIONS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        fun hasCorePermissions(context: Context): Boolean = CORE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun factory(app: NetPulseApp): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    app = app,
                    diagnosticsHelper = app.container.diagnosticsHelper,
                    optimizer = app.container.networkOptimizer,
                    dnsSettingsRepository = app.container.dnsSettingsRepository,
                    vpnRepository = app.container.vpnStateRepository,
                )
            }
        }
    }
}
