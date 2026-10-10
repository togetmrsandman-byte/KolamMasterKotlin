package com.kolammaster.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val BROWSE_PREFERENCES_NAME = "kolam_master_preferences"
private const val REVEALED_THUMBNAIL_COUNT_KEY = "browse-revealed-thumbnail-count"
private const val INITIAL_REVEALED_THUMBNAIL_COUNT = 60
private const val FREE_CATALOGUE_ENTRY_COUNT = 30
private const val THUMBNAIL_REVEAL_BATCH_SIZE = 20
private const val REWARDED_AD_TIMEOUT_MILLIS = 120_000L
private const val ADMOB_NETWORK_ERROR_CODE = 2

// Replace with the production rewarded-ad unit ID before release.
private const val REWARDED_THUMBNAIL_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

internal data class LessonCatalogueUiState(
    val entries: List<LessonCatalogueEntry> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val refreshNetworkFailed: Boolean = false
)

@Composable
internal fun BrowseLessonScreen(
    catalogue: LessonCatalogueUiState,
    unlockedLessonIds: Set<String>,
    accountId: String?,
    onGoogleSignIn: () -> Unit,
    onLessonSelected: (LessonCatalogueEntry) -> Unit,
    communityLessonId: String?,
    onCommunityLessonRequestHandled: () -> Unit,
    onCommunityLessonMissing: () -> Unit,
    onCommunityLessonSelected: (LessonCatalogueEntry) -> Unit,
    folderRepository: FolderDataSource = remember { FolderRepository() }
) {
    var query by remember { mutableStateOf("") }
    var difficulties by remember { mutableStateOf<Set<String>>(emptySet()) }
    var types by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filtersOpen by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) {
        context.applicationContext.getSharedPreferences(
            BROWSE_PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    }

    var visibleCount by remember(preferences) {
        mutableIntStateOf(
            preferences.getInt(
                REVEALED_THUMBNAIL_COUNT_KEY,
                INITIAL_REVEALED_THUMBNAIL_COUNT
            ).coerceAtLeast(INITIAL_REVEALED_THUMBNAIL_COUNT)
        )
    }
    var isLoadingRewardedAd by remember { mutableStateOf(false) }
    var isIndividualAdProcessing by remember { mutableStateOf(false) }
    var thumbnailAdAttempt by remember { mutableIntStateOf(0) }
    var individualAdAttempt by remember { mutableIntStateOf(0) }
    var thumbnailAdTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var individualAdTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var revealMessage by remember { mutableStateOf<String?>(null) }
    var lockedLesson by remember { mutableStateOf<LessonCatalogueEntry?>(null) }
    var lockedLessonFromCommunity by remember { mutableStateOf(false) }
    var completedIndividualAds by remember { mutableIntStateOf(0) }
    var lockedLessonMessage by remember { mutableStateOf<String?>(null) }
    var savedFolders by remember(accountId) { mutableStateOf(emptyList<Folder>()) }
    var foldersLoadError by remember(accountId) { mutableStateOf<String?>(null) }
    var foldersRefreshKey by remember(accountId) { mutableIntStateOf(0) }
    var saveTargetLesson by remember { mutableStateOf<LessonCatalogueEntry?>(null) }
    var saveTargetAccountId by remember { mutableStateOf<String?>(null) }
    var pendingSignInLessonId by remember { mutableStateOf<String?>(null) }
    val latestAccountId by rememberUpdatedState(accountId)
    val scope = rememberCoroutineScope()

    LaunchedEffect(accountId, foldersRefreshKey) {
        savedFolders = emptyList()
        foldersLoadError = null
        if (accountId == null) return@LaunchedEffect
        try {
            val loaded = folderRepository.loadFolders()
            if (latestAccountId == accountId) savedFolders = loaded
        } catch (exception: kotlinx.coroutines.CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (latestAccountId == accountId) {
                foldersLoadError = NetworkErrors.messageFor(
                    exception,
                    "Could not load saved folders."
                )
            }
        }
    }

    LaunchedEffect(accountId, pendingSignInLessonId) {
        val pendingId = pendingSignInLessonId
        if (accountId != null && pendingId != null) {
            saveTargetLesson = catalogue.entries.firstOrNull { it.id == pendingId }
            saveTargetAccountId = accountId
            pendingSignInLessonId = null
        } else if (saveTargetAccountId != null && saveTargetAccountId != accountId) {
            saveTargetLesson = null
            saveTargetAccountId = null
        }
    }

    val filteredEntries = remember(
        catalogue.entries,
        query,
        difficulties,
        types,
        unlockedLessonIds
    ) {
        catalogue.entries.mapIndexed { originalIndex, lesson ->
            lesson.copy(
                access = if (originalIndex < FREE_CATALOGUE_ENTRY_COUNT ||
                    lesson.id in unlockedLessonIds
                ) {
                    CatalogueAccess.FREE
                } else {
                    CatalogueAccess.LOCKED
                }
            )
        }.filter { lesson ->
            val matchesSearch = query.isBlank() || lesson.searchableText()
                .contains(query.trim(), ignoreCase = true)
            val matchesDifficulty = difficulties.isEmpty() ||
                lesson.normalizedDifficulty in difficulties
            val matchesType = types.isEmpty() || lesson.normalizedType in types
            matchesSearch && matchesDifficulty && matchesType
        }
    }
    val hasActiveSearchOrFilters =
        query.isNotBlank() || difficulties.isNotEmpty() || types.isNotEmpty()
    val visibleEntries = if (hasActiveSearchOrFilters) {
        filteredEntries
    } else {
        filteredEntries.take(visibleCount)
    }
    val gridState = rememberLazyGridState()
    val openLesson: (LessonCatalogueEntry, Boolean) -> Unit = { lesson, fromCommunity ->
        if (lesson.access == CatalogueAccess.LOCKED) {
            lockedLesson = lesson
            lockedLessonFromCommunity = fromCommunity
            completedIndividualAds = 0
            lockedLessonMessage = null
        } else {
            if (fromCommunity) onCommunityLessonSelected(lesson) else onLessonSelected(lesson)
        }
    }

    LaunchedEffect(communityLessonId, catalogue.entries, unlockedLessonIds) {
        val requestedId = communityLessonId ?: return@LaunchedEffect
        val index = catalogue.entries.indexOfFirst { it.id == requestedId }
        val lesson = catalogue.entries.getOrNull(index)
        if (lesson == null) {
            onCommunityLessonMissing()
        } else {
            onCommunityLessonRequestHandled()
            openLesson(
                lesson.copy(
                    access = if (index < FREE_CATALOGUE_ENTRY_COUNT ||
                        lesson.id in unlockedLessonIds
                    ) {
                        CatalogueAccess.FREE
                    } else {
                        CatalogueAccess.LOCKED
                    }
                ),
                true
            )
        }
    }

    LaunchedEffect(query, difficulties, types) {
        gridState.scrollToItem(0)
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
            revealMessage?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            when {
                catalogue.isInitialLoading && catalogue.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Color(0xFFC9A86A))
                }
                catalogue.entries.isEmpty() -> BrowseMessage(
                    text = if (catalogue.refreshNetworkFailed) {
                        NetworkErrors.DISPLAY_TEXT
                    } else if (catalogue.refreshFailed) {
                        "Lessons could not be loaded. Please try again."
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
                    if (hasActiveSearchOrFilters) {
                        itemsIndexed(
                            items = visibleEntries,
                            key = { index, lesson -> "${lesson.id}-$index" }
                        ) { _, lesson ->
                            LessonCatalogueCard(
                                lesson = lesson,
                                isSaved = savedFolders.any { lesson.id in it.lessons },
                                onClick = { openLesson(lesson, false) },
                                onHeartClick = {
                                    if (accountId == null) {
                                        pendingSignInLessonId = lesson.id
                                        onGoogleSignIn()
                                    } else {
                                        saveTargetLesson = lesson
                                        saveTargetAccountId = accountId
                                    }
                                }
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = visibleEntries.take(FREE_CATALOGUE_ENTRY_COUNT),
                            key = { index, lesson -> "${lesson.id}-$index" }
                        ) { _, lesson ->
                            LessonCatalogueCard(
                                lesson = lesson,
                                isSaved = savedFolders.any { lesson.id in it.lessons },
                                onClick = { openLesson(lesson, false) },
                                onHeartClick = {
                                    if (accountId == null) {
                                        pendingSignInLessonId = lesson.id
                                        onGoogleSignIn()
                                    } else {
                                        saveTargetLesson = lesson
                                        saveTargetAccountId = accountId
                                    }
                                }
                            )
                        }
                        if (visibleEntries.size > FREE_CATALOGUE_ENTRY_COUNT) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                ThumbnailRevealBanner(
                                    text = "Watch a short ad to unlock lessons below.",
                                    enabled = true
                                )
                            }
                            itemsIndexed(
                                items = visibleEntries.drop(FREE_CATALOGUE_ENTRY_COUNT),
                                key = { index, lesson ->
                                    "${lesson.id}-${index + FREE_CATALOGUE_ENTRY_COUNT}"
                                }
                            ) { _, lesson ->
                                LessonCatalogueCard(
                                    lesson = lesson,
                                    isSaved = savedFolders.any { lesson.id in it.lessons },
                                    onClick = { openLesson(lesson, false) },
                                    onHeartClick = {
                                        if (accountId == null) {
                                            pendingSignInLessonId = lesson.id
                                            onGoogleSignIn()
                                        } else {
                                            saveTargetLesson = lesson
                                            saveTargetAccountId = accountId
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (!hasActiveSearchOrFilters && visibleEntries.size < catalogue.entries.size) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ThumbnailRevealBanner(
                                text = "Watch an ad to unlock more thumbnails",
                                enabled = !isLoadingRewardedAd,
                                onClick = {
                                    val activity = context.findActivity()
                                    if (activity == null) {
                                        revealMessage = "Unable to show an ad right now."
                                    } else if (!isLoadingRewardedAd) {
                                        isLoadingRewardedAd = true
                                        revealMessage = null
                                        val attempt = ++thumbnailAdAttempt
                                        thumbnailAdTimeoutJob?.cancel()
                                        thumbnailAdTimeoutJob = scope.launch {
                                            delay(REWARDED_AD_TIMEOUT_MILLIS)
                                            if (thumbnailAdAttempt == attempt) {
                                                thumbnailAdAttempt++
                                                isLoadingRewardedAd = false
                                                revealMessage = "The ad could not be shown. Please try again."
                                            }
                                        }
                                        showThumbnailRewardedAd(
                                            activity = activity,
                                            onRewardEarned = {
                                                if (thumbnailAdAttempt != attempt) return@showThumbnailRewardedAd
                                                thumbnailAdTimeoutJob?.cancel()
                                                val newCount = minOf(
                                                    visibleCount + THUMBNAIL_REVEAL_BATCH_SIZE,
                                                    catalogue.entries.size
                                                )
                                                if (newCount > visibleCount) {
                                                    visibleCount = newCount
                                                    preferences.edit()
                                                        .putInt(REVEALED_THUMBNAIL_COUNT_KEY, newCount)
                                                        .apply()
                                                }
                                                isLoadingRewardedAd = false
                                            },
                                            onDismissedWithoutReward = {
                                                if (thumbnailAdAttempt != attempt) return@showThumbnailRewardedAd
                                                thumbnailAdTimeoutJob?.cancel()
                                                isLoadingRewardedAd = false
                                                revealMessage =
                                                    "No reward was earned. Thumbnails were not revealed."
                                            },
                                            onFailed = { errorCode ->
                                                if (thumbnailAdAttempt != attempt) return@showThumbnailRewardedAd
                                                thumbnailAdTimeoutJob?.cancel()
                                                isLoadingRewardedAd = false
                                                revealMessage = adFailureMessage(errorCode)
                                            }
                                        )
                                    }
                                },
                                loading = isLoadingRewardedAd
                            )
                        }
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

        lockedLesson?.let { lesson ->
            val requiredAds = lesson.requiredRewardedAdCount
            LockedLessonDialog(
                requiredAds = requiredAds,
                completedAds = completedIndividualAds,
                message = lockedLessonMessage ?: "Please watch $requiredAds short ad" +
                    (if (requiredAds == 1) "" else "s") +
                    " to unlock this lesson permanently.",
                isProcessing = isIndividualAdProcessing,
                onCancel = {
                    if (!isIndividualAdProcessing) {
                        lockedLesson = null
                        lockedLessonFromCommunity = false
                    }
                },
                onWatchAd = {
                    if (!isIndividualAdProcessing) {
                        val activity = context.findActivity()
                        if (activity == null) {
                            lockedLessonMessage = "Unable to show a rewarded ad right now."
                        } else {
                            isIndividualAdProcessing = true
                            lockedLessonMessage = null
                            val attempt = ++individualAdAttempt
                            individualAdTimeoutJob?.cancel()
                            individualAdTimeoutJob = scope.launch {
                                delay(REWARDED_AD_TIMEOUT_MILLIS)
                                if (individualAdAttempt == attempt) {
                                    individualAdAttempt++
                                    isIndividualAdProcessing = false
                                    lockedLessonMessage = "The ad could not be shown. Please try again."
                                }
                            }
                            showIndividualRewardedAd(
                                activity = activity,
                                onRewardEarned = {
                                    if (individualAdAttempt != attempt) return@showIndividualRewardedAd
                                    individualAdTimeoutJob?.cancel()
                                    completedIndividualAds += 1
                                    if (completedIndividualAds >= requiredAds) {
                                        lockedLesson = null
                                        if (lockedLessonFromCommunity) {
                                            lockedLessonFromCommunity = false
                                            onCommunityLessonSelected(lesson)
                                        } else {
                                            onLessonSelected(lesson)
                                        }
                                    } else {
                                        isIndividualAdProcessing = false
                                    }
                                },
                                onDismissedWithoutReward = {
                                    if (individualAdAttempt != attempt) return@showIndividualRewardedAd
                                    individualAdTimeoutJob?.cancel()
                                    isIndividualAdProcessing = false
                                    lockedLessonMessage = "Rewarded ad closed before reward."
                                },
                                onFailed = { errorCode ->
                                    if (individualAdAttempt != attempt) return@showIndividualRewardedAd
                                    individualAdTimeoutJob?.cancel()
                                    isIndividualAdProcessing = false
                                    lockedLessonMessage = adFailureMessage(errorCode)
                                }
                            )
                        }
                    }
                }
            )
        }

        foldersLoadError?.let { message ->
            val isNetworkIssue = message == NetworkErrors.DISPLAY_TEXT
            AlertDialog(
                onDismissRequest = { foldersLoadError = null },
                title = { Text(if (isNetworkIssue) NetworkErrors.TITLE else "My Folders") },
                text = { Text(if (isNetworkIssue) NetworkErrors.MESSAGE else message) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            foldersLoadError = null
                            foldersRefreshKey++
                        }
                    ) { Text("Retry") }
                },
                dismissButton = {
                    TextButton(onClick = { foldersLoadError = null }) { Text("Close") }
                }
            )
        }

        saveTargetLesson
            ?.takeIf { accountId != null && saveTargetAccountId == accountId }
            ?.let { lesson ->
            SaveToMyFoldersDialog(
                accountId = accountId,
                lesson = lesson,
                folderRepository = folderRepository,
                onSaved = { updatedFolder ->
                    if (latestAccountId == accountId) {
                        savedFolders = savedFolders.map {
                            if (it.id == updatedFolder.id) updatedFolder else it
                        }.let { current ->
                            if (current.any { it.id == updatedFolder.id }) {
                                current
                            } else {
                                current + updatedFolder
                            }
                        }
                    }
                    saveTargetLesson = null
                    saveTargetAccountId = null
                },
                onDismiss = {
                    saveTargetLesson = null
                    saveTargetAccountId = null
                }
            )
        }
    }
}

@Composable
private fun LockedLessonDialog(
    requiredAds: Int,
    completedAds: Int,
    message: String,
    isProcessing: Boolean,
    onCancel: () -> Unit,
    onWatchAd: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isProcessing) onCancel() },
        title = { Text("Lesson Locked") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (requiredAds == 2) {
                    Text("$completedAds of $requiredAds ads completed")
                }
            }
        },
        confirmButton = {
            Button(onClick = onWatchAd, enabled = !isProcessing) {
                Text(if (isProcessing) "Please wait" else "Watch Ad")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !isProcessing) {
                Text("Cancel")
            }
        }
    )
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
internal fun LessonCatalogueCard(
    lesson: LessonCatalogueEntry,
    isSaved: Boolean,
    onClick: (() -> Unit)?,
    onHeartClick: () -> Unit
) {
    if (onClick == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
        ) {
            LessonCatalogueCardContent(lesson, isSaved, onHeartClick)
        }
    } else {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
        ) {
            LessonCatalogueCardContent(lesson, isSaved, onHeartClick)
        }
    }
}

