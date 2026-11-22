package com.kolammaster.android

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
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private const val REWARDED_AD_TIMEOUT_MILLIS = 120_000L
private const val ADMOB_NETWORK_ERROR_CODE = 2

private val REWARDED_LESSON_AD_UNIT_ID: String
    get() = BuildConfig.PUBLISH_REWARDED_AD_UNIT_ID

internal data class LessonCatalogueUiState(
    val entries: List<LessonCatalogueEntry> = emptyList(),
    val isInitialLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val refreshFailed: Boolean = false,
    val refreshNetworkFailed: Boolean = false,
    val arrangementRefreshGeneration: Int = 0
)

internal class BrowseCatalogueState(
    val gridState: LazyGridState = LazyGridState()
) {
    var query by mutableStateOf("")
    var difficulties by mutableStateOf<Set<String>>(emptySet())
    var types by mutableStateOf<Set<String>>(emptySet())
    var accessFilter by mutableStateOf(BrowseLessonAccessFilter.ALL)

    fun reset() {
        query = ""
        difficulties = emptySet()
        types = emptySet()
        accessFilter = BrowseLessonAccessFilter.ALL
    }
}

internal data class PendingRewardedLessonOpen(
    val lesson: LessonCatalogueEntry,
    val fromCommunity: Boolean
)

