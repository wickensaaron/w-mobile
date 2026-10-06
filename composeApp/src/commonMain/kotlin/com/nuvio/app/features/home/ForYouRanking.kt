package com.nuvio.app.features.home

internal fun forYouType(type: String): String? = when (type.lowercase()) {
    "movie", "film" -> "movie"
    "series", "tv", "show", "tvshow", "anime" -> "series"
    else -> null
}

internal fun forYouKey(type: String, id: String): String =
    "${forYouType(type) ?: type}:${id.lowercase()}"

internal fun forYouFeedbackKey(accountId: String?, profileId: Int): String =
    "dismissed_${accountId ?: "guest"}_$profileId"

internal data class ForYouSeed(val id: String, val type: String, val name: String, val weight: Double)
internal data class ForYouBatch(val seed: ForYouSeed, val items: List<MetaPreview>)
internal data class ForYouPick(val item: MetaPreview, val reasons: List<String>, val score: Double)

internal fun visibleForYou(picks: List<ForYouPick>, dismissed: Set<String>): List<ForYouPick> =
    picks.filterNot { forYouKey(it.item.type, it.item.id) in dismissed }.take(24)

/** Each distinct seed votes once; agreement beats one seed's first result. */
internal fun rankForYou(batches: List<ForYouBatch>, excluded: Set<String>, limit: Int = 24): List<ForYouPick> {
    val candidates = linkedMapOf<String, ForYouPick>()
    batches.distinctBy { forYouKey(it.seed.type, it.seed.id) }.forEach { batch ->
        batch.items.distinctBy { forYouKey(it.type, it.id) }.forEachIndexed { index, item ->
            val key = forYouKey(item.type, item.id)
            if (key in excluded || forYouType(item.type) == null || item.poster.isNullOrBlank()) return@forEachIndexed
            val previous = candidates[key]
            candidates[key] = ForYouPick(
                item, (previous?.reasons.orEmpty() + batch.seed.name).distinct(),
                (previous?.score ?: 0.0) + batch.seed.weight * (1.0 + 1.0 / (index + 1)),
            )
        }
    }
    val remaining = candidates.values.sortedWith(compareByDescending<ForYouPick> { it.score }.thenBy { it.item.id }).toMutableList()
    val selected = mutableListOf<ForYouPick>()
    while (remaining.isNotEmpty() && selected.size < limit) {
        // Avoid three adjacent picks from the same strongest seed when an alternative exists.
        val previousReasons = selected.takeLast(2).map { it.reasons.first() }
        val alternative = if (previousReasons.size == 2 && previousReasons.distinct().size == 1)
            remaining.indexOfFirst { it.reasons.first() != previousReasons.first() } else -1
        selected += remaining.removeAt(if (alternative >= 0) alternative else 0)
    }
    return selected
}
