package com.netpulse.optimizer.domain.model

enum class DnsProfileId { SPEED, SECURITY, ADBLOCK, CUSTOM }

data class DnsServer(val name: String, val address: String)

data class DnsProfile(
    val id: DnsProfileId,
    val title: String,
    val subtitle: String,
    val servers: List<DnsServer>,
)

/** Built-in resolver presets. All are public, free, plain-DNS anycast resolvers. */
object DnsProfiles {

    val SPEED = DnsProfile(
        id = DnsProfileId.SPEED,
        title = "Hız",
        subtitle = "Cloudflare 1.1.1.1 · Google 8.8.8.8",
        servers = listOf(
            DnsServer("Cloudflare", "1.1.1.1"),
            DnsServer("Cloudflare", "1.0.0.1"),
            DnsServer("Google", "8.8.8.8"),
        ),
    )

    val SECURITY = DnsProfile(
        id = DnsProfileId.SECURITY,
        title = "Güvenlik",
        subtitle = "Quad9 9.9.9.9 · zararlı alan adlarını engeller",
        servers = listOf(
            DnsServer("Quad9", "9.9.9.9"),
            DnsServer("Quad9", "149.112.112.112"),
        ),
    )

    val ADBLOCK = DnsProfile(
        id = DnsProfileId.ADBLOCK,
        title = "Reklam engelleme",
        subtitle = "AdGuard DNS 94.140.14.14 · reklam ve izleyiciler",
        servers = listOf(
            DnsServer("AdGuard", "94.140.14.14"),
            DnsServer("AdGuard", "94.140.15.15"),
        ),
    )

    val presets: List<DnsProfile> = listOf(SPEED, SECURITY, ADBLOCK)

    fun custom(addresses: List<String>): DnsProfile = DnsProfile(
        id = DnsProfileId.CUSTOM,
        title = "Özel",
        subtitle = if (addresses.isEmpty()) "IP adresi girilmedi" else addresses.joinToString(" · "),
        servers = addresses.map { DnsServer("Özel", it) },
    )
}

data class DnsSettings(
    val profileId: DnsProfileId = DnsProfileId.SPEED,
    val customServers: List<String> = emptyList(),
) {
    /** CUSTOM without any saved address falls back to SPEED so the tunnel never runs without upstreams. */
    val activeProfile: DnsProfile
        get() = when (profileId) {
            DnsProfileId.CUSTOM ->
                if (customServers.isEmpty()) DnsProfiles.SPEED else DnsProfiles.custom(customServers)
            else -> DnsProfiles.presets.firstOrNull { it.id == profileId } ?: DnsProfiles.SPEED
        }
}
