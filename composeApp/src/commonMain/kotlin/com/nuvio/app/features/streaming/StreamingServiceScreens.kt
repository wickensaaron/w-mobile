package com.nuvio.app.features.streaming

import kotlinx.coroutines.CancellationException

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage as NuvioAsyncImage
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.stableKey
import com.nuvio.app.features.franchise.FilmFranchisePreview
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.features.franchise.FilmFranchiseRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.streaming_apple_hd
import nuvio.composeapp.generated.resources.streaming_disney
import nuvio.composeapp.generated.resources.streaming_netflix
import nuvio.composeapp.generated.resources.streaming_prime_wordmark_hd
import nuvio.composeapp.generated.resources.streaming_hub_pixar
import nuvio.composeapp.generated.resources.streaming_hub_marvel
import nuvio.composeapp.generated.resources.streaming_hub_starwars
import nuvio.composeapp.generated.resources.streaming_hub_natgeo
import org.jetbrains.compose.resources.painterResource
import kotlinx.coroutines.launch

private data class ServiceStyle(val background: Color, val accent: Color, val glow: Color)

private fun StreamingService.style(): ServiceStyle = when (this) {
    StreamingService.NETFLIX -> ServiceStyle(Color(0xFF141414), Color(0xFFE50914), Color(0xFF6D1520))
    StreamingService.PRIME -> ServiceStyle(Color(0xFF07121F), Color(0xFF28B9E8), Color(0xFF14506A))
    StreamingService.DISNEY -> ServiceStyle(Color(0xFF071827), Color(0xFF78DDF0), Color(0xFF126074))
    StreamingService.APPLE -> ServiceStyle(Color(0xFF090A0D), Color.White, Color(0xFF393B43))
}

@Composable
fun StreamingServiceHomeRow(
    sectionPadding: Dp,
    onServiceClick: (StreamingService) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = "Streaming Services",
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = sectionPadding, vertical = 12.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = sectionPadding),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(StreamingService.entries, key = { it.apiId }) { service ->
                ServiceHomeTile(service, onServiceClick)
            }
        }
    }
}

@Composable
private fun ServiceHomeTile(
    service: StreamingService,
    onClick: (StreamingService) -> Unit,
) {
    val style = service.style()
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .width(194.dp)
            .height(108.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(style.glow.copy(alpha = 0.8f), style.background)))
            .border(1.dp, Color.White.copy(alpha = 0.13f), shape)
            .clickable { onClick(service) },
        contentAlignment = Alignment.Center,
    ) {
        ServiceLogo(
            service,
            when (service) {
                StreamingService.NETFLIX -> Modifier.width(158.dp).height(44.dp)
                StreamingService.PRIME -> Modifier.width(166.dp).height(53.dp)
                StreamingService.DISNEY -> Modifier.fillMaxSize()
                StreamingService.APPLE -> Modifier.width(104.dp).height(53.dp)
            },
        )
    }
}

@Composable
private fun ServiceLogo(service: StreamingService, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(when (service) {
            StreamingService.NETFLIX -> Res.drawable.streaming_netflix
            StreamingService.PRIME -> Res.drawable.streaming_prime_wordmark_hd
            StreamingService.DISNEY -> Res.drawable.streaming_disney
            StreamingService.APPLE -> Res.drawable.streaming_apple_hd
        }),
        contentDescription = service.displayName,
        modifier = modifier,
        contentScale = if (service == StreamingService.DISNEY) ContentScale.Crop else ContentScale.Fit,
        colorFilter = if (service == StreamingService.PRIME) ColorFilter.tint(Color.White) else null,
    )
}

