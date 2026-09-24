package com.nuvio.app.core.deeplink

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.handleDeeplinks
import platform.Foundation.NSURL

internal actual fun handlePlatformSupabaseAuthCallback(url: String) {
    SupabaseProvider.client.handleDeeplinks(
        NSURL(string = url),
        { AuthRepository.clearError() },
        { AuthRepository.reportAuthCallbackFailure() },
    )
}
