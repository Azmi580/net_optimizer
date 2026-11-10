package com.netpulse.optimizer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netpulse.optimizer.domain.model.DiagnosticsSnapshot
import com.netpulse.optimizer.domain.model.DnsTunnelState
import com.netpulse.optimizer.domain.model.LatencyMetrics
import com.netpulse.optimizer.domain.model.QualityScore
import com.netpulse.optimizer.domain.model.RadioMetrics
import com.netpulse.optimizer.domain.model.RadioTechnology
import com.netpulse.optimizer.domain.model.SignalQuality
import com.netpulse.optimizer.domain.model.TunnelStatus
import com.netpulse.optimizer.domain.model.UpstreamStats
import com.netpulse.optimizer.ui.theme.NetPulseTheme
import com.netpulse.optimizer.ui.theme.QualityColors

@Composable
fun MainScreen(
    state: MainUiState,
    onOptimize: () -> Unit,
    onVpnToggle: (Boolean) -> Unit,
    onRequestPermissions: () -> Unit,
) {
    Scaffold(
        bottomBar = {
            ControlBar(
                isOptimizing = state.isOptimizing,
                vpnEnabled = state.vpnEnabled,
                tunnelStatus = state.tunnel.status,
                onOptimize = onOptimize,
                onVpnToggle = onVpnToggle,
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state.diagnostics)
            if (!state.permissionsGranted) PermissionCard(onRequestPermissions)
            PrimaryMetrics(state.diagnostics)
            SecondaryMetrics(state.diagnostics.radio, state.diagnostics.latency)
            QualityCard(state.diagnostics.score)
            DnsCard(state.tunnel)
            if (state.report.isNotEmpty()) ReportCard(state.report)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ------------------------------------------------------------------ Top

@Composable
private fun Header(diagnostics: DiagnosticsSnapshot) {
    Column {
        Text("NetPulse", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val operator = diagnostics.radio.operatorName ?: "Operatör"
        val link = diagnostics.link
        val validated = if (link.validated) "doğrulandı" else "doğrulanmadı"
        Text(
            "$operator · ${link.transport.label} · $validated",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "RSRP/RSRQ/SINR ve 5G durumu için Telefon ve Konum izni gerekli.",
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            OutlinedButton(onClick = onRequestPermissions) { Text("İzinleri ver") }
        }
    }
}

@Composable
private fun PrimaryMetrics(diagnostics: DiagnosticsSnapshot) {
    val radio = diagnostics.radio
    val latency = diagnostics.latency
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BigMetric(
                label = "Ağ Türü",
                value = radio.technology.label,
                unit = "",
                color = when (radio.technology) {
                    RadioTechnology.NR_SA, RadioTechnology.NR_NSA -> QualityColors.Excellent
                    RadioTechnology.LTE_ADVANCED, RadioTechnology.LTE -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            BigMetric(
                label = "RSRP",
                value = radio.rsrpDbm?.toString() ?: "—",
                unit = "dBm",
                color = rsrpColor(radio.rsrpDbm),
            )
            BigMetric(
                label = "Ping",
                value = latency.lastRttMs?.toString() ?: if (latency.samples > 0) "×" else "—",
                unit = "ms",
                color = rttColor(latency.lastRttMs),
            )
        }
    }
}

@Composable
private fun BigMetric(label: String, value: String, unit: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(104.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color, textAlign = TextAlign.Center, maxLines = 1)
        if (unit.isNotEmpty()) {
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SecondaryMetrics(radio: RadioMetrics, latency: LatencyMetrics) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallMetric("RSRQ", radio.rsrqDb?.let { "$it dB" } ?: "—", Modifier.weight(1f))
        SmallMetric("SINR", radio.sinrDb?.let { "$it dB" } ?: "—", Modifier.weight(1f))
        SmallMetric("Jitter", latency.jitterMs?.let { "$it ms" } ?: "—", Modifier.weight(1f))
        SmallMetric("Kayıp", "%${latency.lossPercent}", Modifier.weight(1f))
    }
}

@Composable
private fun SmallMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ Middle

@Composable
private fun QualityCard(score: QualityScore) {
    val target = qualityColor(score.quality)
    val color by animateColorAsState(target, label = "qualityColor")
    val progress by animateFloatAsState(score.score / 100f, label = "qualityProgress")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sinyal Kalite Skoru", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (score.quality == SignalQuality.UNKNOWN) "—" else score.score.toString(),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    color = color,
                )
                Text(" / 100", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
                    Text(
                        score.quality.label,
                        color = color,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = color,
                trackColor = color.copy(alpha = 0.16f),
            )
            Text(
                "Darboğaz: ${score.bottleneck.description}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DnsCard(tunnel: DnsTunnelState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Güvenli DNS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(tunnel.status)
            }
            if (tunnel.status == TunnelStatus.RUNNING || tunnel.status == TunnelStatus.REFRESHING) {
                val hitRate = if (tunnel.totalQueries > 0) (tunnel.cacheHits * 100 / tunnel.totalQueries) else 0
                Text(
                    "MTU ${tunnel.mtu} · ${tunnel.totalQueries} sorgu · önbellek %$hitRate · hata ${tunnel.failures}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider()
                tunnel.upstreams.forEach { UpstreamRow(it) }
            } else {
                Text(
                    tunnel.lastError ?: "Tünel kapalı: sistem operatör DNS'ini kullanıyor.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (tunnel.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UpstreamRow(upstream: UpstreamStats) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .padding(0.dp),
        ) {
            Surface(
                color = if (upstream.healthy) QualityColors.Excellent else QualityColors.Weak,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxSize(),
            ) {}
        }
        Spacer(Modifier.width(8.dp))
        Text("${upstream.name} ${upstream.address}", fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        Text(upstream.ewmaRttMs?.let { "$it ms" } ?: "—", fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        Text("★${upstream.wins}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(
            status.label,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ReportCard(steps: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Son optimizasyon", style = MaterialTheme.typography.titleMedium)
            steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

// ------------------------------------------------------------------ Bottom

@Composable
private fun ControlBar(
    isOptimizing: Boolean,
    vpnEnabled: Boolean,
    tunnelStatus: TunnelStatus,
    onOptimize: () -> Unit,
    onVpnToggle: (Boolean) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DNS Tüneli (VPN)", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Cloudflare 1.1.1.1 · 1.0.0.1 · Google 8.8.8.8",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vpnEnabled,
                    onCheckedChange = onVpnToggle,
                    enabled = tunnelStatus != TunnelStatus.STARTING && tunnelStatus != TunnelStatus.REFRESHING,
                )
            }
            Button(
                onClick = onOptimize,
                enabled = !isOptimizing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (isOptimizing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Optimize ediliyor…")
                } else {
                    Text("Ağı Optimize Et", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Helpers

@Composable
private fun qualityColor(quality: SignalQuality): Color = when (quality) {
    SignalQuality.EXCELLENT -> QualityColors.Excellent
    SignalQuality.FAIR -> QualityColors.Fair
    SignalQuality.WEAK -> QualityColors.Weak
    SignalQuality.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
}

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
                    radio = RadioMetrics(RadioTechnology.NR_NSA, rsrpDbm = -97, rsrqDb = -11, sinrDb = 9, level = 3, operatorName = "Vodafone TR"),
                    latency = LatencyMetrics(lastRttMs = 41, avgRttMs = 45, jitterMs = 6, lossPercent = 0, samples = 10),
                    score = QualityScore(64, SignalQuality.FAIR),
                ),
                tunnel = DnsTunnelState(
                    status = TunnelStatus.RUNNING,
                    mtu = 1440,
                    totalQueries = 312,
                    cacheHits = 187,
                    upstreams = listOf(
                        UpstreamStats("Cloudflare", "1.1.1.1", 18, 240, true),
                        UpstreamStats("Cloudflare", "1.0.0.1", 21, 52, true),
                        UpstreamStats("Google", "8.8.8.8", 27, 20, true),
                    ),
                ),
            ),
            onOptimize = {},
            onVpnToggle = {},
            onRequestPermissions = {},
        )
    }
}