internal data class PendingLessonRequest(
    val lessonId: String,
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

internal fun lessonWithCatalogueAccess(
    lesson: LessonCatalogueEntry
): LessonCatalogueEntry = lesson.copy(
    access = if (lesson.isFree) {
        CatalogueAccess.FREE
    } else {
        CatalogueAccess.LOCKED
    }
)

internal fun canOpenLesson(
    lesson: LessonCatalogueEntry,
    unlockedLessonIds: Set<String>
): Boolean = lesson.isFree || lesson.id in unlockedLessonIds

internal fun shouldShowLessonLockIcon(
    lesson: LessonCatalogueEntry,
    unlockedLessonIds: Set<String>
): Boolean = !lesson.isFree && lesson.id !in unlockedLessonIds

@Composable
internal fun BrowseLessonScreen(
    catalogue: LessonCatalogueUiState,
    browseEntries: List<LessonCatalogueEntry>,
    browseState: BrowseCatalogueState = remember { BrowseCatalogueState() },
    unlockedLessonIds: Set<String>,
    accountId: String?,
    isGuest: Boolean = false,
    isAuthProcessing: Boolean = false,
    onLessonUnlocked: (String) -> Unit = {},
    onGoogleSignIn: () -> Unit,
    onLessonSelected: (LessonCatalogueEntry) -> Unit,
    lessonRequest: PendingLessonRequest?,
    onLessonRequestHandled: () -> Unit,
    onCommunityLessonMissing: () -> Unit,
    onCommunityLessonSelected: (LessonCatalogueEntry) -> Unit,
    onCheckMoreLessons: suspend (Set<String>) -> List<LessonCatalogueEntry>,
    folderRepository: FolderDataSource = remember { FolderRepository() }
) {
    var filtersOpen by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    var shouldFocusSearch by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(shouldFocusSearch) {
        if (shouldFocusSearch) {
            delay(50)
            try {
                searchFocusRequester.requestFocus()
                keyboardController?.show()
            } catch (_: Exception) {}
            shouldFocusSearch = false
        }
    }
    val languageCode = LocalConfiguration.current.locales[0].toLanguageTag()
    val context = androidx.compose.ui.platform.LocalContext.current
    val localizedContext = context.withSelectedAppLanguage()

    var isCheckingForMoreLessons by remember { mutableStateOf(false) }
    var noMoreLessonsAvailable by remember { mutableStateOf(false) }
    var isIndividualAdProcessing by remember { mutableStateOf(false) }
    var individualAdAttempt by remember { mutableIntStateOf(0) }
    var individualAdTimeoutJob by remember { mutableStateOf<Job?>(null) }
    var catalogueUpdateMessage by remember { mutableStateOf<String?>(null) }
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
                    localizedContext.appString("folders_load_saved_failed")
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
            saveTargetLesson = browseEntries.firstOrNull { it.id == pendingId }
            saveTargetAccountId = accountId
            pendingSignInLessonId = null
        } else if (saveTargetAccountId != null && saveTargetAccountId != accountId) {
            saveTargetLesson = null
            saveTargetAccountId = null
        }
    }

    val filteredEntries = remember(
        browseEntries,
        browseState.query,
        browseState.difficulties,
        browseState.types,
        browseState.accessFilter,
        unlockedLessonIds
    ) {
        filterBrowseCatalogue(
            entries = browseEntries.map(::lessonWithCatalogueAccess),
            query = browseState.query,
            difficulties = browseState.difficulties,
            types = browseState.types,
            accessFilter = browseState.accessFilter,
            unlockedLessonIds = unlockedLessonIds
        )
    }
    val visibleEntries = filteredEntries
    val gridState = browseState.gridState
    val resetGridPosition = {
        scope.launch { gridState.scrollToItem(0) }
    }
    val openLesson: (LessonCatalogueEntry, Boolean) -> Unit = { lesson, fromCommunity ->
        if (!canOpenLesson(lesson, unlockedLessonIds)) {
            lockedLesson = lesson
            lockedLessonFromCommunity = fromCommunity
            completedIndividualAds = 0
            lockedLessonMessage = null
        } else {
            if (fromCommunity) onCommunityLessonSelected(lesson) else onLessonSelected(lesson)
        }
    }

    LaunchedEffect(lessonRequest, browseEntries, unlockedLessonIds) {
        val request = lessonRequest ?: return@LaunchedEffect
        val lesson = browseEntries.firstOrNull { it.id == request.lessonId }
        onLessonRequestHandled()
        if (lesson == null) {
            if (request.fromCommunity) onCommunityLessonMissing()
        } else {
            openLesson(
                lessonWithCatalogueAccess(lesson),
                request.fromCommunity
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
                    browseState.difficulties.size +
                        browseState.types.size +
                        if (browseState.accessFilter == BrowseLessonAccessFilter.ALL) 0 else 1,
                onOpenSearch = {
                    filtersOpen = true
                    shouldFocusSearch = true
                },
                onToggleFilters = {
                    filtersOpen = !filtersOpen
                },
                focusRequester = searchFocusRequester
            )
            Spacer(Modifier.height(8.dp))
            catalogueUpdateMessage?.let {
                Text(
                    text = it,
                    color = KolamMutedText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            when {
                catalogue.isInitialLoading && browseEntries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = KolamBronze)
                }
                browseEntries.isEmpty() -> BrowseMessage(
                    text = if (catalogue.refreshNetworkFailed) {
                        NetworkErrors.DISPLAY_TEXT
                    } else if (catalogue.refreshFailed) {
                        currentAppString("lessons_load_failed")
                    } else {
                        currentAppString("lessons_none_available")
                    }
                )
                filteredEntries.isEmpty() ->
                    BrowseMessage(currentAppString("lessons_search_empty"))
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
                            languageCode = languageCode,
                            isSaved = savedFolders.any { lesson.id in it.lessons },
                            unlockedLessonIds = unlockedLessonIds,
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
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CatalogueUpdateButton(
                            text = if (noMoreLessonsAvailable) {
                                currentAppString("more_kolams_coming")
                            } else {
                                currentAppString("check_more_thumbnails")
                            },
                            enabled = !isCheckingForMoreLessons,
                            loading = isCheckingForMoreLessons,
                            loadingText = currentAppString("checking_for_updates"),
                            onClick = {
                                if (!isCheckingForMoreLessons) {
                                    val previouslyKnownIds =
                                        browseEntries.mapTo(mutableSetOf()) { it.id }
                                    noMoreLessonsAvailable = false
                                    catalogueUpdateMessage = null
                                    isCheckingForMoreLessons = true
                                    scope.launch {
                                        try {
                                            val latestEntries =
                                                onCheckMoreLessons(previouslyKnownIds)
                                            noMoreLessonsAvailable =
                                                latestEntries.none {
                                                    it.id !in previouslyKnownIds
                                                }
                                        } catch (exception: kotlinx.coroutines.CancellationException) {
                                            throw exception
                                        } catch (exception: Exception) {
                                            catalogueUpdateMessage = localizedContext.appString(
                                                "check_thumbnails_failed"
                                            )
                                        } finally {
                                            isCheckingForMoreLessons = false
                                        }
                                    }
                                }
                            }
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
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = currentAppString("difficulty"),
                    color = KolamText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FilterRow(
                    options = listOf(
                        BrowseFilterOption("", "all", isAll = true),
                        BrowseFilterOption("Easy", "easy"),
                        BrowseFilterOption("Intermediate", "intermediate"),
                        BrowseFilterOption("Expert", "expert")
                    ),
                    selected = browseState.difficulties,
                    onSelected = { option ->
                        browseState.difficulties = if (option.isEmpty()) {
                            emptySet()
                        } else {
                            browseState.difficulties.toggle(option)
                        }
                        resetGridPosition()
                    }
                )
                Text(
                    text = currentAppString("kolam_type"),
                    color = KolamText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FilterRow(
                    options = listOf(
                        BrowseFilterOption("", "all", isAll = true),
                        BrowseFilterOption("Sikku", "sikku_label"),
                        BrowseFilterOption("Pulli", "pulli_label"),
                        BrowseFilterOption("Rangoli", "rangoli_label")
                    ),
                    selected = browseState.types,
                    onSelected = { option ->
                        browseState.types = if (option.isEmpty()) {
                            emptySet()
                        } else {
                            browseState.types.toggle(option)
                        }
                        resetGridPosition()
                    }
                )
                Text(
                    text = currentAppString("lesson_access"),
                    color = KolamText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                FilterRow(
                    options = listOf(
                        BrowseFilterOption("", "all", isAll = true),
                        BrowseFilterOption("free", "free"),
                        BrowseFilterOption("locked", "locked"),
                        BrowseFilterOption("unlocked", "unlocked")
                    ),
                    selected = when (browseState.accessFilter) {
                        BrowseLessonAccessFilter.ALL -> emptySet()
                        BrowseLessonAccessFilter.FREE -> setOf("free")
                        BrowseLessonAccessFilter.LOCKED -> setOf("locked")
                        BrowseLessonAccessFilter.UNLOCKED -> setOf("unlocked")
                    },
                    onSelected = { option ->
                        browseState.accessFilter = when (option) {
                            "free" -> BrowseLessonAccessFilter.FREE
                            "locked" -> BrowseLessonAccessFilter.LOCKED
                            "unlocked" -> BrowseLessonAccessFilter.UNLOCKED
                            else -> BrowseLessonAccessFilter.ALL
                        }
                        resetGridPosition()
                    }
                )

                Spacer(Modifier.height(4.dp))
                val keyboardController = LocalSoftwareKeyboardController.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            browseState.query = ""
                            browseState.difficulties = emptySet()
                            browseState.types = emptySet()
                            browseState.accessFilter = BrowseLessonAccessFilter.ALL
                            resetGridPosition()
                            shouldFocusSearch = true
                            keyboardController?.show()
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, KolamButtonYellow),
                        shape = RoundedCornerShape(10.dp),
                        colors = kolamStandardButtonColors()
                    ) {
                        Text(
                            currentAppString("clear_filters"),
                            color = KolamButtonText,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(
                        onClick = {
                            keyboardController?.hide()
                            filtersOpen = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = KolamButtonYellow,
                            contentColor = KolamButtonText
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(currentAppString("ok"), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        lockedLesson?.let { lesson ->
            val requiredAds = lesson.requiredRewardedAdCount
            LockedLessonDialog(
                requiredAds = requiredAds,
                completedAds = completedIndividualAds,
                message = lockedLessonMessage ?: currentAppPlural(
                    "watch_ads_unlock_lesson",
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
                                localizedContext.appString("ad_no_activity")
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
                                        localizedContext.appString("ad_rewarded_show_failed")
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
                                        val unlockedLesson = lesson
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
                                        localizedContext.appString("rewarded_ad_closed")
                                },
                                onFailed = { errorCode ->
                                    if (individualAdAttempt != attempt) return@showIndividualRewardedAd
                                    individualAdTimeoutJob?.cancel()
                                    isIndividualAdProcessing = false
                                    lockedLessonMessage = adFailureMessage(
                                        errorCode,
                                        localizedContext.appString("ad_show_failed"),
                                        localizedContext.appString("network_error_title"),
                                        localizedContext.appString("network_error_message")
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
                        if (isNetworkIssue) currentAppString("network_error_title")
                        else currentAppString("my_folders")
                    )
                },
                text = {
                    Text(
                        if (isNetworkIssue) currentAppString("network_error_message")
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
                    ) { Text(currentAppString("retry")) }
                },
                dismissButton = {
                    TextButton(
                        onClick = { foldersLoadError = null },
                        colors = kolamStandardButtonColors()
                    ) { Text(currentAppString("close")) }
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
        title = { Text(currentAppString("lesson_locked")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (requiredAds == 2) {
                    Text(
                        currentAppString(
                            "lesson_unlock_ads_completed",
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
                        Text(currentAppString("please_wait"))
                    }
                } else {
                    Text(currentAppString("watch_ad"))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCancel,
                enabled = !isProcessing,
                colors = kolamStandardButtonColors()
            ) {
                Text(currentAppString("cancel"))
            }
        }
    )
}

@Composable
private fun GuestLessonUnlockWarningDialog(
    onSignIn: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 420.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            tonalElevation = 8.dp
        ) {
            BoxWithConstraints {
                val compact = maxWidth < 360.dp
                Column(
                    modifier = Modifier
                        .padding(28.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = currentAppString("lesson_unlocked"),
                        color = Color(0xFF101A2C),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = currentAppString("local_unlock_signin_message"),
                        color = Color(0xFF35435A),
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    if (compact) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onSignIn,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = KolamButtonYellow,
                                    contentColor = KolamButtonText
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    currentAppString("sign_in_with_google"),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                border = BorderStroke(1.dp, KolamButtonYellow),
                                shape = RoundedCornerShape(12.dp),
                                colors = kolamStandardButtonColors()
                            ) {
                                Text(
                                    currentAppString("continue_as_guest"),
                                    color = Color(0xFF101A2C),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                border = BorderStroke(1.dp, KolamButtonYellow),
                                shape = RoundedCornerShape(12.dp),
                                colors = kolamStandardButtonColors()
                            ) {
                                Text(
                                    currentAppString("continue_as_guest"),
                                    color = Color(0xFF101A2C),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                            }
                            Button(
                                onClick = onSignIn,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = KolamButtonYellow,
                                    contentColor = KolamButtonText
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    currentAppString("sign_in_with_google"),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
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
                    currentAppString("lesson_playback_steps", it)
                }
            ).joinToString(" · "),
            color = KolamMutedText,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = currentAppString("lesson_playback_placeholder"),
            color = KolamMutedText,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) {
            Text(currentAppString("back_to_lessons"))
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    filtersOpen: Boolean,
    activeFilterCount: Int,
    onOpenSearch: () -> Unit,
    onToggleFilters: () -> Unit,
    focusRequester: FocusRequester
) {
    val keyboardController = LocalSoftwareKeyboardController.current
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
                .clickable(onClick = onOpenSearch)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                singleLine = true,
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = {
                        keyboardController?.hide()
                    },
                    onDone = {
                        keyboardController?.hide()
                    }
                ),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = KolamText),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.clickable(onClick = onOpenSearch)
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                currentAppString("search_lessons"),
                                color = KolamMutedText
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }
        Spacer(Modifier.size(8.dp))
        val isHighlighted = filtersOpen || query.isNotBlank() || activeFilterCount > 0
        Text(
            text = if (activeFilterCount == 0) {
                currentAppString("filter")
            } else {
                currentAppString("filter_count", activeFilterCount)
            },
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isHighlighted) KolamGold else KolamElevated
                )
                .clickable(onClick = onToggleFilters)
                .padding(horizontal = 14.dp, vertical = 14.dp),
            color = if (isHighlighted) KolamBackground else KolamText,
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
            val label = if (option.labelKey == null) {
                option.value
            } else {
                currentAppString(option.labelKey)
            }
            val isSelected = if (option.isAll) selected.isEmpty() else option.value in selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelected(if (option.isAll) "" else option.value) },
                label = { Text(label, maxLines = 1) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = KolamElevated,
                    labelColor = KolamText,
                    selectedContainerColor = KolamBronze,
                    selectedLabelColor = KolamBackground
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = KolamBorder,
                    selectedBorderColor = KolamBronze
                )
            )
        }
    }
}

private data class BrowseFilterOption(
    val value: String,
    val labelKey: String? = null,
    val isAll: Boolean = false
)

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

@Composable
internal fun LessonCatalogueCard(
    lesson: LessonCatalogueEntry,
    languageCode: String,
    isSaved: Boolean,
    unlockedLessonIds: Set<String>,
    onClick: (() -> Unit)?,
    onHeartClick: () -> Unit
) {
    if (onClick == null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = KolamSurface)
        ) {
            LessonCatalogueCardContent(
                lesson,
                languageCode,
                isSaved,
                shouldShowLessonLockIcon(lesson, unlockedLessonIds),
                onHeartClick
            )
        }
    } else {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = KolamSurface)
        ) {
            LessonCatalogueCardContent(
                lesson,
                languageCode,
                isSaved,
                shouldShowLessonLockIcon(lesson, unlockedLessonIds),
                onHeartClick
            )
        }
    }
}

@Composable
private fun LessonCatalogueCardContent(
    lesson: LessonCatalogueEntry,
    languageCode: String,
    isSaved: Boolean,
    showLock: Boolean,
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
                if (showLock) {
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
    val accessibilityDescription = currentAppString(
        if (isSaved) "saved_lesson_accessibility"
        else "save_lesson_accessibility",
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
private fun CatalogueUpdateButton(
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

private fun showIndividualRewardedAd(
    activity: Activity,
    onRewardEarned: () -> Unit,
    onDismissedWithoutReward: () -> Unit,
    onFailed: (Int) -> Unit
) {
    RewardedAd.load(
        activity,
        REWARDED_LESSON_AD_UNIT_ID,
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
                        Text(currentAppString("retry"), color = KolamButtonText, fontSize = 11.sp)
                    }
                }
            }
            is LessonThumbnailState.Loaded -> Image(
                bitmap = thumbnail.image,
                contentDescription = currentAppString(
                    "thumbnail_for",
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
                    text = currentAppString("network_error_title"),
                    color = KolamText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = currentAppString("network_error_message"),
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

internal fun LessonCatalogueEntry.searchableText(): String = buildString {
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
