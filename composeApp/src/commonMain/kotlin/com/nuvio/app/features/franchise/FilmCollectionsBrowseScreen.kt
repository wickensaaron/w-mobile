package com.nuvio.app.features.franchise

import kotlinx.coroutines.CancellationException

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage as NuvioAsyncImage
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import kotlinx.coroutines.delay

@Composable
fun FilmCollectionsBrowseScreen(onBack: () -> Unit, onCollectionClick: (Int, String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var selected by remember { mutableStateOf(FilmFranchiseRepository.categories.first()) }
    var query by remember { mutableStateOf("") }
    var collections by remember { mutableStateOf<List<FilmFranchisePreview>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var retry by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val settings by remember {
        TmdbSettingsRepository.ensureLoaded()
        TmdbSettingsRepository.uiState
    }.collectAsState()
    val scroll = rememberLazyListState()
    val background = Color(0xFF101216)

    LaunchedEffect(selected, query, settings.apiKey, retry) {
        loading = true
        error = null
        collections = emptyList()
        if (query.isNotBlank()) delay(350)
        if (TmdbSettingsRepository.effectiveApiKey().isBlank()) {
            error = "Add a TMDb API key in Settings → TMDB Enrichment to browse film collections."
        } else {
            runCatching {
                if (query.isBlank()) FilmFranchiseRepository.categoryCollections(selected)
                else FilmFranchiseRepository.searchCollections(query)
            }.onSuccess { collections = it }
                .onFailure { if (it is CancellationException) throw it; error = it.message ?: "Collections could not be loaded." }
        }
        loading = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(background).safeDrawingPadding().imePadding()) {
        val columns = filmCollectionColumnCount(maxWidth.value, LocalDensity.current.fontScale)
        LazyColumn(state = scroll, contentPadding = PaddingValues(bottom = 50.dp)) {
            item(key = "header") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Back")
                    }
                    Text("Film Collections", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 18.dp))
                    Text("Find a franchise and watch its films in release order.",
                        color = Color.White.copy(alpha = 0.68f), modifier = Modifier.padding(top = 5.dp))
                    OutlinedTextField(colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedLabelColor = Color.White, unfocusedLabelColor = Color.LightGray, cursorColor = Color.White), value = query, onValueChange = { query = it },
                        label = { Text("Search film collections") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().padding(top = 22.dp))
                }
            }
            if (query.isBlank()) {
                item(key = "categories") {
                    NuvioShelfSection(title = "", entries = FilmFranchiseRepository.categories,
                        rowContentPadding = PaddingValues(horizontal = 16.dp), itemSpacing = 8.dp,
                        key = { it.name }) { category ->
                            val active = category == selected
                            Box(Modifier.clip(RoundedCornerShape(22.dp))
                                .background(if (active) Color.White else Color(0xFF292D34))
                                .clickable { selected = category }.heightIn(min = 48.dp).padding(horizontal = 18.dp, vertical = 12.dp)) {
                                Text(category.name, color = if (active) background else Color.White,
                                    fontWeight = FontWeight.SemiBold)
                            }
                    }
                }
            }
            item(key = "count") {
                Text(when {
                    loading -> "Loading collections…"
                    error != null -> error!!
                    collections.isEmpty() -> "No collections found. Try another search."
                    query.isNotBlank() -> "${collections.size} search results"
                    else -> "${collections.size} collections · ${selected.name}"
                }, color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(start = 30.dp, top = 25.dp, bottom = 14.dp))
            }
            items(collections.chunked(columns), key = { row -> row.joinToString { it.id.toString() } }) { row ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { collection ->
                        Box(Modifier.weight(1f).height(155.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF242A33))
                            .clickable { onCollectionClick(collection.id, collection.name) }) {
                            collection.artwork?.let { image ->
                                NuvioAsyncImage(model = image, contentDescription = null,
                                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            }
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))))
                            Text(collection.name, color = Color.White, fontSize = 17.sp,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp))
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (error != null) item(key = "retry") {
                TextButton(onClick = { retry++ }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("Try again", color = Color.White)
                }
            }
            item(key = "credit") {
                Text("Collection data from TMDb", color = Color.White.copy(alpha = 0.42f),
                    fontSize = 12.sp, modifier = Modifier.padding(start = 30.dp, top = 25.dp))
            }
        }
    }
}
