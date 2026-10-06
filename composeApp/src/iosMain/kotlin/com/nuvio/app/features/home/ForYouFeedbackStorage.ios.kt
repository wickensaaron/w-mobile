package com.nuvio.app.features.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSUserDefaults

@Composable
internal actual fun rememberForYouFeedbackStorage(): ForYouFeedbackStorage = remember {
    object : ForYouFeedbackStorage {
        override fun load(key: String): String? =
            NSUserDefaults.standardUserDefaults.stringForKey("w_for_you_$key")

        override fun save(key: String, payload: String) {
            NSUserDefaults.standardUserDefaults.setObject(payload, forKey = "w_for_you_$key")
        }
    }
}
