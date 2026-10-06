package com.nuvio.app.features.franchise

import kotlinx.coroutines.CancellationException

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nuvio.app.features.home.MetaPreview

@Composable
fun FilmFranchiseScreen(
    collectionId: Int,
    fallbackName: String,
    onBack: () -> Unit,
    onFilmClick: (MetaPreview) -> Unit,
) {
    var franchise by remember(collectionId) { mutableStateOf<FilmFranchise?>(null) }
    var error by remember(collectionId) { mutableStateOf<String?>(null) }
    var retry by remember(collectionId) { mutableStateOf(0) }
    val scrollState = rememberLazyListState()
    val background = Color(0xFF101216)

    LaunchedEffect(collectionId, retry) {
        error = null
        runCatching { FilmFranchiseRepository.load(collectionId, fallbackName) }
            .onSuccess { franchise = it }
            .onFailure { if (it is CancellationException) throw it; error = it.message ?: "This collection could not be loaded." }
    }

    Box(Modifier.fillMaxSize().background(background).safeDrawingPadding().imePadding()) {
        LazyColumn(
            state = scrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 46.dp),
        ) {
            item(key = "header") {
                TextButton(
                    onClick = onBack,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    modifier = Modifier.padding(start = 18.dp, top = 10.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Back")
                }
            }
            val loaded = franchise
            if (loaded == null) {
                item(key = "status") {
                    Column(Modifier.padding(16.dp)) {
                        Text(error ?: "Loading film collection…", color = Color.White, fontSize = 16.sp)
                        if (error != null) {
                            TextButton(onClick = { retry++ }) { Text("Try again") }
                        }
                    }
                }
            } else {
                item(key = "hero") {
                    Column(Modifier.fillMaxWidth()) {
                        loaded.artwork?.let { image ->
                            NuvioAsyncImage(model = image, contentDescription = null,
                                modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop)
                        }
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(loaded.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                            Text("${loaded.films.size} films · Release order", color = Color.LightGray)
                            if (loaded.films.isEmpty()) Text("No films found for this collection.", color = Color.White)
                        }
                    }
                }
                item(key = "label") {
                    Text(
                        "The films",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 28.dp, bottom = 10.dp),
                    )
                }
                itemsIndexed(loaded.films, key = { _, film -> film.id }) { index, film ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !film.id.startsWith("missing:")) { onFilmClick(film) }
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text("${index + 1}", color = Color.White.copy(alpha = 0.52f), fontSize = 27.sp,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                        Box(
                            Modifier.width(64.dp).height(96.dp).clip(RoundedCornerShape(7.dp))
                                .background(Color(0xFF30343C)),
                        ) {
                            film.poster?.let { image ->
                                NuvioAsyncImage(
                                    model = image,
                                    contentDescription = film.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(film.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            film.releaseInfo?.let { Text(it, color = Color.White.copy(alpha = 0.64f)) }
                            film.description?.let {
                                Text(it, color = Color.White.copy(alpha = 0.72f), maxLines = 2,
                                    overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                            }
                        }
                    }
                }
                item(key = "credit") {
                    Text("Collection data from TMDb", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp))
                }
            }
        }
    }
}
