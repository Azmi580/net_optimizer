package com.netpulse.optimizer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.netpulse.optimizer.data.diagnostics.NetworkDiagnosticsHelper
import com.netpulse.optimizer.domain.model.CellDetails
import com.netpulse.optimizer.domain.model.DiagnosticsSnapshot
import com.netpulse.optimizer.domain.model.DnsProfileId
import com.netpulse.optimizer.domain.model.DnsTunnelState
import com.netpulse.optimizer.domain.model.LatencyMetrics
import com.netpulse.optimizer.domain.model.LteCellDetails
import com.netpulse.optimizer.domain.model.QualityScore
import com.netpulse.optimizer.domain.model.RadioMetrics
import com.netpulse.optimizer.domain.model.RadioTechnology
import com.netpulse.optimizer.domain.model.SignalQuality
import com.netpulse.optimizer.domain.model.TunnelStatus
import com.netpulse.optimizer.domain.model.UpstreamStats
import com.netpulse.optimizer.ui.components.AmbientBackground
import com.netpulse.optimizer.ui.components.CellInfoCard
import com.netpulse.optimizer.ui.components.DnsProfileCard
import com.netpulse.optimizer.ui.components.GlassCard
import com.netpulse.optimizer.ui.components.LatencyCard
import com.netpulse.optimizer.ui.components.OptimizeButton
import com.netpulse.optimizer.ui.components.RadialGauge
import com.netpulse.optimizer.ui.theme.LocalGlass
import com.netpulse.optimizer.ui.theme.MetricText
import com.netpulse.optimizer.ui.theme.NetPulseTheme
import com.netpulse.optimizer.ui.theme.QualityColors

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    state: MainUiState,
    snackbarHostState: SnackbarHostState,
    onOptimize: () -> Unit,
    onVpnToggle: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
    onSelectDnsProfile: (DnsProfileId) -> Unit,
    onSaveCustomDns: (String, String) -> Unit,
) {
    AmbientBackground(Modifier.background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            containerColor = Color.Transparent,
            // Edge-to-edge: inset by status bar, display cutout and (via bottomBar) navigation bar.
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = { ActionBar(running = state.optimization.running, onOptimize = onOptimize) },
        ) { inner ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .consumeWindowInsets(inner)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Header(state.diagnostics, state.tunnel.status)
                AnimatedVisibility(!state.permissionsGranted) { PermissionCard(onRequestPermissions) }
                PrimaryMetrics(state.diagnostics)
                ScoreCard(state.diagnostics.score)
                SecondaryMetrics(state.diagnostics.radio, state.diagnostics.latency)
                LatencyCard(
                    latency = state.diagnostics.latency,
                    capacity = NetworkDiagnosticsHelper.WINDOW_SIZE,
                    sampleIntervalMs = NetworkDiagnosticsHelper.DEFAULT_PING_INTERVAL_MS,
                )
                DnsCard(state.tunnel, state.vpnEnabled, onVpnToggle)
                DnsProfileCard(
                    settings = state.dnsSettings,
                    onSelectProfile = onSelectDnsProfile,
                    onSaveCustom = onSaveCustomDns,
                )
                CellInfoCard(state.diagnostics.cell, state.diagnostics.radio)
                AnimatedVisibility(
                    visible = state.optimization.steps.isNotEmpty(),
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 },
                ) { ReportCard(state.optimization.steps) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ Top

@Composable
private fun Header(diagnostics: DiagnosticsSnapshot, status: TunnelStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("NetPulse", style = MaterialTheme.typography.headlineMedium)
            val operator = diagnostics.radio.operatorName ?: "Operatör"
            val link = diagnostics.link
            Text(
                "$operator · ${link.transport.label}${if (link.validated) "" else " · doğrulanmadı"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(status)
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Sinyal değerleri, 5G durumu ve hücre bilgisi için Telefon ve Konum izni gerekli.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onRequestPermissions) { Text("İzinleri ver") }
        }
    }
}

