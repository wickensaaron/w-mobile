package com.nuvio.app.features.streams

/** iOS trial: apply only to automatic selection; manual sources remain available. */
internal expect val balancedAutoPlayEnabled: Boolean
internal expect suspend fun verifyBalancedAutoPlay(stream: StreamItem): Boolean

internal object BalancedAutoPlayPolicy {
    const val MAX_BYTES = 20_000_000_000L
    const val PREFERRED_BYTES = 12_000_000_000L
    const val MAX_ATTEMPTS = 5
    const val FALLBACK_MESSAGE = "AUTO could not confirm an English audio track within the 20 GB limit. Choose a source."
    private val sizePattern = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(GiB|GB|MiB|MB)\\b")

    fun sizeBytes(stream: StreamItem): Long? {
        // Use the individual video size, never a season-pack/folder total.
        return listOfNotNull(stream.behaviorHints.videoSize,
            stream.clientResolve?.stream?.raw?.size).filter { it > 0 }.maxOrNull()
            ?: sizePattern.findAll(listOfNotNull(stream.name, stream.title, stream.description).joinToString(" "))
                .mapNotNull { match ->
                    val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
                    val multiplier = when (match.groupValues[2].lowercase()) {
                        "gib" -> 1_073_741_824.0
                        "gb" -> 1_000_000_000.0
                        "mib" -> 1_048_576.0
                        else -> 1_000_000.0
                    }
                    (amount * multiplier).takeIf { it > 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
                }.maxOrNull()
    }

    fun isEnglish(language: String?): Boolean {
        val code = language?.trim()?.lowercase()?.replace('_', '-') ?: return false
        return code == "english" || code == "eng" || code == "en" || code.startsWith("en-")
    }

    fun isEligible(stream: StreamItem): Boolean {
        if ((sizeBytes(stream) ?: 0L) > MAX_BYTES) return false
        val languages = stream.clientResolve?.stream?.raw?.parsed?.languages.orEmpty()
            .filterNot { it.isBlank() || it.lowercase() in setOf("und", "unknown", "multi", "mul") }
        return languages.isEmpty() || languages.any(::isEnglish)
    }

    fun sizeRank(stream: StreamItem): Int = when (val size = sizeBytes(stream)) {
        null -> 1
        else -> if (size <= PREFERRED_BYTES) 2 else 0
    }
}
