package com.netpulse.optimizer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.netpulse.optimizer.domain.model.DnsProfileId
import com.netpulse.optimizer.domain.model.DnsProfiles
import com.netpulse.optimizer.domain.model.DnsSettings

/** One-tap resolver presets plus a custom-IP editor. Switching applies live, even while the tunnel runs. */
@Composable
fun DnsProfileCard(
    settings: DnsSettings,
    onSelectProfile: (DnsProfileId) -> Unit,
    onSaveCustom: (primary: String, secondary: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var customExpanded by rememberSaveable { mutableStateOf(settings.profileId == DnsProfileId.CUSTOM) }
    var primary by rememberSaveable(settings.customServers) { mutableStateOf(settings.customServers.getOrElse(0) { "" }) }
    var secondary by rememberSaveable(settings.customServers) { mutableStateOf(settings.customServers.getOrElse(1) { "" }) }
    val focusManager = LocalFocusManager.current

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text(
                "DNS Profili",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Column(Modifier.selectableGroup()) {
                DnsProfiles.presets.forEach { profile ->
                    ProfileRow(
                        title = profile.title,
                        subtitle = profile.subtitle,
                        selected = settings.profileId == profile.id,
                        onClick = {
                            customExpanded = false
                            onSelectProfile(profile.id)
                        },
                    )
                }
                ProfileRow(
                    title = "Özel DNS",
                    subtitle = if (settings.customServers.isEmpty()) {
                        "Kendi DNS IP adresinizi girin"
                    } else {
                        settings.customServers.joinToString(" · ")
                    },
                    selected = settings.profileId == DnsProfileId.CUSTOM,
                    onClick = {
                        customExpanded = true
                        if (settings.customServers.isNotEmpty()) onSelectProfile(DnsProfileId.CUSTOM)
                    },
                )
            }
            AnimatedVisibility(visible = customExpanded) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = primary,
                        onValueChange = { primary = it.trim() },
                        label = { Text("Birincil DNS (ör. 9.9.9.9)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = secondary,
                        onValueChange = { secondary = it.trim() },
                        label = { Text("İkincil DNS (isteğe bağlı)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        onClick = {
                            focusManager.clearFocus()
                            onSaveCustom(primary, secondary)
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Kaydet ve kullan") }
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
