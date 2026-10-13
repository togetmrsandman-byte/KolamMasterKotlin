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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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

internal class BrowseCatalogueState {
    val gridState = LazyGridState()
    var query by mutableStateOf("")
    var difficulties by mutableStateOf<Set<String>>(emptySet())
    var types by mutableStateOf<Set<String>>(emptySet())
}

internal data class BrowseCatalogueItem(
    val catalogueIndex: Int,
    val lesson: LessonCatalogueEntry
)

internal data class BrowseCatalogueSections(
    val freeLessons: List<BrowseCatalogueItem>,
    val individuallyUnlockedLessons: List<BrowseCatalogueItem>,
    val revealedLessons: List<BrowseCatalogueItem>
)

internal data class PendingRewardedLessonOpen(
    val lesson: LessonCatalogueEntry,
    val fromCommunity: Boolean
)

internal enum class GuestUnlockWarningAction {
    SIGN_IN,
    CANCEL
}

internal enum class PendingGoogleLessonAction {
    WAITING,
    OPEN_LESSON,
    RETURN_TO_WARNING
}

internal fun pendingGoogleLessonAction(
    hasPendingLesson: Boolean,
    signInAttemptStarted: Boolean,
    isAuthProcessing: Boolean,
    isGoogleAccount: Boolean
): PendingGoogleLessonAction = when {
    !hasPendingLesson || !signInAttemptStarted || isAuthProcessing ->
        PendingGoogleLessonAction.WAITING
    isGoogleAccount -> PendingGoogleLessonAction.OPEN_LESSON
    else -> PendingGoogleLessonAction.RETURN_TO_WARNING
}

internal fun completeVerifiedRewardedLessonUnlock(
    isGuest: Boolean,
    onUnlockGranted: () -> Unit,
    onShowGuestWarning: () -> Unit,
    onOpenLesson: () -> Unit
) {
    onUnlockGranted()
    if (isGuest) onShowGuestWarning() else onOpenLesson()
}

internal fun handleGuestUnlockWarningAction(
    action: GuestUnlockWarningAction,
    onSignIn: () -> Unit,
    onCancelAndOpen: () -> Unit
) {
    when (action) {
        GuestUnlockWarningAction.SIGN_IN -> onSignIn()
        GuestUnlockWarningAction.CANCEL -> onCancelAndOpen()
    }
}

internal fun browseCatalogueSections(
    entries: List<LessonCatalogueEntry>,
    visibleCount: Int,
    unlockedLessonIds: Set<String>
): BrowseCatalogueSections {
    fun item(index: Int, lesson: LessonCatalogueEntry) = BrowseCatalogueItem(
        catalogueIndex = index,
        lesson = lesson.copy(
            access = if (index < FREE_CATALOGUE_ENTRY_COUNT ||
                lesson.id in unlockedLessonIds
            ) {
                CatalogueAccess.FREE
            } else {
                CatalogueAccess.LOCKED
            }
        )
    )

    val individuallyUnlockedIds = entries
        .drop(FREE_CATALOGUE_ENTRY_COUNT)
        .asSequence()
        .filter { it.id in unlockedLessonIds }
        .map { it.id }
        .toSet()

    return BrowseCatalogueSections(
        freeLessons = entries.take(FREE_CATALOGUE_ENTRY_COUNT).mapIndexed(::item),
        individuallyUnlockedLessons = entries
            .mapIndexed(::item)
            .drop(FREE_CATALOGUE_ENTRY_COUNT)
            .filter { it.lesson.id in individuallyUnlockedIds },
        revealedLessons = entries
            .drop(FREE_CATALOGUE_ENTRY_COUNT)
            .take((visibleCount - FREE_CATALOGUE_ENTRY_COUNT).coerceAtLeast(0))
            .mapIndexed { offset, lesson ->
                item(offset + FREE_CATALOGUE_ENTRY_COUNT, lesson)
            }
            .filterNot { it.lesson.id in individuallyUnlockedIds }
    )
}