@Composable
private fun LessonCatalogueCardContent(
    lesson: LessonCatalogueEntry,
    isSaved: Boolean,
    onHeartClick: () -> Unit
) {
    Column(modifier = Modifier.padding(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            RemoteLessonThumbnail(
                url = lesson.firstThumbnailUrl,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center
            ) {
                if (lesson.access == CatalogueAccess.LOCKED) {
                    Text(text = "🔒", fontSize = 18.sp)
                }
            }
            Text(
                text = lesson.lessonName,
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            LessonFavoriteStar(
                lessonName = lesson.lessonName,
                isSaved = isSaved,
                onClick = onHeartClick
            )
        }
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

@Composable
internal fun LessonFavoriteStar(
    lessonName: String,
    isSaved: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (isSaved) {
                    "Saved $lessonName to My Folders"
                } else {
                    "Save $lessonName to My Folders"
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val scaleX = size.width / 24f
            val scaleY = size.height / 24f
            val heart = Path().apply {
                moveTo(12f * scaleX, 21.35f * scaleY)
                lineTo(10.55f * scaleX, 20.03f * scaleY)
                cubicTo(
                    5.4f * scaleX, 15.36f * scaleY,
                    2f * scaleX, 12.28f * scaleY,
                    2f * scaleX, 8.5f * scaleY
                )
                cubicTo(
                    2f * scaleX, 5.42f * scaleY,
                    4.42f * scaleX, 3f * scaleY,
                    7.5f * scaleX, 3f * scaleY
                )
                cubicTo(
                    9.24f * scaleX, 3f * scaleY,
                    10.91f * scaleX, 3.81f * scaleY,
                    12f * scaleX, 5.08f * scaleY
                )
                cubicTo(
                    13.09f * scaleX, 3.81f * scaleY,
                    14.76f * scaleX, 3f * scaleY,
                    16.5f * scaleX, 3f * scaleY
                )
                cubicTo(
                    19.58f * scaleX, 3f * scaleY,
                    22f * scaleX, 5.42f * scaleY,
                    22f * scaleX, 8.5f * scaleY
                )
                cubicTo(
                    22f * scaleX, 12.28f * scaleY,
                    18.6f * scaleX, 15.36f * scaleY,
                    13.45f * scaleX, 20.04f * scaleY
                )
                close()
            }
            drawPath(
                path = heart,
                color = if (isSaved) Color.Red else Color.White,
                style = if (isSaved) {
                    androidx.compose.ui.graphics.drawscope.Fill
                } else {
                    Stroke(width = size.width * 0.09f)
                }
            )
        }
    }
}

@Composable
private fun ThumbnailRevealBanner(
    text: String,
    onClick: () -> Unit = {},
    enabled: Boolean = false,
    loading: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = Color(0xFF315A78),
            contentColor = Color.White
        )
    ) {
        Text(if (loading) "Loading ad..." else text)
    }
}

