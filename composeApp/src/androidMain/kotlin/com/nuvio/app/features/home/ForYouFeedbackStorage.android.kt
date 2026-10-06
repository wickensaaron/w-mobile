package com.nuvio.app.features.home

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
internal actual fun rememberForYouFeedbackStorage(): ForYouFeedbackStorage {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        val preferences = context.getSharedPreferences("w_for_you", Context.MODE_PRIVATE)
        object : ForYouFeedbackStorage {
            override fun load(key: String): String? = preferences.getString(key, null)
            override fun save(key: String, payload: String) {
                preferences.edit().putString(key, payload).apply()
            }
        }
    }
}