@Composable
internal fun BrowseLessonScreen(
    catalogue: LessonCatalogueUiState,
    browseState: BrowseCatalogueState = remember { BrowseCatalogueState() },
    unlockedLessonIds: Set<String>,
    accountId: String?,
    isGuest: Boolean = false,
    isAuthProcessing: Boolean = false,
    visibleLessonCount: Int,
    onVisibleLessonCountChanged: (Int) -> Unit,
    onLessonUnlocked: (String) -> Unit = {},
    onGoogleSignIn: () -> Unit,
    onLessonSelected: (LessonCatalogueEntry) -> Unit,
    communityLessonId: String?,
    onCommunityLessonRequestHandled: () -> Unit,
    onCommunityLessonMissing: () -> Unit,
    onCommunityLessonSelected: (LessonCatalogueEntry) -> Unit,
    onCheckMoreThumbnails: suspend () -> List<LessonCatalogueEntry>,
    folderRepository: FolderDataSource = remember { FolderRepository() }
) {
    var filtersOpen by remember { mutableStateOf(false) }
    val languageCode = LocalConfiguration.current.locales[0].toLanguageTag()
    val context = androidx.compose.ui.platform.LocalContext.current
    val localizedContext = context.withSelectedAppLanguage()

    var visibleCount by remember(accountId) {
        mutableIntStateOf(visibleLessonCount.coerceAtLeast(INITIAL_REVEALED_THUMBNAIL_COUNT))
    }
    LaunchedEffect(accountId, visibleLessonCount) {
        visibleCount = maxOf(visibleCount, visibleLessonCount)
    }
    var isLoadingRewardedAd by remember { mutableStateOf(false) }
    var isCheckingForMoreThumbnails by remember { mutableStateOf(false) }
    var noMoreThumbnailsAvailable by remember { mutableStateOf(false) }
    var isIndividualAdProcessing by remember { mutableStateOf(false) }
    var thumbnailAdAttempt by remember { mutableIntStateOf(0) }
    var individualAdAttempt by remember { mutableIntStateOf(0) }
    var thumbnailAdTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var individualAdTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var revealMessage by remember { mutableStateOf<String?>(null) }
    var lockedLesson by remember { mutableStateOf<LessonCatalogueEntry?>(null) }
    var lockedLessonFromCommunity by remember { mutableStateOf(false) }
    var guestUnlockWarning by remember { mutableStateOf<PendingRewardedLessonOpen?>(null) }
    var pendingGoogleLessonOpen by remember { mutableStateOf<PendingRewardedLessonOpen?>(null) }
    var waitingForGoogleSignIn by remember { mutableStateOf(false) }
    var googleSignInAttemptStarted by remember { mutableStateOf(false) }
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
                    localizedContext.getString(R.string.folders_load_saved_failed)
                )
            }
        }
    }

    LaunchedEffect(accountId, isGuest, isAuthProcessing, pendingGoogleLessonOpen) {
        val pending = pendingGoogleLessonOpen
        if (pending != null && waitingForGoogleSignIn) {
            if (isAuthProcessing) {
                googleSignInAttemptStarted = true
            } else {
                when (
                    pendingGoogleLessonAction(
                        hasPendingLesson = true,
                        signInAttemptStarted = googleSignInAttemptStarted,
                        isAuthProcessing = false,
                        isGoogleAccount = accountId != null && !isGuest
                    )
                ) {
                    PendingGoogleLessonAction.OPEN_LESSON -> {
                        pendingGoogleLessonOpen = null
                        waitingForGoogleSignIn = false
                        googleSignInAttemptStarted = false
                        if (pending.fromCommunity) {
                            onCommunityLessonSelected(pending.lesson)
                        } else {
                            onLessonSelected(pending.lesson)
                        }
                    }
                    PendingGoogleLessonAction.RETURN_TO_WARNING -> {
                        pendingGoogleLessonOpen = null
                        waitingForGoogleSignIn = false
                        googleSignInAttemptStarted = false
                        guestUnlockWarning = pending
                    }
                    PendingGoogleLessonAction.WAITING -> Unit
                }
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
        browseState.query,
        browseState.difficulties,
        browseState.types,
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
            val matchesSearch = browseState.query.isBlank() || lesson.searchableText()
                .contains(browseState.query.trim(), ignoreCase = true)
            val matchesDifficulty = browseState.difficulties.isEmpty() ||
                lesson.normalizedDifficulty in browseState.difficulties
            val matchesType = browseState.types.isEmpty() ||
                lesson.normalizedType in browseState.types
            matchesSearch && matchesDifficulty && matchesType
        }
    }
    val hasActiveSearchOrFilters =
        browseState.query.isNotBlank() ||
            browseState.difficulties.isNotEmpty() ||
            browseState.types.isNotEmpty()
    val visibleEntries = if (hasActiveSearchOrFilters) {
        filteredEntries
    } else {
        filteredEntries.take(visibleCount)
    }
    val gridState = browseState.gridState
    val resetGridPosition = {
        scope.launch { gridState.scrollToItem(0) }
    }
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
                        color = KolamBronze
                    )
                }
            }
            SearchField(
                query = browseState.query,
                onQueryChange = {
                    browseState.query = it
                    resetGridPosition()
                },
                filtersOpen = filtersOpen,
                activeFilterCount =
                    browseState.difficulties.size + browseState.types.size,
                onToggleFilters = { filtersOpen = !filtersOpen }
            )
            Spacer(Modifier.height(8.dp))
            revealMessage?.let {
                Text(
                    text = it,
                    color = KolamMutedText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            when {
                catalogue.isInitialLoading && catalogue.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = KolamBronze)
                }
                catalogue.entries.isEmpty() -> BrowseMessage(
                    text = if (catalogue.refreshNetworkFailed) {
                        NetworkErrors.DISPLAY_TEXT
                    } else if (catalogue.refreshFailed) {
                        stringResource(R.string.lessons_load_failed)
                    } else {
                        stringResource(R.string.lessons_none_available)
                    }
                )
                filteredEntries.isEmpty() ->
                    BrowseMessage(stringResource(R.string.lessons_search_empty))
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
                                languageCode = languageCode,
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
                        val sections = browseCatalogueSections(
                            entries = catalogue.entries,
                            visibleCount = visibleCount,
                            unlockedLessonIds = unlockedLessonIds
                        )
                        itemsIndexed(
                            items = sections.freeLessons,
                            key = { _, item -> "${item.lesson.id}-${item.catalogueIndex}" }
                        ) { _, item ->
                            val lesson = item.lesson
                            LessonCatalogueCard(
                                lesson = lesson,
                                languageCode = languageCode,
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
                        itemsIndexed(
                            items = sections.individuallyUnlockedLessons,
                            key = { _, item -> "${item.lesson.id}-${item.catalogueIndex}" }
                        ) { _, item ->
                            val lesson = item.lesson
                            LessonCatalogueCard(
                                lesson = lesson,
                                languageCode = languageCode,
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
                                    text = stringResource(R.string.watch_short_ad),
                                    enabled = true,
                                    loadingText = stringResource(R.string.loading_ad)
                                )
                            }
                            itemsIndexed(
                                items = sections.revealedLessons,
                                key = { _, item ->
                                    "${item.lesson.id}-${item.catalogueIndex}"
                                }
                            ) { _, item ->
                                val lesson = item.lesson
                                LessonCatalogueCard(
                                    lesson = lesson,
                                    languageCode = languageCode,
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
                                text = stringResource(R.string.watch_ad_unlock),
                                enabled = !isLoadingRewardedAd,
                                loadingText = stringResource(R.string.loading_ad),
                                onClick = {
                                    val activity = context.findActivity()
                                    if (activity == null) {
                                        revealMessage =
                                            localizedContext.getString(R.string.ad_unavailable)
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
                                                revealMessage =
                                                    localizedContext.getString(R.string.ad_show_failed)
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
                                                    onVisibleLessonCountChanged(newCount)
                                                }
                                                isLoadingRewardedAd = false
                                            },
                                            onDismissedWithoutReward = {
                                                if (thumbnailAdAttempt != attempt) return@showThumbnailRewardedAd
                                                thumbnailAdTimeoutJob?.cancel()
                                                isLoadingRewardedAd = false
                                                revealMessage = localizedContext.getString(
                                                    R.string.ad_reward_not_earned
                                                )
                                            },
                                            onFailed = { errorCode ->
                                                if (thumbnailAdAttempt != attempt) return@showThumbnailRewardedAd
                                                thumbnailAdTimeoutJob?.cancel()
                                                isLoadingRewardedAd = false
                                                revealMessage = adFailureMessage(
                                                    errorCode,
                                                    localizedContext.getString(
                                                        R.string.ad_show_failed
                                                    ),
                                                    localizedContext.getString(
                                                        R.string.network_error_title
                                                    ),
                                                    localizedContext.getString(
                                                        R.string.network_error_message
                                                    )
                                                )
                                            }
                                        )
                                    }
                                },
                                loading = isLoadingRewardedAd
                            )
                        }
                    }
                    if (!hasActiveSearchOrFilters &&
                        catalogue.entries.isNotEmpty() &&
                        visibleEntries.size >= catalogue.entries.size
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            ThumbnailRevealBanner(
                                text = if (noMoreThumbnailsAvailable) {
                                    stringResource(R.string.more_kolams_coming)
                                } else {
                                    stringResource(R.string.check_more_thumbnails)
                                },
                                enabled = !isCheckingForMoreThumbnails,
                                loading = isCheckingForMoreThumbnails,
                                loadingText = stringResource(R.string.checking_for_updates),
                                onClick = {
                                    if (!isCheckingForMoreThumbnails) {
                                        val previouslyKnownIds =
                                            catalogue.entries.mapTo(mutableSetOf()) { it.id }
                                        visibleCount = maxOf(visibleCount, catalogue.entries.size)
                                        noMoreThumbnailsAvailable = false
                                        revealMessage = null
                                        isCheckingForMoreThumbnails = true
                                        scope.launch {
                                            try {
                                                val latestEntries = onCheckMoreThumbnails()
                                                noMoreThumbnailsAvailable =
                                                    latestEntries.none {
                                                        it.id !in previouslyKnownIds
                                                    }
                                            } catch (exception: kotlinx.coroutines.CancellationException) {
                                                throw exception
                                            } catch (exception: Exception) {
                                                revealMessage = localizedContext.getString(
                                                    R.string.check_thumbnails_failed
                                                )
                                            } finally {
                                                isCheckingForMoreThumbnails = false
                                            }
                                        }
                                    }
                                }
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
                    .padding(
                        top = if (catalogue.isRefreshing) 86.dp else 64.dp,
                        start = 16.dp,
                        end = 16.dp
                    )
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(KolamSubtleSurface)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.difficulty),
                    color = KolamText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FilterRow(
                    options = listOf(
                        BrowseFilterOption("Easy", R.string.easy),
                        BrowseFilterOption("Intermediate", R.string.intermediate),
                        BrowseFilterOption("Expert", R.string.expert)
                    ),
                    selected = browseState.difficulties,
                    onSelected = { option ->
                        browseState.difficulties =
                            browseState.difficulties.toggle(option)
                        resetGridPosition()
                    }
                )
                Text(
                    text = stringResource(R.string.kolam_type),
                    color = KolamText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FilterRow(
                    options = listOf(
                        BrowseFilterOption("Sikku"),
                        BrowseFilterOption("Pulli"),
                        BrowseFilterOption("Rangoli")
                    ),
                    selected = browseState.types,
                    onSelected = { option ->
                        browseState.types = browseState.types.toggle(option)
                        resetGridPosition()
                    }
                )
            }
        }

        lockedLesson?.let { lesson ->
            val requiredAds = lesson.requiredRewardedAdCount
            LockedLessonDialog(
                requiredAds = requiredAds,
                completedAds = completedIndividualAds,
                message = lockedLessonMessage ?: pluralStringResource(
                    R.plurals.watch_ads_unlock_lesson,
                    requiredAds,
                    requiredAds
                ),
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
                            lockedLessonMessage =
                                localizedContext.getString(R.string.ad_no_activity)
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
                                    lockedLessonMessage =
                                        localizedContext.getString(R.string.ad_rewarded_show_failed)
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
                                        isIndividualAdProcessing = false
                                        val fromCommunity = lockedLessonFromCommunity
                                        lockedLessonFromCommunity = false
                                        val unlockedLesson = lesson.copy(access = CatalogueAccess.FREE)
                                        completeVerifiedRewardedLessonUnlock(
                                            isGuest = isGuest,
                                            onUnlockGranted = { onLessonUnlocked(unlockedLesson.id) },
                                            onShowGuestWarning = {
                                                guestUnlockWarning = PendingRewardedLessonOpen(
                                                    unlockedLesson,
                                                    fromCommunity
                                                )
                                            },
                                            onOpenLesson = {
                                                if (fromCommunity) {
                                                    onCommunityLessonSelected(unlockedLesson)
                                                } else {
                                                    onLessonSelected(unlockedLesson)
                                                }
                                            }
                                        )
                                    } else {
                                        isIndividualAdProcessing = false
                                    }
                                },
                                onDismissedWithoutReward = {
                                    if (individualAdAttempt != attempt) return@showIndividualRewardedAd
                                    individualAdTimeoutJob?.cancel()
                                    isIndividualAdProcessing = false
                                    lockedLessonMessage =
                                        localizedContext.getString(R.string.rewarded_ad_closed)
                                },
                                onFailed = { errorCode ->
                                    if (individualAdAttempt != attempt) return@showIndividualRewardedAd
                                    individualAdTimeoutJob?.cancel()
                                    isIndividualAdProcessing = false
                                    lockedLessonMessage = adFailureMessage(
                                        errorCode,
                                        localizedContext.getString(R.string.ad_show_failed),
                                        localizedContext.getString(R.string.network_error_title),
                                        localizedContext.getString(R.string.network_error_message)
                                    )
                                }
                            )
                        }
                    }
                }
            )
        }

        guestUnlockWarning?.let { pending ->
            GuestLessonUnlockWarningDialog(
                onSignIn = {
                    handleGuestUnlockWarningAction(
                        action = GuestUnlockWarningAction.SIGN_IN,
                        onSignIn = {
                            guestUnlockWarning = null
                            pendingGoogleLessonOpen = pending
                            waitingForGoogleSignIn = true
                            googleSignInAttemptStarted = false
                            onGoogleSignIn()
                        },
                        onCancelAndOpen = {}
                    )
                },
                onCancel = {
                    handleGuestUnlockWarningAction(
                action = GuestUnlockWarningAction.CANCEL,
                onSignIn = {},
                onCancelAndOpen = {
                    guestUnlockWarning = null
                    openLesson(pending.lesson, pending.fromCommunity)
                }
                    )
                }
            )
        }

        foldersLoadError?.let { message ->
            val isNetworkIssue = message == NetworkErrors.DISPLAY_TEXT
            AlertDialog(
                onDismissRequest = { foldersLoadError = null },
                title = {
                    Text(
                        if (isNetworkIssue) stringResource(R.string.network_error_title)
                        else stringResource(R.string.my_folders)
                    )
                },
                text = {
                    Text(
                        if (isNetworkIssue) stringResource(R.string.network_error_message)
                        else message
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            foldersLoadError = null
                            foldersRefreshKey++
                        },
                        colors = kolamStandardButtonColors()
                    ) { Text(stringResource(R.string.retry)) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { foldersLoadError = null },
                        colors = kolamStandardButtonColors()
                    ) { Text(stringResource(R.string.close)) }
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
        title = { Text(stringResource(R.string.lesson_locked)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (requiredAds == 2) {
                    Text(
                        stringResource(
                            R.string.lesson_unlock_ads_completed,
                            completedAds,
                            requiredAds
                        )
                    )
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onWatchAd,
                enabled = !isProcessing,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    disabledContainerColor = KolamButtonYellow,
                    disabledContentColor = KolamButtonText
                )
            ) {
                if (isProcessing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = KolamButtonText,
                            strokeWidth = 2.dp
                        )
                        Text(stringResource(R.string.please_wait))
                    }
                } else {
                    Text(stringResource(R.string.watch_ad))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCancel,
                enabled = !isProcessing,
                colors = kolamStandardButtonColors()
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun GuestLessonUnlockWarningDialog(
    onSignIn: () -> Unit,
    onCancel: () -> Unit
) {
    val lessonUnlockProtectionDescription =
        stringResource(R.string.lesson_unlock_protect_progress)
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(24.dp),
            color = KolamText,
            tonalElevation = 8.dp
        ) {
            BoxWithConstraints {
                val compact = maxWidth < 360.dp
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(KolamElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓",
                                color = KolamBronze,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.semantics {
                                    contentDescription = lessonUnlockProtectionDescription
                                }
                            )
                        }
                        Text(
                            text = stringResource(R.string.lesson_unlocked),
                            color = KolamText,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = stringResource(R.string.local_unlock_signin_message),
                        color = KolamMutedText,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                    if (compact) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onSignIn,
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = KolamButtonYellow
                                )
                            ) {
                                Text(stringResource(R.string.sign_in_with_google))
                            }
                            Button(
                                onClick = onCancel,
                                modifier = Modifier.fillMaxWidth(),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = KolamButtonYellow
                                )
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onSignIn,
                                modifier = Modifier.weight(1f),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = KolamButtonYellow
                                )
                            ) {
                                Text(stringResource(R.string.sign_in_with_google))
                            }
                            Button(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = KolamButtonYellow
                                )
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CatalogueLessonDetailScreen(
    lesson: LessonCatalogueEntry,
    onBack: () -> Unit
) {
    val languageCode = LocalConfiguration.current.locales[0].toLanguageTag()
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
            text = lesson.localizedName(languageCode),
            color = KolamText,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = listOfNotNull(
                lesson.normalizedType,
                lesson.normalizedDifficulty,
                lesson.gridType,
                lesson.totalSteps?.let {
                    stringResource(R.string.lesson_playback_steps, it)
                }
            ).joinToString(" · "),
            color = KolamMutedText,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.lesson_playback_placeholder),
            color = KolamMutedText,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) {
            Text(stringResource(R.string.back_to_lessons))
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
                .background(KolamSubtleSurface)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = KolamText),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                stringResource(R.string.search_lessons),
                                color = KolamMutedText
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = if (activeFilterCount == 0) {
                stringResource(R.string.filter)
            } else {
                stringResource(R.string.filter_count, activeFilterCount)
            },
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (filtersOpen) KolamGold else KolamElevated
                )
                .clickable(onClick = onToggleFilters)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            color = if (filtersOpen) KolamBackground else KolamText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FilterRow(
    options: List<BrowseFilterOption>,
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
            val label = if (option.labelResource == null) {
                option.value
            } else {
                stringResource(option.labelResource)
            }
            FilterChip(
                selected = option.value in selected,
                onClick = { onSelected(option.value) },
                label = { Text(label, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = KolamElevated,
                    labelColor = KolamText,
                    selectedContainerColor = KolamBronze,
                    selectedLabelColor = KolamBackground
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = option.value in selected,
                    borderColor = KolamBorder,
                    selectedBorderColor = KolamBronze
                )
            )
        }
    }
}

