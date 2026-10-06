package com.nuvio.app.core.auth

import kotlinx.coroutines.flow.MutableStateFlow

/** Installs an owner without logging in over the network; restore the returned state in teardown. */
internal fun replaceTestAuthState(state: AuthState): AuthState {
    val field = AuthRepository::class.java.getDeclaredField("_state").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    val mutableState = field.get(AuthRepository) as MutableStateFlow<AuthState>
    return mutableState.value.also { mutableState.value = state }
}
