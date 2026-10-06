package com.nuvio.app.features.franchise

import kotlinx.coroutines.CancellationException

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage as NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.launch

@Composable
fun FilmFranchiseHomeRow(
    sectionPadding: Dp,
    onCollectionClick: (Int, String) -> Unit,
    onBrowseClick: () -> Unit,
) {
    var collections by remember { mutableStateOf<List<FilmFranchisePreview>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val rowState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val tmdbSettings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsState()
    LaunchedEffect(tmdbSettings.apiKey) {
        loading = true
        collections = runCatching { FilmFranchiseRepository.featuredCollections() }.getOrElse { if (it is CancellationException) throw it; emptyList() }
        loading = false
    }
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = sectionPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Film Collections", color = Color.White, fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBrowseClick) {
                    Text("View all", color = Color.White.copy(alpha = 0.82f))
                }
            }
        }
        if (collections.isEmpty()) {
            Text(
                when {
                    loading -> "Loading film collections…"
                    TmdbSettingsRepository.effectiveApiKey().isBlank() ->
                        "Add a TMDb API key in Settings → TMDB Enrichment to browse film collections."
                    else -> "Film collections are unavailable right now."
                },
                color = Color.White.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = sectionPadding),
            )
        } else NuvioShelfSection(
            title = "",
            entries = collections,
            rowContentPadding = PaddingValues(horizontal = sectionPadding),
            itemSpacing = 14.dp,
            key = { it.id },
            state = rowState,
        ) { collection ->
                Box(
                    Modifier.width(224.dp).height(126.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (collection.id == FilmFranchiseRepository.MCU_ID)
                            Brush.horizontalGradient(listOf(Color(0xFF6E121B), Color(0xFF1A1C26)))
                        else Brush.horizontalGradient(listOf(Color(0xFF20242B), Color(0xFF20242B))))
                        .clickable { onCollectionClick(collection.id, collection.name) },
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
                            Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))),
                        ),
                    )
                    Text(
                        collection.name,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(13.dp),
                    )
                }
        }
    }
}
