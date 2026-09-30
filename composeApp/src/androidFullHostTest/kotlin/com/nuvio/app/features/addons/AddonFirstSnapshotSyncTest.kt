package com.nuvio.app.features.addons

import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.replaceTestAuthState
import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.features.watching.sync.currentNuvioSyncIdentity
import com.russhwolf.settings.SettingsInitializer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AddonFirstSnapshotSyncTest {
    private val server = MockWebServer()
    private lateinit var previousAuthState: AuthState

    @Before
    fun setUp(): Unit = runBlocking {
        previousAuthState = replaceTestAuthState(AuthState.Authenticated("addon-first-snapshot-test", null, false))
        val context = RuntimeEnvironment.getApplication()
        SettingsInitializer().create(context)
        AddonStorage.initialize(context)
        ServerConfigurationStorage.initialize(context)
        AddonRepository.clearLocalState()
        server.start()
        assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
            backendUrl = server.url("/").toString(),
            publishableKey = "test-key",
            capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = false),
            isCustom = true,
        )))
        SupabaseProvider.reset()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        AddonRepository.clearLocalState()
        SupabaseProvider.reset()
        ServerConfigurationRepository.useOfficial()
        server.shutdown()
        replaceTestAuthState(previousAuthState)
    }

    @Test
    fun provisionalStarterEditCannotReplaceExistingAccountAddons(): Unit = runBlocking {
        val starterUrl = "https://catalog.nuvio.tv/manifest.json"
        val owner = assertNotNull(currentNuvioSyncIdentity())
        val remoteUrls = (1..4).map { server.url("/account-addon-$it/manifest.json").toString() }
        AddonStorage.saveInstalledAddonUrls(1, listOf(starterUrl))
        AddonStorage.saveAddonEnabledStates(1, mapOf(starterUrl to false))
        AddonStorage.saveStarterBootstrapStatus(1, "pending")
        AddonStorage.saveSyncPayload(
            1,
            """{"owner":"$owner","pending":{"owner":"$owner","items":[{"url":"$starterUrl","name":"Nuvio Catalog","enabled":false,"sort_order":0}]}}""",
        )
        AddonRepository.initialize()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            remoteUrls.joinToString(prefix = "[", postfix = "]") { url ->
                """{"url":"$url","enabled":false}"""
            },
        ))

        AddonRepository.pullFromServer(1)

        assertEquals(remoteUrls, AddonRepository.uiState.value.addons.map { it.manifestUrl })
        assertEquals(remoteUrls, AddonStorage.loadInstalledAddonUrls(1))
        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/rest/v1/addons", request.requestUrl?.encodedPath)
        assertNull(server.takeRequest(750, TimeUnit.MILLISECONDS))
    }

    @Test
    fun emptyFirstSnapshotDoesNotPublishProvisionalStarterList(): Unit = runBlocking {
        val starterUrl = "https://catalog.nuvio.tv/manifest.json"
        AddonStorage.saveInstalledAddonUrls(1, listOf(starterUrl))
        AddonStorage.saveAddonEnabledStates(1, mapOf(starterUrl to false))
        AddonStorage.saveStarterBootstrapStatus(1, "pending")
        AddonRepository.initialize()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))

        AddonRepository.pullFromServer(1)

        assertEquals(listOf(starterUrl), AddonStorage.loadInstalledAddonUrls(1))
        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", request.method)
        assertEquals("/rest/v1/addons", request.requestUrl?.encodedPath)
        assertNull(server.takeRequest(750, TimeUnit.MILLISECONDS))
    }
}
