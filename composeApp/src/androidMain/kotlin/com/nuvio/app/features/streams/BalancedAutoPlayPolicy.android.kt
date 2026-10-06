package com.nuvio.app.features.streams

internal actual val balancedAutoPlayEnabled: Boolean = false
internal actual suspend fun verifyBalancedAutoPlay(stream: StreamItem): Boolean = true
