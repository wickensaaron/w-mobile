package com.nuvio.app.features.livetv

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

internal actual object LiveTvArchivePlatform {
    actual val clock: LiveTvArchiveClock? = MobileLiveTvArchiveClock
    actual fun client(): HttpClient = HttpClient(OkHttp) {
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000; socketTimeoutMillis = 20_000 }
        engine { config { followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false) } }
    }
}