private data class BrowseFilterOption(
    val value: String,
    @param:androidx.annotation.StringRes val labelResource: Int? = null
)

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

@Composable
internal fun LessonCatalogueCard(
    lesson: LessonCatalogueEntry,
    languageCode: String,
    isSaved: Boolean,
    onClick: (() -> Unit)?,
    onHeartClick: () -> Unit
) {
    if (onClick == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = KolamSurface)
        ) {
            LessonCatalogueCardContent(lesson, languageCode, isSaved, onHeartClick)
        }
    } else {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = KolamSurface)
        ) {
            LessonCatalogueCardContent(lesson, languageCode, isSaved, onHeartClick)
        }
    }
}

@Composable
private fun LessonCatalogueCardContent(
    lesson: LessonCatalogueEntry,
    languageCode: String,
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
                text = lesson.localizedName(languageCode),
                modifier = Modifier.weight(1f),
                color = KolamText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            LessonFavoriteStar(
                lessonName = lesson.localizedName(languageCode),
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
                color = KolamMutedText,
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
    val accessibilityDescription = stringResource(
        if (isSaved) R.string.saved_lesson_accessibility
        else R.string.save_lesson_accessibility,
        lessonName
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = accessibilityDescription },
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
                color = if (isSaved) Color.Red else KolamMutedText,
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
    loading: Boolean = false,
    loadingText: String
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = KolamButtonYellow,
            contentColor = KolamButtonText,
            disabledContainerColor = KolamButtonYellow,
            disabledContentColor = KolamButtonText
        )
    ) {
        if (loading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = KolamButtonText,
                    strokeWidth = 2.dp
                )
                Text(loadingText)
            }
        } else {
            Text(text)
        }
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

