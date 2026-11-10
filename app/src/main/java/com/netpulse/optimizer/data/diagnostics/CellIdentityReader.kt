package com.netpulse.optimizer.data.diagnostics

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.netpulse.optimizer.domain.model.CellDetails
import com.netpulse.optimizer.domain.model.LteCellDetails
import com.netpulse.optimizer.domain.model.NrCellDetails
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Reads serving-cell identity through the public CellIdentityLte / CellIdentityNr APIs.
 * Requires ACCESS_FINE_LOCATION (Android treats cell IDs as location data). No OEM or hidden APIs.
 */
class CellIdentityReader(private val context: Context) {

    private val telephony: TelephonyManager = context.getSystemService(TelephonyManager::class.java)

    fun cellDetails(intervalMs: Long = DEFAULT_INTERVAL_MS): Flow<CellDetails> = flow {
        while (currentCoroutineContext().isActive) {
            if (!hasFineLocation()) {
                emit(CellDetails(permissionMissing = true))
            } else {
                emit(parse(fetchCells()))
            }
            delay(intervalMs)
        }
    }.distinctUntilChanged()

    private suspend fun fetchCells(): List<CellInfo> = try {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            suspendCancellableCoroutine<List<CellInfo>> { continuation ->
                telephony.requestCellInfoUpdate(
                    context.mainExecutor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: List<CellInfo>) {
                            if (continuation.isActive) continuation.resume(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            if (continuation.isActive) continuation.resume(cachedCells())
                        }
                    },
                )
            }
        } ?: cachedCells()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun cachedCells(): List<CellInfo> = try {
        telephony.allCellInfo ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun parse(cells: List<CellInfo>): CellDetails {
        val lte = cells.filterIsInstance<CellInfoLte>()
            .firstOrNull { it.isRegistered }
            ?.let { lteDetails(it.cellIdentity) }

        val nrCells = cells.filterIsInstance<CellInfoNr>()
        val nrCell = nrCells.firstOrNull { it.isRegistered } ?: nrCells.firstOrNull()
        val nr = nrCell?.let { info ->
            (info.cellIdentity as? CellIdentityNr)?.let { nrDetails(it, info.isRegistered) }
        }
        return CellDetails(lte = lte, nr = nr)
    }

    private fun lteDetails(id: CellIdentityLte): LteCellDetails {
        val ci = id.ci.validIn(0..0x0FFFFFFF)
        return LteCellDetails(
            mcc = id.mccString,
            mnc = id.mncString,
            operatorName = id.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() },
            cellId = ci,
            eNodeBId = ci?.let { it ushr 8 },
            sectorId = ci?.let { it and 0xFF },
            pci = id.pci.validIn(0..503),
            tac = id.tac.validIn(0..65_535),
            earfcn = id.earfcn.validIn(0..262_143),
            bands = id.bands.filter { it > 0 },
        )
    }

    private fun nrDetails(id: CellIdentityNr, registered: Boolean): NrCellDetails {
        val nci = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG && it in 0L..MAX_NCI }
        return NrCellDetails(
            mcc = id.mccString,
            mnc = id.mncString,
            operatorName = id.operatorAlphaLong?.toString()?.takeIf { it.isNotBlank() },
            nci = nci,
            gNodeBId = nci?.let { it ushr (36 - ASSUMED_GNB_ID_BITS) },
            pci = id.pci.validIn(0..1007),
            tac = id.tac.validIn(0..16_777_215),
            nrarfcn = id.nrarfcn.validIn(0..3_279_165),
            bands = id.bands.filter { it > 0 },
            registered = registered,
        )
    }

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun Int.validIn(range: IntRange): Int? =
        if (this == CellInfo.UNAVAILABLE || this !in range) null else this

    private companion object {
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val REQUEST_TIMEOUT_MS = 3_000L
        const val MAX_NCI = 0xFFFFFFFFFL
        const val ASSUMED_GNB_ID_BITS = 24
    }
}
