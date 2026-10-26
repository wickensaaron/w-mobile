package com.nuvio.app.features.downloads

import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults

@OptIn(ExperimentalForeignApi::class)
internal actual object DownloadsStorage {
    private const val payloadKey = "downloads_payload"

    // Profile indices can be reused by a different signed-in account. Never load that
    // account's download URLs, headers or local file records under the new identity.
    private fun ownerKey(): String? = ProfileRepository.state.value.activeProfile?.let { profile ->
        profile.userId.takeIf { it.isNotBlank() }?.let { userId ->
            profile.id.takeIf { it.isNotBlank() }?.let { profileId ->
                "${payloadKey}_${userId}_${profileId}"
            }
        }
    }

    actual fun loadPreferences(): String? = ownerKey()?.let { NSUserDefaults.standardUserDefaults.stringForKey(it + "_preferences") }
    actual fun savePreferences(payload: String) { ownerKey()?.let { NSUserDefaults.standardUserDefaults.setObject(payload, forKey = it + "_preferences") } }
    actual fun availableBytes(): Long? = (platform.Foundation.NSFileManager.defaultManager.attributesOfFileSystemForPath(
        platform.Foundation.NSHomeDirectory(), null)?.get(platform.Foundation.NSFileSystemFreeSize) as? platform.Foundation.NSNumber)?.longLongValue

    actual fun loadPayload(): String? =
        ownerKey()?.let { NSUserDefaults.standardUserDefaults.stringForKey(it) }

    actual fun savePayload(payload: String) {
        ownerKey()?.let { NSUserDefaults.standardUserDefaults.setObject(payload, forKey = it) }
    }
}
