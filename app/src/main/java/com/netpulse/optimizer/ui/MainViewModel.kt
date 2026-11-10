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
import com.netpulse.optimizer.data.vpn.VpnStateRepository
import com.netpulse.optimizer.domain.model.DiagnosticsSnapshot
import com.netpulse.optimizer.domain.model.DnsTunnelState
import com.netpulse.optimizer.domain.model.TunnelStatus
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val diagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot(),
    val tunnel: DnsTunnelState = DnsTunnelState(),
    val isOptimizing: Boolean = false,
    val report: List<String> = emptyList(),
    val permissionsGranted: Boolean = true,
) {
    val vpnEnabled: Boolean
        get() = tunnel.status == TunnelStatus.RUNNING ||
            tunnel.status == TunnelStatus.STARTING ||
            tunnel.status == TunnelStatus.REFRESHING
}

sealed interface MainEvent {
    data class RequestVpnPermission(val intent: Intent) : MainEvent
    data object OpenOperatorSettings : MainEvent
    data class Message(val text: String) : MainEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val app: Application,
    private val diagnosticsHelper: NetworkDiagnosticsHelper,
    private val optimizer: NetworkOptimizer,
    vpnRepository: VpnStateRepository,
) : ViewModel() {

    private enum class PendingAction { START, OPTIMIZE }

    /** Bumped after runtime permissions change so the telephony callbacks are re-registered. */
    private val permissionEpoch = MutableStateFlow(0)
    private val optimizing = MutableStateFlow(false)
    private val report = MutableStateFlow<List<String>>(emptyList())
    private val permissionsGranted = MutableStateFlow(hasCorePermissions(app))
    private var pendingAfterVpnPermission: PendingAction? = null

    private val events = Channel<MainEvent>(Channel.BUFFERED)
    val eventFlow: Flow<MainEvent> = events.receiveAsFlow()

    private val diagnostics: Flow<DiagnosticsSnapshot> = permissionEpoch
        .flatMapLatest { diagnosticsHelper.diagnostics() }
        .onStart { emit(DiagnosticsSnapshot()) }

    val uiState: StateFlow<MainUiState> = combine(
        diagnostics,
        vpnRepository.state,
        optimizing,
        report,
        permissionsGranted,
    ) { diag, tunnel, isOptimizing, steps, granted ->
        MainUiState(diag, tunnel, isOptimizing, steps, granted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun onPermissionsResult() {
        permissionsGranted.value = hasCorePermissions(app)
        permissionEpoch.value += 1
    }

    fun refreshPermissionsIfChanged() {
        if (hasCorePermissions(app) != permissionsGranted.value) onPermissionsResult()
    }

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

    fun optimize() {
        if (optimizing.value) return
        viewModelScope.launch {
            optimizing.value = true
            try {
                val result = optimizer.optimize(uiState.value.diagnostics)
                report.value = result.steps
                if (result.vpnPermissionRequired) {
                    VpnService.prepare(app)?.let { consent ->
                        pendingAfterVpnPermission = PendingAction.OPTIMIZE
                        events.send(MainEvent.RequestVpnPermission(consent))
                    }
                }
                if (result.recommendOperatorSettings) events.send(MainEvent.OpenOperatorSettings)
            } finally {
                optimizing.value = false
            }
        }
    }

    fun openOperatorSettings(activityContext: Context) {
        if (!optimizer.openOperatorSettings(activityContext)) {
            events.trySend(MainEvent.Message("Operatör ayarları açılamadı"))
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
                    vpnRepository = app.container.vpnStateRepository,
                )
            }
        }
    }
}
