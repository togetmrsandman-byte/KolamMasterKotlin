package com.kolammaster.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class LessonCatalogueUiState(
    val entries: List<LessonCatalogueEntry> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false
)

@Composable
internal fun BrowseLessonScreen(
    catalogue: LessonCatalogueUiState,
    onLessonSelected: (LessonCatalogueEntry) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var difficulties by remember { mutableStateOf<Set<String>>(emptySet()) }
    var types by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filtersOpen by remember { mutableStateOf(false) }
    var visibleCount by remember(catalogue.entries, query, difficulties, types) {
        mutableIntStateOf(60)
    }

    val filteredEntries = remember(catalogue.entries, query, difficulties, types) {
        catalogue.entries.filter { lesson ->
            val matchesSearch = query.isBlank() || lesson.searchableText()
                .contains(query.trim(), ignoreCase = true)
            val matchesDifficulty = difficulties.isEmpty() ||
                lesson.normalizedDifficulty in difficulties
            val matchesType = types.isEmpty() || lesson.normalizedType in types
            matchesSearch && matchesDifficulty && matchesType
        }
    }
    val visibleEntries = filteredEntries.take(visibleCount)
    val gridState = rememberLazyGridState()

    LaunchedEffect(query, difficulties, types) {
        gridState.scrollToItem(0)
    }
    LaunchedEffect(gridState, visibleEntries.size, filteredEntries.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisibleIndex ->
                if (
                    lastVisibleIndex != null &&
                    lastVisibleIndex >= visibleEntries.lastIndex - 6 &&
                    visibleEntries.size < filteredEntries.size
                ) {
                    visibleCount = minOf(visibleCount + 30, filteredEntries.size)
                }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp)
        ) {
            if (catalogue.isRefreshing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFFC9A86A)
                    )
                }
            }
            SearchField(
                query = query,
                onQueryChange = { query = it },
                filtersOpen = filtersOpen,
                activeFilterCount = difficulties.size + types.size,
                onToggleFilters = { filtersOpen = !filtersOpen }
            )
            Spacer(Modifier.height(8.dp))

            when {
                catalogue.isInitialLoading && catalogue.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFC9A86A))
                }
                catalogue.entries.isEmpty() -> BrowseMessage(
                    text = if (catalogue.refreshFailed) {
                        "No lessons are available. Check your connection and try again."
                    } else {
                        "No lessons are available yet."
                    }
                )
                filteredEntries.isEmpty() -> BrowseMessage("No lessons match your search and filters.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(
                        items = visibleEntries,
                        key = { index, lesson -> "${lesson.id}-$index" }
                    ) { _, lesson ->
                        LessonCatalogueCard(
                            lesson = lesson,
                            onClick = { onLessonSelected(lesson) }
                        )
                    }
                }
            }
        }

        if (filtersOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { filtersOpen = false }
                    )
            )
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 116.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF34393F))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Difficulty",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FilterRow(
                    options = listOf("Easy", "Intermediate", "Expert"),
                    selected = difficulties,
                    onSelected = { option ->
                        difficulties = difficulties.toggle(option)
                    }
                )
                Text(
                    text = "Kolam Type",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FilterRow(
                    options = listOf("Sikku", "Pulli", "Rangoli"),
                    selected = types,
                    onSelected = { option ->
                        types = types.toggle(option)
                    }
                )
            }
        }
    }
}

@Composable
internal fun CatalogueLessonDetailScreen(
    lesson: LessonCatalogueEntry,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        RemoteLessonThumbnail(
            url = lesson.firstThumbnailUrl,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = lesson.lessonName,
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = listOfNotNull(
                lesson.normalizedType,
                lesson.normalizedDifficulty,
                lesson.gridType,
                lesson.totalSteps?.let { "$it steps" }
            ).joinToString(" · "),
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Lesson playback will be connected in a later step.",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) {
            Text("Back to lessons")
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    filtersOpen: Boolean,
    activeFilterCount: Int,
    onToggleFilters: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text("Search lessons", color = Color.White.copy(alpha = 0.55f))
                        }
                        innerTextField()
                    }
                }
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = if (activeFilterCount == 0) "Filters" else "Filters ($activeFilterCount)",
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (filtersOpen) Color(0xFF8F6F38)
                    else Color.White.copy(alpha = 0.08f)
                )
                .clickable(onClick = onToggleFilters)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FilterRow(
    options: List<String>,
    selected: Set<String>,
    onSelected: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option in selected,
                onClick = { onSelected(option) },
                label = { Text(option, maxLines = 1) }
            )
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

@Composable
private fun LessonCatalogueCard(
    lesson: LessonCatalogueEntry,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            RemoteLessonThumbnail(
                url = lesson.firstThumbnailUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = lesson.lessonName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val metadata = listOfNotNull(
                lesson.normalizedType,
                lesson.normalizedDifficulty,
                lesson.totalSteps?.let { "$it steps" }
            ).joinToString(" · ")
            if (metadata.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = metadata,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RemoteLessonThumbnail(url: String?, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(initialValue = null, url) {
        value = if (url.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                loadThumbnail(url)
            }
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
    ) {
        if (image == null) {
            Text(
                text = "Kolam",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 14.sp
            )
        } else {
            Image(
                bitmap = image!!,
                contentDescription = "Thumbnail for ${url?.substringAfterLast('/')}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

internal fun loadThumbnail(url: String): ImageBitmap? {
    val connection = try {
        URL(url).openConnection() as? HttpsURLConnection
    } catch (_: IOException) {
        null
    } ?: return null
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.use { stream ->
            BitmapFactory.decodeStream(stream)?.asImageBitmap()
        }
    } catch (_: IOException) {
        null
    } finally {
        connection.disconnect()
    }
}

@Composable
private fun BrowseMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 16.sp
        )
    }
}

private fun LessonCatalogueEntry.searchableText(): String = buildString {
    append(lessonName)
    append(' ')
    append(tags.joinToString(" "))
    append(' ')
    append(kolamType.orEmpty())
    append(' ')
    append(lessonType.orEmpty())
    append(' ')
    append(gridType.orEmpty())
    append(' ')
    append(dotInformation.orEmpty())
    append(' ')
    append(festival.orEmpty())
    append(' ')
    append(keywords.joinToString(" "))
}