private fun adFailureMessage(
    errorCode: Int,
    fallback: String,
    networkTitle: String,
    networkMessage: String
): String =
    if (errorCode == ADMOB_NETWORK_ERROR_CODE) {
        "$networkTitle\n$networkMessage"
    } else {
        fallback
    }

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
internal fun RemoteLessonThumbnail(url: String?, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retryAttempt by remember(url) { mutableIntStateOf(0) }
    val state by produceState<LessonThumbnailState>(
        initialValue = LessonThumbnailState.Checking,
        url,
        retryAttempt
    ) {
        if (url.isNullOrBlank()) {
            value = LessonThumbnailState.Unavailable
        } else {
            value = LessonThumbnailState.Checking
            value = try {
                LessonThumbnailCache.load(
                    context,
                    url
                ) {
                    value = LessonThumbnailState.Downloading
                }?.let(LessonThumbnailState::Loaded)
                    ?: LessonThumbnailState.Unavailable
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (exception: Exception) {
                LessonThumbnailState.Unavailable
            }
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(KolamElevated),
        contentAlignment = Alignment.Center
    ) {
        when (val thumbnail = state) {
            LessonThumbnailState.Checking -> ThumbnailFallback()
            LessonThumbnailState.Downloading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = KolamBronze,
                strokeWidth = 2.dp
            )
            LessonThumbnailState.Unavailable -> {
                if (url.isNullOrBlank()) {
                    ThumbnailFallback()
                } else {
                    TextButton(
                        onClick = { retryAttempt++ },
                        contentPadding = PaddingValues(4.dp),
                        colors = kolamStandardButtonColors()
                    ) {
                        Text(stringResource(R.string.retry), color = KolamButtonText, fontSize = 11.sp)
                    }
                }
            }
            is LessonThumbnailState.Loaded -> Image(
                bitmap = thumbnail.image,
                contentDescription = stringResource(
                    R.string.thumbnail_for,
                    url?.substringAfterLast('/') ?: ""
                ),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private sealed interface LessonThumbnailState {
    data object Checking : LessonThumbnailState
    data object Downloading : LessonThumbnailState
    data object Unavailable : LessonThumbnailState
    data class Loaded(val image: ImageBitmap) : LessonThumbnailState
}

@Composable
private fun ThumbnailFallback() {
    Text(
        text = "Kolam",
        color = KolamMutedText,
        fontSize = 14.sp
    )
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
                    color = KolamText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = NetworkErrors.MESSAGE,
                    color = KolamMutedText,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Text(
                text = text,
                color = KolamMutedText,
                fontSize = 16.sp
            )
        }
    }
}

private fun LessonCatalogueEntry.searchableText(): String = buildString {
    append(lessonName)
    append(' ')
    append(displayNames.values.joinToString(" "))
    append(' ')
    append(tags.joinToString(" "))
    append(' ')
    append(tagTranslations.translatable.values.flatten().joinToString(" "))
    append(' ')
    append(tagTranslations.nonTranslatable.joinToString(" "))
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