@Composable
fun StreamingServiceScreen(
    service: StreamingService,
    onBack: () -> Unit,
    onFranchiseClick: (Int, String) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val config by StreamingAvailabilityRepository.config.collectAsState()
    var data by remember(service) { mutableStateOf<StreamingServicePageData?>(null) }
    var error by remember(service) { mutableStateOf<String?>(null) }
    var loading by remember(service) { mutableStateOf(false) }
    var refresh by remember(service) { mutableStateOf(0) }
    var disneyHub by remember(service) { mutableStateOf<String?>(null) }
    var hubItems by remember(service) { mutableStateOf<List<MetaPreview>>(emptyList()) }
    var hubLoading by remember(service) { mutableStateOf(false) }
    var marvelCollections by remember(service) { mutableStateOf<List<FilmFranchisePreview>>(emptyList()) }
    var marvelCollectionsLoading by remember(service) { mutableStateOf(false) }
    var editingConfig by remember(service) { mutableStateOf(false) }
    val style = service.style()
    val pageScrollState = rememberLazyListState()

    LaunchedEffect(service, config, refresh) {
        if (!config.isConfigured) {
            data = null
            return@LaunchedEffect
        }
        loading = true
        error = null
        data = null
        runCatching {
            StreamingAvailabilityRepository.loadPage(service, force = refresh > 0)
        }.onSuccess { data = it }
            .onFailure { if (it is CancellationException) throw it; error = it.message ?: "The service could not be loaded." }
        loading = false
    }

    LaunchedEffect(disneyHub, config) {
        val hub = disneyHub ?: return@LaunchedEffect
        if (!config.isConfigured) return@LaunchedEffect
        hubLoading = true
        hubItems = runCatching { StreamingAvailabilityRepository.loadDisneyHub(hub) }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        hubLoading = false
    }

    LaunchedEffect(disneyHub) {
        if (service == StreamingService.DISNEY && disneyHub == "Marvel") {
            marvelCollectionsLoading = true
            marvelCollections = runCatching { FilmFranchiseRepository.marvelCollections() }.getOrElse { if (it is CancellationException) throw it; emptyList() }
            marvelCollectionsLoading = false
        }
    }

    Box(Modifier.fillMaxSize().background(style.background).safeDrawingPadding().imePadding()) {
        LazyColumn(
            state = pageScrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp),
        ) {
        item(key = "header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 13.dp, end = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Back")
                }
                ServiceLogo(service, Modifier.width(126.dp).height(42.dp))
            }
        }
        if (!config.isConfigured || editingConfig) {
            item(key = "setup") { StreamingApiSetup(config, style, onSaved = { editingConfig = false }) }
        } else {
            val page = data
            if (page != null) {
                item(key = "hero") {
                    ServiceHero(service, page, style, config.normalizedCountry, onPosterClick)
                }
                if (service == StreamingService.DISNEY) {
                    item(key = "hubs") {
                        DisneyHubButtons(selected = disneyHub, onSelect = { disneyHub = it })
                    }
                    if (disneyHub != null) {
                        if (disneyHub == "Marvel") {
                            item(key = "marvel_collections") {
                                MarvelFranchiseRow(
                                    marvelCollections,
                                    marvelCollectionsLoading,
                                    onFranchiseClick,
                                )
                            }
                        }
                        item(key = "hub_results") {
                            ServicePosterRow(
                                title = "Explore ${disneyHub!!}",
                                items = hubItems,
                                service = service,
                                style = style,
                                onPosterClick = onPosterClick,
                                emptyMessage = if (hubLoading) "Loading…" else "No title or description matches in this region yet.",
                            )
                        }
                    }
                }
                if (page.topFilms.isNotEmpty()) {
                    item(key = "films") {
                        ServicePosterRow(
                            if (service == StreamingService.APPLE) "Popular Films" else "Top 10 Films",
                            page.topFilms, service, style, onPosterClick, ranked = service != StreamingService.APPLE,
                        )
                    }
                }
                if (page.topSeries.isNotEmpty()) {
                    item(key = "series") {
                        ServicePosterRow(
                            if (service == StreamingService.APPLE) "Popular Series" else "Top 10 Series",
                            page.topSeries, service, style, onPosterClick, ranked = service != StreamingService.APPLE,
                        )
                    }
                }
                if (page.more.isNotEmpty()) {
                    item(key = "more") {
                        ServicePosterRow(
                            if (service == StreamingService.APPLE) "More to Watch" else "Explore ${service.displayName}",
                            page.more, service, style, onPosterClick,
                        )
                    }
                }
                item(key = "refresh") {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        TextButton(onClick = { refresh++ }, colors = ButtonDefaults.textButtonColors(contentColor = style.accent)) {
                            Text("Refresh charts")
                        }
                        TextButton(onClick = { editingConfig = true }, colors = ButtonDefaults.textButtonColors(contentColor = style.accent)) {
                            Text("Region & API key")
                        }
                    }
                }
                item(key = "attribution") {
                    Text(
                        text = "Streaming availability provided by Streaming Availability API by Movie of the Night",
                        color = Color.White.copy(alpha = 0.54f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp)
                            .clickable { uriHandler.openUri("https://www.movieofthenight.com/about/api") },
                    )
                }
            } else {
                item(key = "loading") {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            text = if (loading) "Loading ${service.displayName}…" else error ?: "No titles available.",
                            color = Color.White,
                        )
                        if (error != null) {
                            TextButton(onClick = { refresh++ }, colors = ButtonDefaults.textButtonColors(contentColor = style.accent)) {
                                Text("Try again")
                            }
                            TextButton(onClick = { editingConfig = true }, colors = ButtonDefaults.textButtonColors(contentColor = style.accent)) {
                                Text("Edit API key")
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun MarvelFranchiseRow(
    collections: List<FilmFranchisePreview>,
    loading: Boolean,
    onClick: (Int, String) -> Unit,
) {
    val rowState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Marvel film collections", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold)

        }
        if (collections.isEmpty()) {
            Text(
                if (loading) "Loading collections…" else "Film collections are unavailable. Check the TMDb key in Settings → TMDB Enrichment.",
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            NuvioShelfSection(
                title = "",
                entries = collections,
                rowContentPadding = PaddingValues(horizontal = 16.dp),
                itemSpacing = 14.dp,
                key = { it.id },
                state = rowState,
            ) { collection ->
                    Box(
                        Modifier.width(250.dp).height(143.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(Color(0xFF263244))
                            .clickable { onClick(collection.id, collection.name) },
                    ) {
                        collection.artwork?.let { image ->
                            NuvioAsyncImage(
                                model = image,
                                contentDescription = collection.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))),
                            ),
                        )
                        Text(
                            collection.name,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                        )
                    }
            }
        }
    }
}

