package com.netpulse.optimizer.domain

import com.netpulse.optimizer.domain.model.Bottleneck
import com.netpulse.optimizer.domain.model.LatencyMetrics
import com.netpulse.optimizer.domain.model.QualityScore
import com.netpulse.optimizer.domain.model.RadioMetrics
import com.netpulse.optimizer.domain.model.SignalQuality

/**
 * Pure, side-effect-free scoring of radio + latency metrics into a 0..100 score.
 *
 * Thresholds follow common 3GPP field-engineering bands for LTE/NR:
 *  RSRP  ≥ -85 dBm excellent, ≤ -115 dBm unusable
 *  SINR  ≥ 20 dB excellent,   ≤ 0 dB unusable
 *  RSRQ  ≥ -8 dB excellent,   ≤ -18 dB congested
 *  RTT   ≤ 30 ms excellent,   ≥ 200 ms poor
 */
class SignalScorer {

    fun score(radio: RadioMetrics, latency: LatencyMetrics): QualityScore {
        val components = buildList {
            radio.rsrpDbm?.let { add(Component(Bottleneck.COVERAGE, normalize(it.toDouble(), -115.0, -85.0), 0.35)) }
            radio.sinrDb?.let { add(Component(Bottleneck.INTERFERENCE, normalize(it.toDouble(), 0.0, 20.0), 0.25)) }
            radio.rsrqDb?.let { add(Component(Bottleneck.CONGESTION, normalize(it.toDouble(), -18.0, -8.0), 0.15)) }
            latency.avgRttMs?.let { add(Component(Bottleneck.LATENCY, normalize(-it.toDouble(), -200.0, -30.0), 0.25)) }
        }
        if (components.isEmpty()) return QualityScore(weakRadio = isWeakRadio(radio))

        val totalWeight = components.sumOf { it.weight }
        var score = components.sumOf { it.value * it.weight } / totalWeight * 100.0

        // Packet loss is a multiplicative penalty: 10 % loss costs ~25 % of the score.
        if (latency.lossPercent > 0) score *= (1.0 - (latency.lossPercent / 40.0)).coerceIn(0.0, 1.0)

        val finalScore = score.toInt().coerceIn(0, 100)
        val worst = components.minBy { it.value }
        val bottleneck = when {
            latency.lossPercent >= 10 -> Bottleneck.PACKET_LOSS
            worst.value < 0.45 -> worst.kind
            else -> Bottleneck.NONE
        }
        val quality = when {
            finalScore >= 70 -> SignalQuality.EXCELLENT
            finalScore >= 40 -> SignalQuality.FAIR
            else -> SignalQuality.WEAK
        }
        return QualityScore(finalScore, quality, bottleneck, isWeakRadio(radio))
    }

    /** Weak band = coverage or interference so poor that a manual band/network re-selection is worth trying. */
    fun isWeakRadio(radio: RadioMetrics): Boolean {
        val rsrpWeak = radio.rsrpDbm?.let { it <= -110 } ?: false
        val sinrWeak = radio.sinrDb?.let { it < 0 } ?: false
        val levelWeak = radio.level?.let { it <= 1 } ?: false
        return rsrpWeak || sinrWeak || (radio.rsrpDbm == null && levelWeak)
    }

    private fun normalize(value: Double, worst: Double, best: Double): Double =
        ((value - worst) / (best - worst)).coerceIn(0.0, 1.0)

    private data class Component(val kind: Bottleneck, val value: Double, val weight: Double)
}
