package com.netpulse.optimizer.domain.model

enum class RadioTechnology(val label: String) {
    NR_SA("5G SA"),
    NR_NSA("5G"),
    LTE_ADVANCED("4.5G"),
    LTE("4G LTE"),
    LEGACY("3G/2G"),
    NONE("Servis yok"),
    UNKNOWN("—"),
}

/** Snapshot of the serving cell as reported by the modem. Null means "not reported / invalid". */
data class RadioMetrics(
    val technology: RadioTechnology = RadioTechnology.UNKNOWN,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val sinrDb: Int? = null,
    /** Android abstract level 0..4. */
    val level: Int? = null,
    val operatorName: String? = null,
    val timestampMs: Long = 0L,
)

data class LatencyMetrics(
    val lastRttMs: Long? = null,
    val avgRttMs: Long? = null,
    val jitterMs: Long? = null,
    val lossPercent: Int = 0,
    val samples: Int = 0,
)

enum class LinkTransport(val label: String) {
    CELLULAR("Mobil veri"),
    WIFI("Wi‑Fi"),
    OTHER("Diğer"),
    NONE("Bağlantı yok"),
}

data class LinkMetrics(
    val transport: LinkTransport = LinkTransport.NONE,
    val validated: Boolean = false,
    val downstreamKbps: Int = 0,
    val upstreamKbps: Int = 0,
    val linkMtu: Int? = null,
    val vpnActive: Boolean = false,
)

enum class SignalQuality(val label: String) {
    WEAK("Zayıf"),
    FAIR("Orta"),
    EXCELLENT("Mükemmel"),
    UNKNOWN("Ölçülüyor"),
}

enum class Bottleneck(val description: String) {
    NONE("Belirgin darboğaz yok"),
    COVERAGE("Kapsama zayıf (düşük RSRP) — baz istasyonuna uzak / bina içi kayıp"),
    INTERFERENCE("Girişim yüksek (düşük SINR) — komşu hücre gürültüsü"),
    CONGESTION("Hücre yoğun (düşük RSRQ) — kaynak bloğu paylaşımı"),
    LATENCY("Yüksek gecikme — çekirdek ağ / omurga kaynaklı"),
    PACKET_LOSS("Paket kaybı — bağlantı kararsız"),
}

data class QualityScore(
    val score: Int = 0,
    val quality: SignalQuality = SignalQuality.UNKNOWN,
    val bottleneck: Bottleneck = Bottleneck.NONE,
    val weakRadio: Boolean = false,
)

data class DiagnosticsSnapshot(
    val radio: RadioMetrics = RadioMetrics(),
    val latency: LatencyMetrics = LatencyMetrics(),
    val link: LinkMetrics = LinkMetrics(),
    val score: QualityScore = QualityScore(),
)

enum class TunnelStatus(val label: String) {
    STOPPED("Pasif"),
    STARTING("Başlatılıyor"),
    RUNNING("Aktif"),
    REFRESHING("Tazeleniyor"),
    ERROR("Hata"),
}

data class UpstreamStats(
    val name: String,
    val address: String,
    val ewmaRttMs: Long?,
    val wins: Long,
    val healthy: Boolean,
)

data class DnsTunnelState(
    val status: TunnelStatus = TunnelStatus.STOPPED,
    val mtu: Int = 0,
    val upstreams: List<UpstreamStats> = emptyList(),
    val totalQueries: Long = 0,
    val cacheHits: Long = 0,
    val failures: Long = 0,
    val lastError: String? = null,
    /** Incremented every time a TUN interface is (re-)established; lets callers await a refresh. */
    val tunnelGeneration: Long = 0,
)