@Composable
private fun StreamingApiSetup(config: StreamingApiConfig, style: ServiceStyle, onSaved: () -> Unit) {
    var apiKey by remember(config) { mutableStateOf(config.apiKey) }
    var country by remember(config) { mutableStateOf(config.country) }
    var source by remember(config) { mutableStateOf(config.source) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connect Streaming Availability", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Enter your Movie of the Night API key to load live service charts.", color = Color(0xFFB9C0C7))
        OutlinedTextField(colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color.White, unfocusedLabelColor = Color.LightGray, cursorColor = Color.White), 
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("direct" to "Movie of the Night", "rapidapi" to "RapidAPI").forEach { (id, label) ->
                Button(
                    onClick = { source = id },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (source == id) style.accent else Color(0xFF303941),
                        contentColor = if (source == id && style.accent == Color.White) Color.Black else Color.White,
                    ),
                ) { Text(label) }
            }
        }
        OutlinedTextField(colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color.White, unfocusedLabelColor = Color.LightGray, cursorColor = Color.White), 
            value = country,
            onValueChange = { country = it.take(2) },
            label = { Text("Country code") },
            singleLine = true,
            modifier = Modifier.width(180.dp),
        )
        Button(
            enabled = apiKey.isNotBlank() && country.trim().matches(Regex("[A-Za-z]{2}")),
            onClick = {
                StreamingAvailabilityRepository.saveConfig(apiKey, source, country)
                onSaved()
            },
            colors = ButtonDefaults.buttonColors(containerColor = style.accent),
        ) { Text("Load services", color = if (style.accent == Color.White) Color.Black else Color.White) }
    }
}

