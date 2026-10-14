package com.nuvio.app.core.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileActivityIsolationTest {
    @Test
    fun progressRefreshRunsWhileSettingsRequestIsBlocked() = runBlocking {
        val coordinator = ProfileSyncRequestCoordinator()
        val settingsStarted = CompletableDeferred<Unit>()
        val holdSettings = CompletableDeferred<Unit>()
        val progressRead = CompletableDeferred<Unit>()
        coordinator.launch(this, 1, ProfileSyncRequestKind.Full) {
            runOrderedProfileSync(
                profileId = 1, pluginsEnabled = false,
                operations = ProfileSyncOperations(
                    pullAddons = {}, pullPlugins = {},
                    pullProfileSettings = { settingsStarted.complete(Unit); holdSettings.await() },
                    syncProviderCredentials = {}, pullLibrary = {},
                    refreshActiveWatchSource = {}, pullCollections = {}, pullHomeCatalogSettings = {},
                ),
            )
        }
        settingsStarted.await()
        val result = coordinator.launch(this, 1, ProfileSyncRequestKind.Activity) {
            runActivityProfileSync(
                profileId = 1,
                operations = ProfileActivitySyncOperations(
                    pullLibrary = {}, pullWatchActivity = { progressRead.complete(Unit) },
                ),
            )
        }
        assertEquals(ProfileSyncRequestResult.Started, result)
        progressRead.await()
        assertFalse(holdSettings.isCompleted)
        coordinator.cancel()
    }

    @Test
    fun settingsRefreshDoesNotCancelProgressUploadInFlight() = runBlocking {
        val coordinator = ProfileSyncRequestCoordinator()
        val uploadStarted = CompletableDeferred<Unit>()
        val finishUpload = CompletableDeferred<Unit>()
        val uploadCompleted = CompletableDeferred<Unit>()
        val settingsCompleted = CompletableDeferred<Unit>()
        coordinator.launch(this, 1, ProfileSyncRequestKind.Activity) {
            uploadStarted.complete(Unit)
            finishUpload.await()
            uploadCompleted.complete(Unit)
        }
        uploadStarted.await()
        assertEquals(
            ProfileSyncRequestResult.Started,
            coordinator.launch(this, 1, ProfileSyncRequestKind.Full) { settingsCompleted.complete(Unit) },
        )
        settingsCompleted.await()
        assertFalse(uploadCompleted.isCompleted)
        finishUpload.complete(Unit)
        uploadCompleted.await()
        coordinator.cancel()
    }

    @Test
    fun accountClearCancelsBothRequestsBeforeAnotherAccountStarts() = runBlocking {
        val coordinator = ProfileSyncRequestCoordinator()
        val starts = List(2) { CompletableDeferred<Unit>() }
        val cancellations = List(2) { CompletableDeferred<Unit>() }
        listOf(ProfileSyncRequestKind.Full, ProfileSyncRequestKind.Activity).forEachIndexed { index, kind ->
            coordinator.launch(this, 1, kind) {
                starts[index].complete(Unit)
                try { CompletableDeferred<Unit>().await() }
                finally { cancellations[index].complete(Unit) }
            }
        }
        starts.forEach { it.await() }
        coordinator.cancel()
        cancellations.forEach { it.await() }
        val newAccountRead = CompletableDeferred<Unit>()
        assertEquals(
            ProfileSyncRequestResult.Started,
            coordinator.launch(this, 2, ProfileSyncRequestKind.Activity) { newAccountRead.complete(Unit) },
        )
        newAccountRead.await()
        assertTrue(cancellations.all { it.isCompleted })
        coordinator.cancel()
    }
}
