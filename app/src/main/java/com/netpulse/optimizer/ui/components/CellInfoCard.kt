package com.netpulse.optimizer.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.netpulse.optimizer.domain.model.CellDetails
import com.netpulse.optimizer.domain.model.LteCellDetails
import com.netpulse.optimizer.domain.model.NrCellDetails
import com.netpulse.optimizer.domain.model.RadioMetrics

/** Serving-cell identity (CellIdentityLte / CellIdentityNr): operator, eNodeB/gNodeB, PCI, TAC, band. */
@Composable
fun CellInfoCard(cell: CellDetails, radio: RadioMetrics, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hücre Bilgisi", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                    Text(
                        radio.technology.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            when {
                cell.permissionMissing -> Muted("Hücre kimliği için konum izni gerekli (Android hücre ID'sini konum verisi sayar).")
                cell.lte == null && cell.nr == null -> Muted("Hücre bilgisi henüz alınamadı.")
                else -> {
                    cell.lte?.let { LteSection(it) }
                    if (cell.lte != null && cell.nr != null) HorizontalDivider()
                    cell.nr?.let { NrSection(it) }
                }
            }
        }
    }
}

@Composable
private fun LteSection(lte: LteCellDetails) {
    SectionTitle("LTE")
    InfoRow("Operatör", operatorText(lte.operatorName, lte.mcc, lte.mnc))
    InfoRow("eNodeB / Sektör", lte.eNodeBId?.let { enb -> "$enb / ${lte.sectorId ?: "—"}" } ?: "—")
    InfoRow("Band", bandsText(lte.bands, prefix = "B", table = LTE_BANDS))
    InfoRow("PCI · TAC", "${lte.pci ?: "—"} · ${lte.tac ?: "—"}")
    InfoRow("EARFCN", lte.earfcn?.toString() ?: "—")
}

@Composable
private fun NrSection(nr: NrCellDetails) {
    SectionTitle(if (nr.registered) "5G NR (SA)" else "5G NR (NSA ikincil taşıyıcı)")
    InfoRow("Operatör", operatorText(nr.operatorName, nr.mcc, nr.mnc))
    InfoRow("gNodeB ID*", nr.gNodeBId?.toString() ?: "—")
    InfoRow("NCI", nr.nci?.toString() ?: "—")
    InfoRow("Band", bandsText(nr.bands, prefix = "n", table = NR_BANDS))
    InfoRow("PCI · TAC", "${nr.pci ?: "—"} · ${nr.tac ?: "—"}")
    InfoRow("NR-ARFCN", nr.nrarfcn?.toString() ?: "—")
    Muted("* gNodeB ID, 24-bit varsayımıyla NCI'dan türetilir; gerçek uzunluk operatöre göre değişir.")
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun operatorText(name: String?, mcc: String?, mnc: String?): String {
    val plmn = if (mcc != null && mnc != null) "$mcc-$mnc" else null
    return listOfNotNull(name, plmn?.let { "($it)" }).joinToString(" ").ifEmpty { "—" }
}

private fun bandsText(bands: List<Int>, prefix: String, table: Map<Int, String>): String =
    if (bands.isEmpty()) {
        "—"
    } else {
        bands.joinToString(", ") { band -> table[band]?.let { "$prefix$band · $it" } ?: "$prefix$band" }
    }

private val LTE_BANDS = mapOf(
    1 to "2100 MHz", 3 to "1800 MHz", 7 to "2600 MHz", 8 to "900 MHz", 20 to "800 MHz",
    28 to "700 MHz", 32 to "1500 MHz", 38 to "2600 TDD", 40 to "2300 TDD", 41 to "2500 TDD",
    42 to "3500 TDD",
)

private val NR_BANDS = mapOf(
    1 to "2100 MHz", 3 to "1800 MHz", 7 to "2600 MHz", 8 to "900 MHz", 20 to "800 MHz",
    28 to "700 MHz", 41 to "2500 MHz", 77 to "3700 MHz", 78 to "3500 MHz", 258 to "26 GHz",
)
