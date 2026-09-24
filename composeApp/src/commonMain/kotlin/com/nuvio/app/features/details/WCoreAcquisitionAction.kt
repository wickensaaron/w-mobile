package com.nuvio.app.features.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.network.WCoreConnectionRepository
import com.nuvio.app.core.network.WCoreConnectionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.wcore_add_to_jellyfin
import nuvio.composeapp.generated.resources.wcore_available
import nuvio.composeapp.generated.resources.wcore_check_failed
import nuvio.composeapp.generated.resources.wcore_connecting
import nuvio.composeapp.generated.resources.wcore_downloading
import nuvio.composeapp.generated.resources.wcore_failed
import nuvio.composeapp.generated.resources.wcore_paused
import nuvio.composeapp.generated.resources.wcore_queued
import nuvio.composeapp.generated.resources.wcore_retry
import nuvio.composeapp.generated.resources.wcore_tmdb_required
import nuvio.composeapp.generated.resources.wcore_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun WCoreAcquisitionAction(meta: MetaDetails) {
    val identity = remember(meta.id, meta.type, meta.imdbId, meta.name, meta.releaseInfo, meta.poster, meta.background) {
        meta.wCoreAcquisitionIdentity()
    } ?: return
    val connectionStatus by WCoreConnectionRepository.status.collectAsStateWithLifecycle()
    val origin by WCoreConnectionRepository.configuredOrigin.collectAsStateWithLifecycle()
    if (connectionStatus == WCoreConnectionStatus.NotConfigured ||
        connectionStatus == WCoreConnectionStatus.SignInRequired
    ) return

    val client = remember { WCoreAcquisitionClient() }
    val scope = rememberCoroutineScope()
    var status by remember(identity, origin) { mutableStateOf<WCoreAcquisitionStatus?>(null) }
    var busy by remember(identity, origin) { mutableStateOf(false) }
    var error by remember(identity, origin) { mutableStateOf(false) }
    var refreshVersion by remember(identity, origin) { mutableIntStateOf(0) }

    LaunchedEffect(connectionStatus, origin) {
        if (connectionStatus != WCoreConnectionStatus.Connected) {
            status = null
            error = false
        }
    }

    LaunchedEffect(identity, origin, connectionStatus, refreshVersion) {
        if (connectionStatus != WCoreConnectionStatus.Connected) return@LaunchedEffect
        do {
            try {
                status = client.status(identity)
                error = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                error = true
                return@LaunchedEffect
            }
            if (status?.state !in setOf(
                    WCoreAcquisitionState.QUEUED, WCoreAcquisitionState.DOWNLOADING,
                    WCoreAcquisitionState.PAUSED,
                )
            ) break
            delay(15_000L)
        } while (true)
    }

    val label = when {
        connectionStatus == WCoreConnectionStatus.Unavailable -> stringResource(Res.string.wcore_unavailable)
        connectionStatus != WCoreConnectionStatus.Connected -> stringResource(Res.string.wcore_connecting)
        error -> stringResource(Res.string.wcore_check_failed)
        status == null -> stringResource(Res.string.wcore_connecting)
        status?.state == WCoreAcquisitionState.AVAILABLE -> stringResource(Res.string.wcore_available)
        status?.state == WCoreAcquisitionState.DOWNLOADING -> {
            val base = stringResource(Res.string.wcore_downloading)
            status?.progressPercent?.let { "$base $it%" } ?: base
        }
        status?.state == WCoreAcquisitionState.PAUSED -> stringResource(Res.string.wcore_paused)
        status?.state == WCoreAcquisitionState.FAILED -> stringResource(Res.string.wcore_failed)
        status?.state == WCoreAcquisitionState.QUEUED -> stringResource(Res.string.wcore_queued)
        !identity.canRequest -> stringResource(Res.string.wcore_tmdb_required)
        else -> ""
    }
    val canAdd = connectionStatus == WCoreConnectionStatus.Connected && !busy && !error &&
        status?.state == WCoreAcquisitionState.NOT_REQUESTED && identity.canRequest
    val canRetry = connectionStatus == WCoreConnectionStatus.Connected && !busy &&
        status?.state == WCoreAcquisitionState.FAILED && status?.canRetry == true &&
        status?.acquisitionId != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Jellyfin", style = MaterialTheme.typography.labelMedium)
                if (label.isNotBlank()) Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canAdd) {
                Button(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            status = client.request(identity)
                            error = false
                            refreshVersion++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            error = true
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(Res.string.wcore_add_to_jellyfin)) }
            } else if (canRetry) {
                OutlinedButton(onClick = {
                    val id = status?.acquisitionId ?: return@OutlinedButton
                    busy = true
                    scope.launch {
                        try {
                            status = client.retry(id)
                            error = false
                            refreshVersion++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            error = true
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(stringResource(Res.string.wcore_retry)) }
            } else if (error || connectionStatus == WCoreConnectionStatus.Unavailable) {
                OutlinedButton(onClick = {
                    WCoreConnectionRepository.retry()
                    error = false
                    refreshVersion++
                }) { Text(stringResource(Res.string.wcore_retry)) }
            }
        }
    }
}
