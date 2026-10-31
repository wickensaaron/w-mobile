package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.nextReleasedEpisodeAfter
import com.nuvio.app.features.shuffle.EpisodeShuffleRepository
import com.nuvio.app.features.shuffle.ShuffleSurface
import com.nuvio.app.features.shuffle.shuffleEpisodeProgress
import com.nuvio.app.features.shuffle.watchedShuffleEpisodes
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watching.sync.currentNuvioSyncIdentity

object ResumePromptRepository {

    fun markPlayerEntered(videoId: String) {
        ResumePromptStorage.saveWasInPlayer(true)
        ResumePromptStorage.saveLastPlayerVideoId(videoId)
    }

    fun markPlayerExitedNormally() {
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)
    }

    suspend fun consumeResumePrompt(): ContinueWatchingItem? {
        val profileId = ProfileRepository.activeProfileId
        val identity = currentNuvioSyncIdentity()
        val isCurrentContext = {
            ProfileRepository.activeProfileId == profileId && currentNuvioSyncIdentity() == identity
        }
        WatchProgressRepository.ensureLoaded()
        val entry = loadLatestResumePromptEntry(
            refreshProgress = {
                WatchProgressSourceCoordinator.refreshActiveSource(profileId, force = true)
            },
            isCurrentContext = isCurrentContext,
            readProgress = { WatchProgressRepository.uiState.value },
        ) ?: return null
        ResumePromptStorage.saveWasInPlayer(false)
        ResumePromptStorage.saveLastPlayerVideoId(null)

        if (entry.isUnsupportedMobileLiveTvProgress()) return null

        if (entry.isResumable) {
            return entry.toContinueWatchingItem()
        }

        if (!entry.isEpisode) return null

        val meta = MetaDetailsRepository.fetch(
            type = entry.parentMetaType,
            id = entry.parentMetaId,
        ) ?: return null
        if (!isCurrentContext()) return null

        EpisodeShuffleRepository.ensureLoaded()
        val settings = EpisodeShuffleRepository.uiState.value.settings(meta.id, meta.type)
        val nextEpisode = if (settings.enabled) {
            WatchedRepository.ensureLoaded()
            EpisodeShuffleRepository.shuffle.select(
                profileId, meta.id, meta.videos, settings.includeWatched,
                watchedShuffleEpisodes(meta.id, meta.type, meta.videos, WatchedRepository.uiState.value.watchedKeys),
                shuffleEpisodeProgress(meta.id, WatchProgressRepository.uiState.value.entries),
                ShuffleSurface.PLAYBACK, current = entry.seasonNumber!! to entry.episodeNumber!!,
            )
        } else meta.nextReleasedEpisodeAfter(
            seasonNumber = entry.seasonNumber,
            episodeNumber = entry.episodeNumber,
            todayIsoDate = CurrentDateProvider.todayIsoDate(),
        )
        if (nextEpisode == null || !isCurrentContext()) return null

        return entry.toUpNextContinueWatchingItem(nextEpisode).let { item ->
            if (settings.enabled) item.copy(shufflePlayback = true, isReleaseAlert = false, isNewSeasonRelease = false)
            else item
        }
    }
}
