package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.WCoreConnectionRepository
import com.nuvio.app.core.network.WCoreConnectionStatus
import nuvio.composeapp.generated.resources.compose_settings_page_debrid
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_mdblist_ratings
import nuvio.composeapp.generated.resources.compose_settings_page_tmdb_enrichment
import nuvio.composeapp.generated.resources.settings_integrations_mdblist_description
import nuvio.composeapp.generated.resources.settings_integrations_debrid_description
import nuvio.composeapp.generated.resources.settings_integrations_section_title
import nuvio.composeapp.generated.resources.settings_integrations_tmdb_description
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.integrationsContent(
    isTablet: Boolean,
    onTmdbClick: () -> Unit,
    onMdbListClick: () -> Unit,
    onDebridClick: () -> Unit,
) {
    item {
        val coreStatus by WCoreConnectionRepository.status.collectAsState()
        val coreOrigin by WCoreConnectionRepository.configuredOrigin.collectAsState()
        val coreDescription = when (coreStatus) {
            WCoreConnectionStatus.NotConfigured -> "Not configured in this build"
            WCoreConnectionStatus.SignInRequired -> "Sign in with email or Google to connect"
            WCoreConnectionStatus.Connecting -> "Connecting to W Core..."
            WCoreConnectionStatus.Connected -> "Connected"
            WCoreConnectionStatus.Unavailable -> "Unavailable. Tap to retry. Local playback still works."
        }
        SettingsSection(title = "W Core", isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                WCoreOriginSettingsRow(coreOrigin = coreOrigin, isTablet = isTablet)
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "W Core connection",
                    description = coreDescription,
                    enabled = coreStatus == WCoreConnectionStatus.Unavailable,
                    isTablet = isTablet,
                    onClick = WCoreConnectionRepository::retry,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = stringResource(Res.string.settings_integrations_section_title),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_tmdb_enrichment),
                    description = stringResource(Res.string.settings_integrations_tmdb_description),
                    iconPainter = integrationLogoPainter(IntegrationLogo.Tmdb),
                    isTablet = isTablet,
                    onClick = onTmdbClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_mdblist_ratings),
                    description = stringResource(Res.string.settings_integrations_mdblist_description),
                    iconPainter = integrationLogoPainter(IntegrationLogo.MdbList),
                    isTablet = isTablet,
                    onClick = onMdbListClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_debrid),
                    description = stringResource(Res.string.settings_integrations_debrid_description),
                    isTablet = isTablet,
                    onClick = onDebridClick,
                )
            }
        }
    }
}

@Composable
private fun WCoreOriginSettingsRow(coreOrigin: String?, isTablet: Boolean) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(coreOrigin, editing) { mutableStateOf(coreOrigin.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsNavigationRow(
        title = "W Core server",
        description = coreOrigin ?: "Add an HTTPS server address to use W Core",
        isTablet = isTablet,
        onClick = { error = null; editing = true },
    )
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("W Core server") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the HTTPS address of your W Core server. Your account and local addons stay available if it is offline.")
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it; error = null },
                        label = { Text("https://core.example.com") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { message -> { Text(message) } },
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (WCoreConnectionRepository.setCustomOrigin(draft)) editing = false
                    else error = "Use a valid HTTPS server address, with no path or sign-in details."
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        if (WCoreConnectionRepository.useDefaultOrigin()) editing = false
                        else error = "Could not reset the saved address."
                    }) { Text("Use build default") }
                    TextButton(onClick = { editing = false }) { Text("Cancel") }
                }
            },
        )
    }
}
