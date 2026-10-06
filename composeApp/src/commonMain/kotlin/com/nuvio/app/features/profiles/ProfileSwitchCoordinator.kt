package com.nuvio.app.features.profiles

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/** Serializes transitions and rejects work queued for an account that has since changed. */
internal class ProfileSwitchCoordinator<Owner> {
    private val mutex = Mutex()

    suspend fun switch(
        expectedOwner: Owner,
        currentOwner: () -> Owner,
        transition: () -> Unit,
    ): Boolean = mutex.withLock {
        // Give the caller's loading state a scheduling opportunity before synchronous resets.
        yield()
        if (currentOwner() != expectedOwner) return@withLock false
        transition()
        true
    }
}
