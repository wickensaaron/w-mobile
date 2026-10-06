package com.nuvio.app.features.home

import androidx.compose.runtime.Composable

/** Keys explicitly include account and profile, so delayed actions cannot write to a new owner. */
internal interface ForYouFeedbackStorage {
    fun load(key: String): String?
    fun save(key: String, payload: String)
}

@Composable
internal expect fun rememberForYouFeedbackStorage(): ForYouFeedbackStorage
