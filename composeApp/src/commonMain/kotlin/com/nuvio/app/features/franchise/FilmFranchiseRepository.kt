package com.nuvio.app.features.franchise

import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.TmdbCollectionSort
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.TmdbCollectionSourceType
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class FilmFranchisePreview(val id: Int, val name: String, val artwork: String?)
data class FilmFranchise(val id: Int, val name: String, val artwork: String?, val films: List<MetaPreview>)
data class FranchiseCategory(val name: String, val queries: List<String>)

object FilmFranchiseRepository {
    const val MCU_ID = 0
    private val mcuPreview = FilmFranchisePreview(MCU_ID, "Marvel Cinematic Universe", null)
    val categories = listOf(
        FranchiseCategory("Popular", listOf("James Bond", "Pirates of the Caribbean", "Harry Potter", "Star Wars", "The Avengers", "Jurassic Park", "Fast & Furious", "The Lord of the Rings", "The Hunger Games", "Mission: Impossible", "The Matrix", "Indiana Jones", "The Dark Knight", "Avatar", "Spider-Man", "Batman")),
        FranchiseCategory("Superheroes", listOf("The Avengers", "Iron Man", "Captain America", "Thor", "Guardians of the Galaxy", "Black Panther", "Doctor Strange", "Ant-Man", "Spider-Man", "Deadpool", "X-Men", "Venom", "Superman", "Batman", "Wonder Woman", "Aquaman", "Shazam!", "The Incredibles")),
        FranchiseCategory("Adventure & sci-fi", listOf("Star Wars", "Star Trek", "Jurassic Park", "Avatar", "Dune", "The Matrix", "Alien", "Predator", "Terminator", "Planet of the Apes", "Back to the Future", "Indiana Jones", "Men in Black", "Transformers", "Ghostbusters", "The Mummy", "National Treasure", "Pirates of the Caribbean")),
        FranchiseCategory("Fantasy", listOf("Harry Potter", "Fantastic Beasts", "The Lord of the Rings", "The Hobbit", "The Chronicles of Narnia", "The Hunger Games", "Twilight", "Percy Jackson", "Jumanji", "How to Train Your Dragon", "Shrek", "Frozen", "Maleficent")),
        FranchiseCategory("Animation & family", listOf("Toy Story", "Cars", "Finding Nemo", "Monsters, Inc.", "The Incredibles", "Inside Out", "Despicable Me", "Minions", "Kung Fu Panda", "Madagascar", "Shrek", "Ice Age", "Hotel Transylvania", "The Lego Movie", "Sonic the Hedgehog", "Paddington", "The Secret Life of Pets")),
        FranchiseCategory("Action & crime", listOf("John Wick", "Mission: Impossible", "Fast & Furious", "James Bond", "Bourne", "Die Hard", "The Equalizer", "Taken", "Ocean's", "Bad Boys", "Beverly Hills Cop", "Rush Hour", "Rocky", "Creed", "The Karate Kid", "Rambo", "Top Gun", "Kingsman")),
        FranchiseCategory("Horror", listOf("Scream", "Halloween", "Friday the 13th", "A Nightmare on Elm Street", "The Conjuring", "The Nun", "Annabelle", "Saw", "Final Destination", "Insidious", "The Purge", "A Quiet Place", "It", "The Exorcist", "Child's Play", "Evil Dead", "Paranormal Activity", "The Texas Chainsaw Massacre")),
        FranchiseCategory("Comedy & romance", listOf("Bridget Jones", "The Hangover", "American Pie", "Meet the Parents", "Legally Blonde", "Pitch Perfect", "Mamma Mia!", "Sister Act", "Knives Out", "Zoolander", "Ted", "The Pink Panther")),
    )

    private val mcuFilms = listOf(
        "Iron Man" to 2008, "The Incredible Hulk" to 2008, "Iron Man 2" to 2010, "Thor" to 2011,
        "Captain America: The First Avenger" to 2011, "The Avengers" to 2012, "Iron Man 3" to 2013,
        "Thor: The Dark World" to 2013, "Captain America: The Winter Soldier" to 2014,
        "Guardians of the Galaxy" to 2014, "Avengers: Age of Ultron" to 2015, "Ant-Man" to 2015,
        "Captain America: Civil War" to 2016, "Doctor Strange" to 2016,
        "Guardians of the Galaxy Vol. 2" to 2017, "Spider-Man: Homecoming" to 2017,
        "Thor: Ragnarok" to 2017, "Black Panther" to 2018, "Avengers: Infinity War" to 2018,
        "Ant-Man and the Wasp" to 2018, "Captain Marvel" to 2019, "Avengers: Endgame" to 2019,
        "Spider-Man: Far From Home" to 2019, "Black Widow" to 2021,
        "Shang-Chi and the Legend of the Ten Rings" to 2021, "Eternals" to 2021,
        "Spider-Man: No Way Home" to 2021, "Doctor Strange in the Multiverse of Madness" to 2022,
        "Thor: Love and Thunder" to 2022, "Black Panther: Wakanda Forever" to 2022,
        "Ant-Man and the Wasp: Quantumania" to 2023, "Guardians of the Galaxy Vol. 3" to 2023,
        "The Marvels" to 2023, "Deadpool & Wolverine" to 2024,
        "Captain America: Brave New World" to 2025, "Thunderbolts*" to 2025,
        "The Fantastic Four: First Steps" to 2025, "Spider-Man: Brand New Day" to 2026,
    )

    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<String, List<FilmFranchisePreview>>()
    private val requestLimit = Semaphore(4)