private fun showThumbnailRewardedAd(
    activity: Activity,
    onRewardEarned: () -> Unit,
    onDismissedWithoutReward: () -> Unit,
    onFailed: (Int) -> Unit
) {
    RewardedAd.load(
        activity,
        REWARDED_THUMBNAIL_AD_UNIT_ID,
        AdRequest.Builder().build(),
        object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                onFailed(error.code)
            }

            override fun onAdLoaded(rewardedAd: RewardedAd) {
                var rewardEarned = false
                rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        if (!rewardEarned) onDismissedWithoutReward()
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        onFailed(error.code)
                    }
                }
                rewardedAd.show(activity) { _: RewardItem ->
                    if (!rewardEarned) {
                        rewardEarned = true
                        onRewardEarned()
                    }
                }
            }
        }
    )
}

private fun showIndividualRewardedAd(
    activity: Activity,
    onRewardEarned: () -> Unit,
    onDismissedWithoutReward: () -> Unit,
    onFailed: (Int) -> Unit
) {
    RewardedAd.load(
        activity,
        REWARDED_THUMBNAIL_AD_UNIT_ID,
        AdRequest.Builder().build(),
        object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                onFailed(error.code)
            }

            override fun onAdLoaded(rewardedAd: RewardedAd) {
                var rewardEarned = false
                var completionHandled = false
                rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        if (completionHandled) return
                        completionHandled = true
                        if (rewardEarned) onRewardEarned() else onDismissedWithoutReward()
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        if (completionHandled) return
                        completionHandled = true
                        if (rewardEarned) {
                            onRewardEarned()
                        } else {
                            onFailed(error.code)
                        }
                    }
                }
                rewardedAd.show(activity) { _: RewardItem ->
                    rewardEarned = true
                }
            }
        }
    )
}

private fun adFailureMessage(errorCode: Int): String =
    if (errorCode == ADMOB_NETWORK_ERROR_CODE) {
        NetworkErrors.DISPLAY_TEXT
    } else {
        "The ad could not be shown. Please try again."
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun RemoteLessonThumbnail(url: String?, modifier: Modifier = Modifier) {
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
        if (text == NetworkErrors.DISPLAY_TEXT) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = NetworkErrors.TITLE,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = NetworkErrors.MESSAGE,
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 16.sp
            )
        }
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
