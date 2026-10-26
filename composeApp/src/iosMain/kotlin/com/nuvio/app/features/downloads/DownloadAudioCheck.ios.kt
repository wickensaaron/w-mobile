package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.verifyBalancedAutoPlay

internal actual suspend fun confirmDownloadEnglishAudio(stream: StreamItem): Boolean = verifyBalancedAutoPlay(stream)
