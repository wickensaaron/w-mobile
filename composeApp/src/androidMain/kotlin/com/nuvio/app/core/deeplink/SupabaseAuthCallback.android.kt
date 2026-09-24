package com.nuvio.app.core.deeplink

import android.content.Intent
import android.net.Uri
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.auth.handleDeeplinks

internal actual fun handlePlatformSupabaseAuthCallback(url: String) {
    SupabaseProvider.client.handleDeeplinks(
        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
        { AuthRepository.clearError() },
        { AuthRepository.reportAuthCallbackFailure() },
    )
}
