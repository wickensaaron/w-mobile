package com.nuvio.app.features.streaming

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler

internal data class BritishLiveDestination(val name: String, val url: String)

/** Match only known main-channel names: +1, regional and similarly named channels are not interchangeable. */
internal fun britishLiveDestination(channelName: String): BritishLiveDestination? {
    val name = channelName.trim().uppercase().replace(Regex(" +(?:HD|FHD|UHD|SD)$"), "").trim()
    return when (name) {
        "BBC ONE", "BBC 1", "BBC TWO", "BBC 2", "BBC THREE", "BBC 3", "BBC FOUR", "BBC 4", "CBBC", "CBEEBIES", "BBC NEWS" ->
            BritishLiveDestination("BBC iPlayer", "https://www.bbc.co.uk/iplayer")
        "ITV", "ITV1", "ITV 1", "ITV2", "ITV 2", "ITV3", "ITV 3", "ITV4", "ITV 4" ->
            BritishLiveDestination("ITVX", "https://www.itv.com/watch")
        else -> null
    }
}

@Composable
internal fun BritishLiveSourceButton(channelName: String) {
    val destination = britishLiveDestination(channelName) ?: return
    val handler = LocalUriHandler.current
    var failed by remember(channelName) { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { failed = runCatching { handler.openUri(destination.url) }.isFailure },
            modifier = Modifier.fillMaxWidth()) {
            Text(destination.name + " · choose live channel")
        }
        if (failed) Text("The official player could not be opened. Please try again.")
    }
}