@Composable
private fun PrimaryMetrics(diagnostics: DiagnosticsSnapshot) {
    val radio = diagnostics.radio
    val latency = diagnostics.latency
    GlassCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BigMetric(
                label = "AĞ",
                value = radio.technology.label,
                unit = "",
                color = when (radio.technology) {
                    RadioTechnology.NR_SA, RadioTechnology.NR_NSA -> QualityColors.Excellent
                    RadioTechnology.LTE_ADVANCED, RadioTechnology.LTE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            BigMetric(
                label = "RSRP",
                value = radio.rsrpDbm?.toString() ?: "—",
                unit = "dBm",
                color = rsrpColor(radio.rsrpDbm),
                modifier = Modifier.weight(1f),
            )
            BigMetric(
                label = "PING",
                value = latency.lastRttMs?.toString() ?: if (latency.samples > 0) "×" else "—",
                unit = "ms",
                color = rttColor(latency.lastRttMs),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BigMetric(label: String, value: String, unit: String, color: Color, modifier: Modifier = Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 })
            },
            label = "metric-$label",
        ) { shown ->
            Text(shown, style = MetricText.large, color = color, textAlign = TextAlign.Center, maxLines = 1)
        }
        if (unit.isNotEmpty()) {
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ------------------------------------------------------------------ Middle

@Composable
private fun ScoreCard(score: QualityScore) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "SİNYAL KALİTE SKORU",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            RadialGauge(score = score.score, quality = score.quality)
            Text(
                score.bottleneck.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SecondaryMetrics(radio: RadioMetrics, latency: LatencyMetrics) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallMetric("RSRQ", radio.rsrqDb?.let { "$it dB" } ?: "—", Modifier.weight(1f))
        SmallMetric("SINR", radio.sinrDb?.let { "$it dB" } ?: "—", Modifier.weight(1f))
        SmallMetric("JITTER", latency.jitterMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
        SmallMetric("KAYIP", "%${latency.lossPercent}", Modifier.weight(1f))
    }
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(value, style = MetricText.medium, maxLines = 1)
        }
    }
}

/** DNS status + the tunnel switch (moved here from the old bottom bar). */
@Composable
private fun DnsCard(tunnel: DnsTunnelState, vpnEnabled: Boolean, onVpnToggle: (Boolean) -> Unit) {
    val haptics = LocalHapticFeedback.current
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("GÜVENLİ DNS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (vpnEnabled && tunnel.profileTitle.isNotEmpty()) "${tunnel.profileTitle} profili" else tunnel.status.label,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Switch(
                    checked = vpnEnabled,
                    onCheckedChange = { enabled ->
                        haptics.performHapticFeedback(if (enabled) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                        onVpnToggle(enabled)
                    },
                    enabled = tunnel.status != TunnelStatus.STARTING && tunnel.status != TunnelStatus.REFRESHING,
                )
            }
            if (tunnel.status == TunnelStatus.RUNNING || tunnel.status == TunnelStatus.REFRESHING) {
                val hitRate = if (tunnel.totalQueries > 0) tunnel.cacheHits * 100 / tunnel.totalQueries else 0
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip("MTU", tunnel.mtu.toString(), Modifier.weight(1f))
                    StatChip("SORGU", tunnel.totalQueries.toString(), Modifier.weight(1f))
                    StatChip("ÖNBELLEK", "%$hitRate", Modifier.weight(1f))
                    StatChip("HATA", tunnel.failures.toString(), Modifier.weight(1f))
                }
                HorizontalDivider(color = LocalGlass.current.border)
                tunnel.upstreams.forEach { UpstreamRow(it) }
            } else {
                Text(
                    tunnel.lastError ?: "Tünel kapalı: sistem operatörün DNS sunucusunu kullanıyor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tunnel.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MetricText.medium, maxLines = 1)
    }
}