@Composable
private fun ServiceHero(
    service: StreamingService,
    page: StreamingServicePageData,
    style: ServiceStyle,
    country: String,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val featured = (if (service == StreamingService.APPLE) page.topSeries else page.topFilms).firstOrNull()
        ?: page.more.firstOrNull()
    if (featured == null) {
        Text("No titles are available in this region yet.", color = Color.White,
            modifier = Modifier.padding(16.dp))
        return
    }
    Column(Modifier.fillMaxWidth()) {
        featured.banner?.let { image ->
            NuvioAsyncImage(model = image, contentDescription = null,
                modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop)
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Featured · ${country.uppercase()}", color = Color.LightGray, fontSize = 13.sp)
            Text(featured.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            featured.description?.takeIf(String::isNotBlank)?.let {
                Text(it, color = Color.White.copy(alpha = 0.82f), maxLines = 3,
                    overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            }
            Button(onClick = { onPosterClick(featured) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Text("View details", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DisneyHubButtons(selected: String?, onSelect: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 15.dp)) {
        Text(
            "Explore the worlds of Disney+",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(listOf("Disney", "Pixar", "Marvel", "Star Wars", "Nat Geo")) { hub ->
                val shape = RoundedCornerShape(10.dp)
                val background = when (hub) {
                    "Pixar" -> Color(0xFF123A62)
                    "Marvel" -> Color(0xFF461523)
                    "Star Wars" -> Color(0xFF101927)
                    "Nat Geo" -> Color(0xFF253848)
                    else -> Color(0xFF123B54)
                }
                Box(
                    modifier = Modifier.width(190.dp).height(103.dp)
                        .clip(shape)
                        .background(Brush.verticalGradient(listOf(background.copy(alpha = 0.8f), background)))
                        .border(2.dp, if (selected == hub) Color.White else Color.White.copy(alpha = 0.17f), shape)
                        .clickable { onSelect(hub) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (hub == "Disney") {
                        ServiceLogo(StreamingService.DISNEY, Modifier.fillMaxSize())
                    } else {
                        val drawable = when (hub) {
                            "Pixar" -> Res.drawable.streaming_hub_pixar
                            "Marvel" -> Res.drawable.streaming_hub_marvel
                            "Star Wars" -> Res.drawable.streaming_hub_starwars
                            else -> Res.drawable.streaming_hub_natgeo
                        }
                        Image(
                            painter = painterResource(drawable),
                            contentDescription = hub,
                            contentScale = ContentScale.Fit,
                            colorFilter = if (hub == "Pixar") ColorFilter.tint(Color.White) else null,
                            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 21.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ServicePosterRow(
    title: String,
    items: List<MetaPreview>,
    service: StreamingService,
    style: ServiceStyle,
    onPosterClick: (MetaPreview) -> Unit,
    ranked: Boolean = false,
    emptyMessage: String = "No titles available in this region.",
) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        if (items.isEmpty()) {
            Text(emptyMessage, color = Color.LightGray, modifier = Modifier.padding(horizontal = 16.dp))
        } else LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items.size, key = { items[it].stableKey() }) { index ->
                val item = items[index]
                Column(Modifier.width(136.dp).clickable { onPosterClick(item) }) {
                    Box(Modifier.fillMaxWidth().height(204.dp).clip(RoundedCornerShape(10.dp)).background(style.glow)) {
                        (item.poster ?: item.banner)?.let { image ->
                            NuvioAsyncImage(model = image, contentDescription = item.name,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        if (ranked) Text("${index + 1}", color = Color.White, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = 0.8f))
                                .padding(horizontal = 10.dp, vertical = 5.dp))
                    }
                    Text(item.name, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp), fontSize = 14.sp)
                }
            }
        }
    }
}
