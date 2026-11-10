package com.netpulse.optimizer.data.vpn

import com.netpulse.optimizer.domain.model.DnsTunnelState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Single source of truth for tunnel state; written by the VPN service, read by UI and optimizer. */
class VpnStateRepository {
    private val _state = MutableStateFlow(DnsTunnelState())
    val state: StateFlow<DnsTunnelState> = _state.asStateFlow()

    fun update(transform: (DnsTunnelState) -> DnsTunnelState) = _state.update(transform)

    fun reset() {
        _state.value = DnsTunnelState()
    }
}