@Composable
private fun UpstreamRow(upstream: UpstreamStats) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = if (upstream.healthy) QualityColors.Excellent else QualityColors.Weak,
            shape = CircleShape,
            modifier = Modifier.size(8.dp),
        ) {}
        Spacer(Modifier.width(10.dp))
        Text("${upstream.name} ${upstream.address}", style = MetricText.mono, modifier = Modifier.weight(1f))
        Text(upstream.ewmaRttMs?.let { "$it ms" } ?: "—", style = MetricText.mono)
        Spacer(Modifier.width(10.dp))
        Text("★${upstream.wins}", style = MetricText.mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusPill(status: TunnelStatus) {
    val color = when (status) {
        TunnelStatus.RUNNING -> QualityColors.Excellent
        TunnelStatus.STARTING, TunnelStatus.REFRESHING -> QualityColors.Fair
        TunnelStatus.ERROR -> QualityColors.Weak
        TunnelStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = color, shape = CircleShape, modifier = Modifier.size(7.dp)) {}
            Spacer(Modifier.width(6.dp))
            Text("DNS ${status.label}", color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ReportCard(steps: List<String>) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("SON OPTİMİZASYON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

// ------------------------------------------------------------------ Bottom

/** Floating action area: only the glowing optimise pill, over a fade so content scrolls beneath it. */
@Composable
private fun ActionBar(running: Boolean, onOptimize: () -> Unit) {
    val background = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, background.copy(alpha = 0.92f), background)))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 14.dp),
    ) {
        OptimizeButton(running = running, onClick = onOptimize)
    }
}

// ------------------------------------------------------------------ Helpers

@Composable
private fun rsrpColor(rsrp: Int?): Color = when {
    rsrp == null -> MaterialTheme.colorScheme.onSurfaceVariant
    rsrp >= -90 -> QualityColors.Excellent
    rsrp >= -105 -> QualityColors.Fair
    else -> QualityColors.Weak
}

@Composable
private fun rttColor(rtt: Long?): Color = when {
    rtt == null -> MaterialTheme.colorScheme.onSurfaceVariant
    rtt <= 50 -> QualityColors.Excellent
    rtt <= 120 -> QualityColors.Fair
    else -> QualityColors.Weak
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    NetPulseTheme {
        MainScreen(
            state = MainUiState(
                diagnostics = DiagnosticsSnapshot(
                    radio = RadioMetrics(RadioTechnology.NR_NSA, rsrpDbm = -97, rsrqDb = -11, sinrDb = 9, level = 3, operatorName = "Operatör"),
                    latency = LatencyMetrics(
                        lastRttMs = 41, avgRttMs = 45, jitterMs = 6, lossPercent = 7, samples = 15,
                        history = listOf(48, 44, 52, 39, null, 41, 47, 60, 43, 40, 42, 55, 46, 44, 41),
                    ),
                    cell = CellDetails(
                        lte = LteCellDetails("286", "02", "Operatör", 12345678, 48225, 78, 211, 41201, 1300, listOf(3)),
                    ),
                    score = QualityScore(82, SignalQuality.EXCELLENT),
                ),
                tunnel = DnsTunnelState(
                    status = TunnelStatus.RUNNING,
                    mtu = 1440,
                    profileTitle = "Hız",
                    totalQueries = 312,
                    cacheHits = 187,
                    upstreams = listOf(
                        UpstreamStats("Cloudflare", "1.1.1.1", 18, 240, true),
                        UpstreamStats("Cloudflare", "1.0.0.1", 21, 52, true),
                        UpstreamStats("Google", "8.8.8.8", 27, 20, true),
                    ),
                ),
            ),
            snackbarHostState = remember { SnackbarHostState() },
            onOptimize = {},
            onVpnToggle = {},
            onRequestPermissions = {},
            onSelectDnsProfile = {},
            onSaveCustomDns = { _, _ -> },
        )
    }
}
