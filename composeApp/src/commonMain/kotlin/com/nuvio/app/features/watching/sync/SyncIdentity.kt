package com.nuvio.app.features.watching.sync

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerConfigurationRepository

internal const val LegacyUnboundSyncIdentity = "legacy:unbound"

/** The backend and account that own locally queued Nuvio history writes. */
internal fun currentNuvioSyncIdentity(): String? {
    val account = AuthRepository.state.value as? AuthState.Authenticated ?: return null
    val backendUrl = ServerConfigurationRepository.active.value.backendUrl.trim().trimEnd('/')
    return "$backendUrl|${account.userId}"
}
