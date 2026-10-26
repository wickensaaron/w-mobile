package com.nuvio.app.core.sync

import android.content.Context
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.replaceTestAuthState
import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.ServerConfigurationStorage
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.core.network.WCoreOriginStorage
import com.nuvio.app.features.debrid.DebridSettingsStorage
import com.nuvio.app.features.mdblist.MdbListSettingsStorage
import com.nuvio.app.features.player.PlayerSettingsStorage
import com.nuvio.app.features.streaming.StreamingApiConfig
import com.nuvio.app.features.streaming.StreamingAvailabilityRepository
import com.nuvio.app.features.streaming.StreamingAvailabilityStorage
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettingsStorage
import com.russhwolf.settings.SettingsInitializer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscoveryCredentialRestoreTest {
    private val server = MockWebServer()
    private lateinit var previousAuth: AuthState

    @Before
    fun setUp() = runBlocking {
        previousAuth = replaceTestAuthState(AuthState.Authenticated("discovery-test-owner", null, false))
        val context = RuntimeEnvironment.getApplication()
        SettingsInitializer().create(context)
        listOf("nuvio_tmdb_settings", "streaming_availability").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        TmdbSettingsStorage.initialize(context)
        StreamingAvailabilityStorage.initialize(context)
        DebridSettingsStorage.initialize(context)
        MdbListSettingsStorage.initialize(context)
        PlayerSettingsStorage.initialize(context)
        WCoreOriginStorage.initialize(context)
        ServerConfigurationStorage.initialize(context)
        TmdbSettingsRepository.onProfileChanged()
        StreamingAvailabilityRepository.onProfileChanged()
        ProviderCredentialSync.clearAccountState()
        server.start()
        assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
            backendUrl = server.url("/").toString(), publishableKey = "test-key",
            capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = false), isCustom = true,
        )))
        SupabaseProvider.reset()
    }

    @After
    fun tearDown() = runBlocking {
        ProviderCredentialSync.clearAccountState()
        SupabaseProvider.reset()
        ServerConfigurationRepository.useOfficial()
        server.shutdown()
        replaceTestAuthState(previousAuth)
        TmdbSettingsRepository.setApiKey("")
        StreamingAvailabilityRepository.saveConfig("", "direct", "gb")
    }

    @Test
    fun bothKeysRestoreFromTheAccountRpcAndSurviveStorageReload() = runBlocking {
        enqueueCredentials("""{"apiKey":"restored-streaming","source":"rapidapi","country":"us"}""")

        assertTrue(ProviderCredentialSync.syncFromRemote(1))
        assertEquals("restored-tmdb", TmdbSettingsRepository.effectiveApiKey())
        assertEquals(StreamingApiConfig("restored-streaming", "rapidapi", "us"), StreamingAvailabilityRepository.config.value)

        TmdbSettingsRepository.onProfileChanged()
        StreamingAvailabilityRepository.onProfileChanged()
        assertEquals("restored-tmdb", TmdbSettingsRepository.effectiveApiKey())
        assertEquals("restored-streaming", StreamingAvailabilityRepository.config.value.apiKey)
        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/rest/v1/rpc/w_sync_pull_provider_credentials", request.requestUrl?.encodedPath)
        assertTrue(request.body.readUtf8().contains("\"p_profile_id\":1"))
    }

    @Test
    fun invalidStreamingPayloadCannotPartiallyReplaceTheTmdbKey() = runBlocking {
        TmdbSettingsRepository.setApiKey("existing-tmdb")
        StreamingAvailabilityRepository.saveConfig("existing-streaming", "direct", "gb")
        enqueueCredentials("invalid-json")

        assertNotNull(runCatching { ProviderCredentialSync.syncFromRemote(1) }.exceptionOrNull())
        assertEquals("existing-tmdb", TmdbSettingsRepository.effectiveApiKey())
        assertEquals("existing-streaming", StreamingAvailabilityRepository.config.value.apiKey)
    }

    private fun enqueueCredentials(streaming: String) {
        val body = buildJsonArray {
            add(buildJsonObject {
                put("provider", "tmdb")
                put("credential_json", buildJsonObject { put("api_key", "restored-tmdb") })
            })
            add(buildJsonObject {
                put("provider", "streaming_availability")
                put("credential_json", buildJsonObject { put("config", streaming) })
            })
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
    }
}