    suspend fun featuredCollections(): List<FilmFranchisePreview> =
        if (TmdbSettingsRepository.effectiveApiKey().isBlank()) emptyList()
        else listOf(mcuPreview) + findCollections(categories.first().queries.take(11))
    suspend fun marvelCollections(): List<FilmFranchisePreview> =
        if (TmdbSettingsRepository.effectiveApiKey().isBlank()) emptyList()
        else listOf(mcuPreview) + findCollections(categories[1].queries)
    suspend fun categoryCollections(category: FranchiseCategory): List<FilmFranchisePreview> =
        (if (category.name == "Popular" || category.name == "Superheroes") listOf(mcuPreview) else emptyList()) + findCollections(category.queries)

    suspend fun searchCollections(query: String): List<FilmFranchisePreview> {
        val term = query.trim()
        if (term.isBlank() || TmdbSettingsRepository.effectiveApiKey().isBlank()) return emptyList()
        val results = requestLimit.withPermit { TmdbCollectionSourceResolver.searchCollections(term) }
        return (if ("marvel cinematic universe".contains(term, ignoreCase = true) || term.equals("mcu", true)) listOf(mcuPreview) else emptyList()) +
            results.mapNotNull { result -> result.name?.let { FilmFranchisePreview(result.id, it, tmdbImage(result.backdropPath ?: result.posterPath)) } }
                .distinctBy { it.id }
    }

    private suspend fun findCollections(queries: List<String>): List<FilmFranchisePreview> = coroutineScope {
        if (TmdbSettingsRepository.effectiveApiKey().isBlank()) return@coroutineScope emptyList()
        val key = TmdbSettingsRepository.effectiveApiKey().hashCode().toString() + queries.joinToString("|")
        cacheMutex.withLock { cache[key] }?.let { return@coroutineScope it }
        val results = queries.map { query -> async {
            try {
                requestLimit.withPermit {
                    val matches = TmdbCollectionSourceResolver.searchCollections(query)
                    val match = matches.firstOrNull { result ->
                        result.name?.contains(query, ignoreCase = true) == true &&
                            result.name.contains("collection", ignoreCase = true)
                    } ?: matches.firstOrNull { it.name?.equals(query, ignoreCase = true) == true }
                    match?.let { FilmFranchisePreview(it.id, it.name ?: query, tmdbImage(it.backdropPath ?: it.posterPath)) }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
        } }.awaitAll().filterNotNull().distinctBy { it.id }
        cacheMutex.withLock { cache[key] = results }
        results
    }

    suspend fun load(collectionId: Int, fallbackName: String): FilmFranchise {
        require(TmdbSettingsRepository.effectiveApiKey().isNotBlank()) {
            "Add a TMDb API key in Settings to browse film collections."
        }
        if (collectionId == MCU_ID) return loadMcu()
        require(collectionId > 0)
        val source = CollectionSource(
            provider = "tmdb", tmdbSourceType = TmdbCollectionSourceType.COLLECTION.name,
            tmdbId = collectionId, mediaType = "MOVIE", sortBy = TmdbCollectionSort.ORIGINAL.value,
        )
        val metadata = TmdbCollectionSourceResolver.importMetadata(TmdbCollectionSourceType.COLLECTION, collectionId)
        val films = TmdbCollectionSourceResolver.resolve(source).items
            .sortedWith(compareBy<MetaPreview> { it.rawReleaseDate?.takeIf(String::isNotBlank) ?: "9999" }.thenBy { it.name })
        return FilmFranchise(collectionId, metadata.title ?: fallbackName,
            films.firstOrNull { it.banner != null }?.banner ?: metadata.coverImageUrl, films)
    }

    private suspend fun loadMcu(): FilmFranchise = coroutineScope {
        val films = mcuFilms.mapIndexed { index, (title, year) -> async {
            try {
                requestLimit.withPermit {
                    val matches = TmdbCollectionSourceResolver.searchMovies(title.removeSuffix("*"), year)
                    matches.firstOrNull { normalize(it.name) == normalize(title) && it.rawReleaseDate?.startsWith(year.toString()) == true }
                        ?: matches.firstOrNull { normalize(it.name).removePrefix("the") == normalize(title).removePrefix("the") }
                        ?: MetaPreview(id = "missing:$index", type = "movie", name = title,
                            releaseInfo = year.toString(), rawReleaseDate = year.toString())
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { MetaPreview(id = "missing:$index", type = "movie", name = title,
                releaseInfo = year.toString(), rawReleaseDate = year.toString()) }
        } }.awaitAll()
        FilmFranchise(MCU_ID, "Marvel Cinematic Universe", films.firstOrNull { it.banner != null }?.banner, films)
    }

    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
}

private fun tmdbImage(path: String?): String? = path?.takeIf(String::isNotBlank)?.let { "https://image.tmdb.org/t/p/w780$it" }
