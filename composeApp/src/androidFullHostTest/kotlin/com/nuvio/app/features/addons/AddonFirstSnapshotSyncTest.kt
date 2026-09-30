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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        respondRevision(7)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            remoteUrls.joinToString(prefix = "[", postfix = "]") { url ->
                """{"url":"$url","enabled":false}"""
            },
        ))
        respondRevision(7)

        AddonRepository.pullFromServer(1)

        assertEquals(remoteUrls, AddonRepository.uiState.value.addons.map { it.manifestUrl })
        assertEquals(remoteUrls, AddonStorage.loadInstalledAddonUrls(1))
        assertSnapshotReadsOnly()
        assertNull(server.takeRequest(750, TimeUnit.MILLISECONDS))
    }

    @Test
    fun emptyFirstSnapshotDoesNotPublishProvisionalStarterList(): Unit = runBlocking {
        val starterUrl = "https://catalog.nuvio.tv/manifest.json"
        AddonStorage.saveInstalledAddonUrls(1, listOf(starterUrl))
        AddonStorage.saveAddonEnabledStates(1, mapOf(starterUrl to false))
        AddonStorage.saveStarterBootstrapStatus(1, "pending")
        AddonRepository.initialize()
        respondRevision(0)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("[]"))
        respondRevision(0)

        AddonRepository.pullFromServer(1)

        assertEquals(listOf(starterUrl), AddonStorage.loadInstalledAddonUrls(1))
        assertSnapshotReadsOnly()
        assertNull(server.takeRequest(750, TimeUnit.MILLISECONDS))
    }

    @Test
    fun removingAnAddonUsesCheckedWriteAtPulledRevision(): Unit = runBlocking {
        val urls = (1..2).map { server.url("/addon-$it/manifest.json").toString() }
        seedDisabledLocalAddons(urls)
        respondSnapshot(3, urls)
        AddonRepository.initialize()
        AddonRepository.pullFromServer(1)
        assertSnapshotReadsOnly()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("4"))

        AddonRepository.removeAddon(urls.first())

        val write = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/sync_push_addons_checked", write.requestUrl?.encodedPath)
        val body = write.body.readUtf8()
        assertTrue(body.contains("\"p_expected_revision\":3"))
        assertTrue(body.contains(urls.last()))
        assertTrue(!body.contains(urls.first()))
    }

    @Test
    fun rebaseKeepsAnotherDevicesNewAddonWhileApplyingLocalRemoval() {
        val base = listOf("a", "b").mapIndexed { index, url -> AddonPushItem(url, sortOrder = index) }
        val edited = listOf(base.last())
        val latest = (listOf("a", "b", "c")).mapIndexed { index, url -> AddonPushItem(url, sortOrder = index) }

        assertEquals(listOf("b", "c"), rebaseAddonEdits(base, edited, latest).map { it.url })
    }

    @Test
    fun conflictingRemovalRebasesAndPreservesNewRemoteAddon(): Unit = runBlocking {
        val urls = (1..3).map { server.url("/addon-$it/manifest.json").toString() }
        seedDisabledLocalAddons(urls.take(2))
        respondSnapshot(3, urls.take(2))
        AddonRepository.initialize()
        AddonRepository.pullFromServer(1)
        assertSnapshotReadsOnly()
        server.enqueue(MockResponse().setResponseCode(409).setHeader("Content-Type", "application/json")
            .setBody("""{"code":"40001","message":"Addon sync revision conflict; pull the latest list before retrying"}"""))
        respondSnapshot(4, urls)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("5"))

        AddonRepository.removeAddon(urls.first())

        val rejected = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/sync_push_addons_checked", rejected.requestUrl?.encodedPath)
        assertSnapshotReadsOnly()
        val retried = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/sync_push_addons_checked", retried.requestUrl?.encodedPath)
        val body = retried.body.readUtf8()
        assertTrue(body.contains("\"p_expected_revision\":4"))
        assertTrue(body.contains(urls[1]))
        assertTrue(body.contains(urls[2]))
        assertTrue(!body.contains(urls[0]))
        withTimeout(5_000) {
            AddonRepository.uiState.first { state ->
                state.addons.map { it.manifestUrl } == urls.drop(1)
            }
        }
    }

    private fun seedDisabledLocalAddons(urls: List<String>) {
        AddonStorage.saveInstalledAddonUrls(1, urls)
        AddonStorage.saveAddonEnabledStates(1, urls.associateWith { false })
        AddonStorage.saveStarterBootstrapStatus(1, "done")
        AddonStorage.saveSyncPayload(1, "{}")
    }

    private fun respondSnapshot(revision: Long, urls: List<String>) {
        respondRevision(revision)
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            urls.joinToString(prefix = "[", postfix = "]") { url -> """{"url":"$url","enabled":false}""" },
        ))
        respondRevision(revision)
    }

    private fun respondRevision(revision: Long) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(revision.toString()))
    }

    private fun assertSnapshotReadsOnly() {
        val first = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/sync_get_addon_revision", first.requestUrl?.encodedPath)
        val second = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("GET", second.method)
        assertEquals("/rest/v1/addons", second.requestUrl?.encodedPath)
        val third = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/rest/v1/rpc/sync_get_addon_revision", third.requestUrl?.encodedPath)
    }
}
