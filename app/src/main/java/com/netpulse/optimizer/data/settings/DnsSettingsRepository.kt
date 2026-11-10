package com.netpulse.optimizer.data.settings

import android.content.Context
import android.net.InetAddresses
import com.netpulse.optimizer.domain.model.DnsProfileId
import com.netpulse.optimizer.domain.model.DnsSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

/** Persists the selected DNS profile and custom resolver IPs (SharedPreferences, tiny & synchronous). */
class DnsSettingsRepository(context: Context) {

    sealed interface SaveResult {
        data object Saved : SaveResult
        data class Invalid(val inputs: List<String>) : SaveResult
        data object Empty : SaveResult
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<DnsSettings> = _settings.asStateFlow()

    fun selectProfile(id: DnsProfileId) {
        save(_settings.value.copy(profileId = id))
    }

    /** Validates literal IPv4/IPv6 addresses (no DNS lookup) and switches to the CUSTOM profile. */
    fun saveCustomServers(inputs: List<String>): SaveResult {
        val trimmed = inputs.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return SaveResult.Empty
        val parsed = trimmed.map { it to parse(it) }
        val invalid = parsed.filter { it.second == null }.map { it.first }
        if (invalid.isNotEmpty()) return SaveResult.Invalid(invalid)
        val normalized = parsed.mapNotNull { it.second }.distinct()
        save(DnsSettings(profileId = DnsProfileId.CUSTOM, customServers = normalized))
        return SaveResult.Saved
    }

    private fun parse(input: String): String? {
        val address: InetAddress = try {
            InetAddresses.parseNumericAddress(input)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isMulticastAddress) return null
        val text = address.hostAddress ?: return null
        // Our own virtual resolver addresses would create a forwarding loop.
        if (text.startsWith(VIRTUAL_PREFIX)) return null
        return text
    }

    private fun load(): DnsSettings {
        val id = runCatching { DnsProfileId.valueOf(prefs.getString(KEY_PROFILE, null) ?: DnsProfileId.SPEED.name) }
            .getOrDefault(DnsProfileId.SPEED)
        val servers = prefs.getString(KEY_CUSTOM, "").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return DnsSettings(id, servers)
    }

    private fun save(settings: DnsSettings) {
        prefs.edit()
            .putString(KEY_PROFILE, settings.profileId.name)
            .putString(KEY_CUSTOM, settings.customServers.joinToString(","))
            .apply()
        _settings.value = settings
    }

    private companion object {
        const val PREFS_NAME = "dns_settings"
        const val KEY_PROFILE = "profile"
        const val KEY_CUSTOM = "custom_servers"
        const val VIRTUAL_PREFIX = "10.111.222."
    }
}
