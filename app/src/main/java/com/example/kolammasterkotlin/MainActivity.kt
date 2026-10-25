package com.kolammaster.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import com.kolammaster.app.ui.theme.KolamMasterKotlinTheme
import com.kolammaster.app.auth.SupabaseAccount
import com.kolammaster.app.auth.SupabaseGuestAuth
import com.kolammaster.app.notifications.PushTokenRepository
import com.kolammaster.app.notifications.LauncherBadgeHelper
import com.kolammaster.app.notifications.AnnouncementForegroundAlertDialog
import com.kolammaster.app.notifications.ForegroundAnnouncementAlertStore
import com.kolammaster.app.notifications.SupportForegroundAlertDialog
import com.kolammaster.app.notifications.SupportNotificationChannel
import com.kolammaster.app.notifications.SupportUnreadStore
import com.kolammaster.app.notifications.PendingAnnouncementStore
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.ads.MobileAds
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

private const val PREFERENCES_NAME = "kolam_master_preferences"
private const val LANGUAGE_KEY = "language"
private const val AUTH_SIGN_IN_PROBLEM = "\u0000auth.sign_in_problem"
private const val AUTH_SIGN_IN_TIMEOUT = "\u0000auth.sign_in_timeout"
private const val AUTH_GOOGLE_SIGN_IN_FAILED = "\u0000auth.google_sign_in_failed"
private const val SUPPORT_CONVERSATION_OPEN_FAILED = "\u0000contact.conversation_open_failed"
private const val ANNOUNCEMENTS_MARK_ALL_FAILED = "\u0000announcements.mark_all_failed"
private const val LEGACY_VISIBLE_LESSON_COUNT_KEY = "browse-revealed-thumbnail-count"
private const val ASSET_SETUP_PREFERENCES_NAME = "kolam_master_asset_setup"
private const val ASSET_SETUP_COMPLETE_KEY = "initial_assets_complete"
private const val INITIAL_ASSET_AUTH_COMPLETE_KEY = "initial_assets_auth_complete"
private const val NOTIFICATIONS_PERMISSION_REQUESTED_KEY =
    "notifications_permission_requested"
private const val ONBOARDING_PERMISSION_PENDING_STATE_KEY =
    "onboarding_permission_pending"
private const val LAST_ACTIVITY_MIN_INTERVAL_MILLIS = 60_000L

private sealed interface AppScreen {
    data object ChooseLanguage : AppScreen
    data object SignInInvitation : AppScreen
    data object DownloadingAssets : AppScreen
    data object Opening : AppScreen
    data object Landing : AppScreen
    data object Browse : AppScreen
    data object MyKolams : AppScreen
    data object Profile : AppScreen
    data object Language : AppScreen
    data object ContactUs : AppScreen
    data object Announcements : AppScreen
    data object Community : AppScreen
    data object History : AppScreen
    data object Loading : AppScreen
    data object LessonDownloading : AppScreen
    data class SikkuReady(
        val lesson: SikkuLesson,
        val publishLesson: PublishLessonDetails
    ) : AppScreen
    data class RangoliReady(
        val lesson: RangoliLesson,
        val publishLesson: PublishLessonDetails
    ) : AppScreen
    data class PublishReady(
        val lesson: PublishLessonDetails,
        val initialImageUris: List<Uri>,
        val temporaryImageUris: Set<Uri>
    ) : AppScreen
    data class Failed(val message: String) : AppScreen
}

internal data class ContactSupportNotification(
    val conversationId: String?,
    val messageId: String?
)

private fun LessonCatalogueEntry.toPublishLessonDetails() = PublishLessonDetails(
    lessonId = id,
    lessonName = lessonName,
    category = category,
    difficulty = normalizedDifficulty
)

internal class AnnouncementNotification(val announcementId: String?)

internal fun shouldRequestNotificationPermission(
    sdkInt: Int,
    permissionGranted: Boolean,
    permissionPreviouslyRequested: Boolean
): Boolean =
    sdkInt >= 33 &&
        !permissionGranted &&
        !permissionPreviouslyRequested

internal class OnboardingPermissionFlow(
    waitingForPermissionResult: Boolean = false
) {
    var isWaitingForPermissionResult: Boolean = waitingForPermissionResult
        private set

    fun completeSetup(permissionRequestLaunched: Boolean): Boolean {
        isWaitingForPermissionResult = permissionRequestLaunched
        return !permissionRequestLaunched
    }

    fun onPermissionRequestFinished(): Boolean {
        if (!isWaitingForPermissionResult) return false
        isWaitingForPermissionResult = false
        return true
    }
}

class MainActivity : ComponentActivity() {
    private var screenState by mutableStateOf<AppScreen>(AppScreen.ChooseLanguage)
    private var activeLanguage by mutableStateOf(DEFAULT_LANGUAGE_IDENTIFIER)
    private var catalogueState by mutableStateOf(LessonCatalogueUiState())
    private var unlockedCatalogueLessonIds by mutableStateOf<Set<String>>(emptySet())
    private var visibleLessonCount by mutableIntStateOf(60)
    private var assetSetupProgress by mutableStateOf(
        AssetSetupProgress(0, 0, AssetSetupMessage.PreparingImages)
    )
    private var assetSetupRunning by mutableStateOf(false)
    private var assetSetupError by mutableStateOf<Int?>(null)
    private var assetSetupJob: Job? = null
    private var accountState by mutableStateOf<SupabaseAccount?>(null)
    private var isAuthProcessing by mutableStateOf(false)
    private var authLoadingMessage by mutableStateOf<Int?>(null)
    private var authError by mutableStateOf<String?>(null)
    private var authErrorTitle by mutableStateOf(AUTH_SIGN_IN_PROBLEM)
    private val routeHistory = mutableListOf<AppScreen>()
    private lateinit var googleSignInClient: com.google.android.gms.auth.api.signin.GoogleSignInClient
    private lateinit var lessonUnlockRepository: LessonUnlockRepository
    private var unlockRestoreGeneration = 0
    private var googleChooserTimeoutJob: Job? = null
    private var googleSignInAwaitingResult = false
    private var onboardingPermissionFlow = OnboardingPermissionFlow()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (onboardingPermissionFlow.onPermissionRequestFinished()) {
                clearAuthProcessing()
                startInitialAssetSetup()
            }
        }
    private var pendingContactSupportNotification by
        mutableStateOf<ContactSupportNotification?>(null)
    private var pendingPublishLesson by mutableStateOf<PublishLessonDetails?>(null)
    private var publishPickerLesson by mutableStateOf<PublishLessonDetails?>(null)
    private var communityLessonOpenError by mutableStateOf<String?>(null)
    private var pendingAnnouncementNotification by
        mutableStateOf<AnnouncementNotification?>(null)
    private val lastActivityRepository by lazy { LastActivityRepository() }
    private var lastActivityAttemptUserId: String? = null
    private var lastActivityAttemptElapsedRealtime = 0L

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (!googleSignInAwaitingResult) return@registerForActivityResult
            googleSignInAwaitingResult = false
            googleChooserTimeoutJob?.cancel()
            googleChooserTimeoutJob = null
            if (result.resultCode != Activity.RESULT_OK && result.data == null) {
                clearAuthProcessing()
                authError = null
                pendingPublishLesson = null
                return@registerForActivityResult
            }
            authLoadingMessage = R.string.signing_in
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                val idToken = account.idToken
                    ?: throw IllegalStateException("Google did not return an ID token.")
                finishGoogleSignIn(idToken)
            } catch (exception: ApiException) {
                if (exception.statusCode == GoogleSignInStatusCodes.SIGN_IN_CANCELLED) {
                    clearAuthProcessing()
                    authError = null
                    pendingPublishLesson = null
                } else {
                    showGoogleSignInError(
                        exception,
                        fallback = AUTH_GOOGLE_SIGN_IN_FAILED,
                        exposeNonNetworkDetails = false
                    )
                }
            } catch (exception: IllegalStateException) {
                pendingPublishLesson = null
                showGoogleSignInError(
                    exception,
                    fallback = AUTH_GOOGLE_SIGN_IN_FAILED,
                    exposeNonNetworkDetails = false
                )
            } catch (exception: Exception) {
                pendingPublishLesson = null
                showGoogleSignInError(
                    exception,
                    fallback = AUTH_GOOGLE_SIGN_IN_FAILED,
                    exposeNonNetworkDetails = false
                )
            }
        }

    private val inactivityHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val inactivityRunnable = Runnable {
        finishAffinity()
    }

    private fun resetInactivityTimer() {
        inactivityHandler.removeCallbacks(inactivityRunnable)
        inactivityHandler.postDelayed(inactivityRunnable, 5 * 60 * 1000L)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev?.action == android.view.MotionEvent.ACTION_DOWN) {
            resetInactivityTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resetInactivityTimer()
        onboardingPermissionFlow = OnboardingPermissionFlow(
            savedInstanceState?.getBoolean(ONBOARDING_PERMISSION_PENDING_STATE_KEY) == true
        )
        pendingContactSupportNotification = intent.toContactSupportNotification()
        pendingAnnouncementNotification = intent.toAnnouncementNotification()
        SupportNotificationChannel.create(this)
        MobileAds.initialize(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        val savedLanguage = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .getString(LANGUAGE_KEY, null)
            ?.takeIf { it in supportedLanguageNames }
        activeLanguage = savedLanguage ?: DEFAULT_LANGUAGE_IDENTIFIER
        lessonUnlockRepository = LessonUnlockRepository(this)
        screenState = when {
            onboardingPermissionFlow.isWaitingForPermissionResult -> AppScreen.SignInInvitation
            savedLanguage == null -> AppScreen.ChooseLanguage
            isInitialAssetSetupComplete() -> AppScreen.Opening
            isInitialAssetAuthComplete(defaultValue = true) -> AppScreen.DownloadingAssets
            else -> AppScreen.SignInInvitation
        }
        googleSignInClient = GoogleSignIn.getClient(
            this,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .requestEmail()
                .build()
        )

        lifecycleScope.launch {
            try {
                if (savedLanguage != null || pendingAnnouncementNotification != null) {
                    SupabaseGuestAuth.getOrCreateGuestUserId()
                }
                accountState = SupabaseGuestAuth.currentAccount()
                accountState?.let {
                    restoreLessonUnlocks(it)
                    registerFcmToken(it)
                }
                accountState?.let(::registerFcmToken)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showAuthError(exception)
            }
        }

        setContent {
            KolamMasterKotlinTheme {
                LocalizedAppResources(activeLanguage) {
                    KolamMasterApp(
                    screen = screenState,
                    onScreenChange = { screenState = it },
                    activeLanguage = activeLanguage,
                    catalogue = catalogueState,
                    unlockedCatalogueLessonIds = unlockedCatalogueLessonIds,
                    visibleLessonCount = visibleLessonCount,
                    assetSetupProgress = assetSetupProgress,
                    isAssetSetupRunning = assetSetupRunning,
                    assetSetupError = assetSetupError,
                    account = accountState,
                    isAuthProcessing = isAuthProcessing,
                    publishPickerLesson = publishPickerLesson,
                    authLoadingMessage = authLoadingMessage,
                    authError = authError,
                    authErrorTitle = authErrorTitle,
                    onNavigate = ::navigateTo,
                    onLessonUnlocked = ::recordLessonUnlock,
                    onVisibleLessonCountChanged = ::recordVisibleLessonCount,
                    onRetryAssetSetup = ::startInitialAssetSetup,
                    onGoBack = ::goBack,
                    onHome = { setRoot(AppScreen.Landing) },
                    onReturnToCatalogue = { setRoot(AppScreen.Browse) },
                    onGoogleSignIn = ::beginGoogleSignIn,
                    onContinueAsGuest = ::continueAsGuest,
                    onSignOut = ::signOutToGuest,
                    onDismissAuthError = { authError = null },
                    onLanguageChange = { language ->
                        saveLanguage(language)
                        goBack()
                    },
                    onLanguageSelected = { language ->
                        setInitialAssetAuthComplete(false)
                        saveLanguage(language)
                        routeHistory.clear()
                        routeHistory += AppScreen.ChooseLanguage
                        screenState = AppScreen.SignInInvitation
                    },
                    onLanguageBack = { finish() },
                    onLoadLesson = { lesson -> loadLesson(lesson) },
                    onLoadCommunityLesson = { lesson ->
                        loadLesson(lesson, fromCommunity = true)
                    },
                    onCheckMoreThumbnails = ::checkLessonCatalogueUpdates,
                    communityLessonOpenError = communityLessonOpenError,
                    onDismissCommunityLessonOpenError = {
                        communityLessonOpenError = null
                    },
                    onCommunityLessonMissing = {
                        communityLessonOpenError = withSelectedAppLanguage().getString(
                            R.string.community_lesson_missing
                        )
                        goBack()
                    },
                    onPublishLesson = ::requestPublish,
                    onDismissPublishPicker = { publishPickerLesson = null },
                    onPublishImagesSelected = ::openGalleryPublishForm,
                    supportNotification = pendingContactSupportNotification,
                    onSupportNotificationHandled = {
                        pendingContactSupportNotification = null
                    },
                    onForegroundSupportAlertOpen = { alert ->
                        pendingContactSupportNotification = ContactSupportNotification(
                            conversationId = alert.conversationId,
                            messageId = alert.messageId
                        )
                    },
                    announcementNotification = pendingAnnouncementNotification,
                    onAnnouncementNotificationHandled = {
                        pendingAnnouncementNotification = null
                    },
                    onOpenForegroundAnnouncement = { announcementId ->
                        pendingAnnouncementNotification =
                            AnnouncementNotification(announcementId)
                    }
                )
                }
            }
        }

        if (screenState == AppScreen.DownloadingAssets) {
            startInitialAssetSetup()
        } else if (isInitialAssetSetupComplete()) {
            refreshLessonCatalogue()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            ONBOARDING_PERMISSION_PENDING_STATE_KEY,
            onboardingPermissionFlow.isWaitingForPermissionResult
        )
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingContactSupportNotification = intent.toContactSupportNotification()
        pendingAnnouncementNotification = intent.toAnnouncementNotification()
    }

    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
        recordCurrentUserActivity()
        AnnouncementRepository.requestRefresh(this)
        refreshLauncherBadgeFromCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        inactivityHandler.removeCallbacks(inactivityRunnable)
    }

    private fun recordCurrentUserActivity() {
        lifecycleScope.launch {
            try {
                val account = SupabaseGuestAuth.currentAccount() ?: return@launch
                recordCurrentUserActivity(account.id)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w("LastActivity", "Could not resolve the current user for activity tracking.", exception)
            }
        }
    }

    private fun recordCurrentUserActivity(userId: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastActivityAttemptUserId == userId &&
            now - lastActivityAttemptElapsedRealtime < LAST_ACTIVITY_MIN_INTERVAL_MILLIS
        ) {
            return
        }
        lastActivityAttemptUserId = userId
        lastActivityAttemptElapsedRealtime = now
        lifecycleScope.launch {
            try {
                lastActivityRepository.recordCurrentUserActivity(userId)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w("LastActivity", "Could not update Last Activity in Supabase.", exception)
            }
        }
    }

    private fun refreshLauncherBadgeFromCache() {
        lifecycleScope.launch {
            try {
                val account = SupabaseGuestAuth.currentAccount()
                if (account == null) {
                    LauncherBadgeHelper.updateExistingNotification(
                        this@MainActivity,
                        PendingAnnouncementStore.count(this@MainActivity) +
                            SupportUnreadStore.unreadMessageCount(this@MainActivity)
                    )
                    return@launch
                }
                SupportUnreadStore.useAccount(this@MainActivity, account.id)
                val announcements = AnnouncementRepository(this@MainActivity).loadCached(account)
                val announcementUnreadCount = PendingAnnouncementStore.reconcile(
                    this@MainActivity,
                    announcements.count { !it.isRead },
                    announcements.map(Announcement::id).toSet()
                )
                LauncherBadgeHelper.updateExistingNotification(
                    this@MainActivity,
                    announcementUnreadCount +
                        SupportUnreadStore.unreadMessageCount(this@MainActivity)
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("LauncherBadge", "Could not recalculate the launcher badge count.", exception)
            }
        }
    }

    private fun beginGoogleSignIn() {
        if (isAuthProcessing) return
        isAuthProcessing = true
        authLoadingMessage = R.string.signing_in
        authError = null
        googleChooserTimeoutJob?.cancel()
        lifecycleScope.launch {
            try {
                val currentAccount = runBoundedNetworkOperation(SIGN_IN_SDK_TIMEOUT_MILLIS) {
                    SupabaseGuestAuth.currentAccount()
                }
                if (currentAccount != null && !currentAccount.isGuest) {
                    accountState = currentAccount
                    restoreLessonUnlocks(currentAccount)
                    openPendingPublishAfterSignIn(currentAccount)
                    if (screenState == AppScreen.SignInInvitation) {
                        val waitingForPermission = continueOnboardingAfterPermission()
                        if (waitingForPermission) return@launch
                    }
                    clearAuthProcessing()
                    return@launch
                }
                runBoundedNetworkOperation(SIGN_IN_SDK_TIMEOUT_MILLIS) {
                    SupabaseGuestAuth.getOrCreateGuestUserId()
                }
                authLoadingMessage = null
                googleSignInAwaitingResult = true
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
                googleChooserTimeoutJob = lifecycleScope.launch {
                    delay(GOOGLE_CHOOSER_TIMEOUT_MILLIS)
                    if (isAuthProcessing) {
                        googleSignInAwaitingResult = false
                        pendingPublishLesson = null
                        authErrorTitle = AUTH_SIGN_IN_PROBLEM
                        authError = AUTH_SIGN_IN_TIMEOUT
                        isAuthProcessing = false
                        authLoadingMessage = null
                    }
                }
            } catch (exception: CancellationException) {
                pendingPublishLesson = null
                clearAuthProcessing()
                throw exception
            } catch (exception: Exception) {
                pendingPublishLesson = null
                showGoogleSignInError(exception)
            }
        }
    }

    private fun finishGoogleSignIn(idToken: String) {
        lifecycleScope.launch {
            var waitingForPermission = false
            try {
                authLoadingMessage = R.string.signing_in
                val (previousAccount, newAccount) =
                    runBoundedNetworkOperation(SIGN_IN_SDK_TIMEOUT_MILLIS) {
                    SupabaseGuestAuth.currentAccount() to
                        SupabaseGuestAuth.mergeGuestWithGoogleIdToken(idToken)
                }
                if (previousAccount?.isGuest == true &&
                    previousAccount.id != newAccount.id
                ) {
                    try {
                        val guestUnlocks = lessonUnlockRepository.loadLocal(previousAccount.id)
                        val accountUnlocks = lessonUnlockRepository.loadLocal(newAccount.id)
                        lessonUnlockRepository.saveLocal(
                            newAccount.id,
                            accountUnlocks + guestUnlocks
                        )
                        val guestVisibleCount =
                            lessonUnlockRepository.loadLocalVisibleLessonCount(previousAccount.id)
                        val accountVisibleCount =
                            lessonUnlockRepository.loadLocalVisibleLessonCount(newAccount.id)
                        visibleLessonCount =
                            lessonUnlockRepository.saveLocalVisibleLessonCount(
                                newAccount.id,
                                maxOf(guestVisibleCount, accountVisibleCount)
                            )
                    } catch (exception: Exception) {
                        Log.e("LessonUnlocks", "Could not migrate Guest lesson data locally.", exception)
                    }
                }
                accountState = newAccount
                recordCurrentUserActivity(newAccount.id)
                registerFcmToken(newAccount)
                restoreLessonUnlocks(newAccount)
                openPendingPublishAfterSignIn(newAccount)
                if (screenState == AppScreen.SignInInvitation) {
                    waitingForPermission = continueOnboardingAfterPermission()
                }

            } catch (exception: CancellationException) {
                pendingPublishLesson = null
                throw exception
            } catch (exception: Exception) {
                pendingPublishLesson = null
                showGoogleSignInError(exception)
            } finally {
                if (!waitingForPermission) clearAuthProcessing()
            }
        }
    }

    private fun continueAsGuest() {
        if (isAuthProcessing) return
        isAuthProcessing = true
        authLoadingMessage = R.string.continuing_as_guest
        authError = null
        lifecycleScope.launch {
            var waitingForPermission = false
            try {
                runBoundedNetworkOperation(SIGN_IN_SDK_TIMEOUT_MILLIS) {
                    val currentAccount = SupabaseGuestAuth.currentAccount()
                    if (currentAccount == null || currentAccount.isGuest) {
                        SupabaseGuestAuth.getOrCreateGuestUserId()
                    }

                    accountState = SupabaseGuestAuth.currentAccount()
                    accountState?.let {
                        restoreLessonUnlocks(it)
                        registerFcmToken(it)
                    }
                }
                waitingForPermission = continueOnboardingAfterPermission()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showAuthError(exception)
            } finally {
                if (!waitingForPermission) clearAuthProcessing()
            }
        }
    }

    private fun requestPublish(lesson: PublishLessonDetails) {
        if (!hasValidatedInternetConnection()) {
            authErrorTitle = NetworkErrors.TITLE
            authError = NetworkErrors.MESSAGE
            return
        }
        pendingPublishLesson = lesson
        lifecycleScope.launch {
            try {
                val currentAccount = runBoundedNetworkOperation(SIGN_IN_SDK_TIMEOUT_MILLIS) {
                    SupabaseGuestAuth.currentAccount()
                }
                if (currentAccount != null && !currentAccount.isGuest) {
                    accountState = currentAccount
                    openPendingPublishAfterSignIn(currentAccount)
                } else {
                    beginGoogleSignIn()
                }
            } catch (exception: CancellationException) {
                pendingPublishLesson = null
                throw exception
            } catch (exception: Exception) {
                pendingPublishLesson = null
                showGoogleSignInError(exception)
            }
        }
    }

    private fun openPendingPublishAfterSignIn(account: SupabaseAccount) {
        val lesson = pendingPublishLesson ?: return
        if (account.isGuest) return
        pendingPublishLesson = null
        publishPickerLesson = lesson
    }

    private fun openGalleryPublishForm(
        lesson: PublishLessonDetails,
        imageUris: List<Uri>,
        temporaryImageUris: Set<Uri>
    ) {
        publishPickerLesson = null
        navigateTo(AppScreen.PublishReady(lesson, imageUris, temporaryImageUris))
    }

    private fun hasValidatedInternetConnection(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.let { capabilities ->
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } == true
    }

    private fun continueOnboardingAfterPermission(): Boolean {
        setInitialAssetAuthComplete(true)
        val preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
        val permissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val shouldRequest = shouldRequestNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                permissionGranted = permissionGranted,
                permissionPreviouslyRequested = preferences.getBoolean(
                    NOTIFICATIONS_PERMISSION_REQUESTED_KEY,
                    false
                )
            )
        if (onboardingPermissionFlow.completeSetup(shouldRequest)) {
            startInitialAssetSetup()
            return false
        }

        preferences.edit()
            .putBoolean(NOTIFICATIONS_PERMISSION_REQUESTED_KEY, true)
            .commit()
        authLoadingMessage = null
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (exception: Exception) {
            onboardingPermissionFlow.onPermissionRequestFinished()
            throw exception
        }
        return true
    }

    private fun isInitialAssetSetupComplete(): Boolean =
        getSharedPreferences(ASSET_SETUP_PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(ASSET_SETUP_COMPLETE_KEY, false)

    private fun isInitialAssetAuthComplete(defaultValue: Boolean): Boolean =
        getSharedPreferences(ASSET_SETUP_PREFERENCES_NAME, MODE_PRIVATE)
            .getBoolean(INITIAL_ASSET_AUTH_COMPLETE_KEY, defaultValue)

    private fun setInitialAssetAuthComplete(complete: Boolean) {
        check(
            getSharedPreferences(ASSET_SETUP_PREFERENCES_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(INITIAL_ASSET_AUTH_COMPLETE_KEY, complete)
                .commit()
        ) { "Could not save onboarding completion." }
    }

    private fun startInitialAssetSetup() {
        if (isInitialAssetSetupComplete()) {
            assetSetupRunning = false
            assetSetupError = null
            screenState = AppScreen.Opening
            return
        }
        if (assetSetupJob?.isActive == true) return

        screenState = AppScreen.DownloadingAssets
        assetSetupRunning = true
        assetSetupError = null
        assetSetupProgress = AssetSetupProgress(
            0,
            0,
            AssetSetupMessage.PreparingImages
        )
        assetSetupJob = lifecycleScope.launch {
            try {
                val catalogueRepository = LessonCatalogueRepository(this@MainActivity)
                val setup = InitialAssetSetup(
                    isComplete = ::isInitialAssetSetupComplete,
                    markComplete = {
                        check(
                            getSharedPreferences(ASSET_SETUP_PREFERENCES_NAME, MODE_PRIVATE)
                                .edit()
                                .putBoolean(ASSET_SETUP_COMPLETE_KEY, true)
                                .commit()
                        ) { "Could not save initial asset setup completion." }
                    },
                    loadLessons = {
                        val entries = catalogueRepository.refresh()
                        catalogueState = LessonCatalogueUiState(
                            entries = entries,
                            isInitialLoading = false
                        )
                        entries
                    },
                    cacheLessonThumbnails = { urls, reportProgress ->
                        LessonThumbnailCache.prefetch(
                            this@MainActivity,
                            urls,
                            { completed, total ->
                                withContext(Dispatchers.Main.immediate) {
                                    reportProgress(completed, total)
                                }
                            }
                        )
                    }
                )
                val completed = setup.run { progress ->
                    assetSetupProgress = progress
                }
                if (!completed) {
                    error("One or more required images could not be downloaded.")
                }
                assetSetupRunning = false
                screenState = AppScreen.Opening
            } catch (exception: CancellationException) {
                assetSetupRunning = false
                throw exception
            } catch (exception: Exception) {
                Log.e("InitialAssetSetup", "Could not prepare initial app images.", exception)
                assetSetupRunning = false
                assetSetupError = R.string.some_images_failed
            }
        }
    }

    private fun signOutToGuest() {
        if (isAuthProcessing) return
        isAuthProcessing = true
        authLoadingMessage = R.string.signing_out
        authError = null
        lifecycleScope.launch {
            try {
                googleSignInClient.signOut()
                try {
                    runBoundedNetworkOperation(SIGN_IN_SDK_TIMEOUT_MILLIS) {
                        PushTokenRepository(applicationContext).unregisterCurrentUserToken()
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e("PushNotifications", "Could not unregister the current FCM token.", exception)
                }
                runBoundedNetworkOperation(SIGN_IN_SDK_TIMEOUT_MILLIS) {
                    SupabaseGuestAuth.signOutAndCreateGuest()
                    accountState = SupabaseGuestAuth.currentAccount()
                    accountState?.let {
                        restoreLessonUnlocks(it)
                        registerFcmToken(it)
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showAuthError(exception)
            } finally {
                clearAuthProcessing()
            }
        }
    }

    private fun showAuthError(
        exception: Exception,
        fallback: String = AUTH_SIGN_IN_PROBLEM,
        exposeNonNetworkDetails: Boolean = true
    ) {
        val networkFailure = NetworkErrors.isNetworkFailure(exception)
        authErrorTitle = if (networkFailure) NetworkErrors.TITLE else AUTH_SIGN_IN_PROBLEM
        authError = if (networkFailure) {
            NetworkErrors.MESSAGE
        } else if (exposeNonNetworkDetails && !NetworkErrors.isHttpFailure(exception)) {
            exception.message?.takeIf(String::isNotBlank) ?: fallback
        } else {
            fallback
        }
        clearAuthProcessing()
    }

    private fun showGoogleSignInError(
        exception: Exception,
        fallback: String = AUTH_GOOGLE_SIGN_IN_FAILED,
        exposeNonNetworkDetails: Boolean = true
    ) {
        val networkMessage = NetworkErrors.googleSignInNetworkMessage(exception)
        if (networkMessage != null) {
            authErrorTitle = NetworkErrors.GOOGLE_SIGN_IN_TITLE
            authError = networkMessage
            clearAuthProcessing()
        } else {
            showAuthError(exception, fallback, exposeNonNetworkDetails)
        }
    }

    private fun clearAuthProcessing() {
        googleChooserTimeoutJob?.cancel()
        googleChooserTimeoutJob = null
        googleSignInAwaitingResult = false
        isAuthProcessing = false
        authLoadingMessage = null
    }

    private fun showNetworkIssue() {
        authErrorTitle = NetworkErrors.TITLE
        authError = NetworkErrors.MESSAGE
    }

    private fun restoreLessonUnlocks(account: SupabaseAccount) {
        val generation = ++unlockRestoreGeneration
        val localUnlocks = lessonUnlockRepository.loadLocal(account.id)
        val localVisibleCount = lessonUnlockRepository.loadLocalVisibleLessonCount(account.id)
        visibleLessonCount = localVisibleCount
        unlockedCatalogueLessonIds = localUnlocks
        lifecycleScope.launch {
            val remoteData = try {
                lessonUnlockRepository.loadFromSupabase(account.id)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not load lesson unlocks from Supabase.", exception)
                return@launch
            }
            if (!isCurrentUnlockAccount(account.id, generation)) return@launch

            val latestLocalUnlocks = lessonUnlockRepository.loadLocal(account.id)
            val mergedUnlocks = latestLocalUnlocks +
                localUnlocks +
                unlockedCatalogueLessonIds +
                remoteData.unlockedLessonIds
            val mergedVisibleCount = lessonUnlockRepository.saveLocalVisibleLessonCount(
                account.id,
                maxOf(localVisibleCount, remoteData.visibleLessonCount)
            )
            try {
                lessonUnlockRepository.saveLocal(account.id, mergedUnlocks)
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not persist merged lesson unlocks locally.", exception)
            }
            unlockedCatalogueLessonIds = mergedUnlocks
            visibleLessonCount = mergedVisibleCount
            if (mergedUnlocks != remoteData.unlockedLessonIds ||
                mergedVisibleCount != remoteData.visibleLessonCount
            ) {
                try {
                    lessonUnlockRepository.saveToSupabase(
                        account.id,
                        mergedUnlocks,
                        mergedVisibleCount
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e(
                        "LessonUnlocks",
                        "Could not sync merged lesson unlocks to Supabase.",
                        exception
                    )
                }
            }
        }
    }

    private fun registerFcmToken(account: SupabaseAccount) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                lifecycleScope.launch {
                    try {
                        val current = SupabaseGuestAuth.currentAccount()
                        if (current != null && current.id == account.id) {
                            PushTokenRepository(applicationContext).registerCurrentToken(token)
                        }
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        Log.e("PushNotifications", "Could not register the FCM token.", exception)
                    }
                }
            }
            .addOnFailureListener { exception ->
                Log.e("PushNotifications", "Could not obtain an FCM registration token.", exception)
            }
    }

    private fun recordLessonUnlock(lessonId: String) {
        unlockedCatalogueLessonIds = unlockedCatalogueLessonIds + lessonId
        val account = accountState
        if (account != null) {
            val localUnlocks = lessonUnlockRepository.loadLocal(account.id) +
                unlockedCatalogueLessonIds
            try {
                lessonUnlockRepository.saveLocal(account.id, localUnlocks)
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not persist lesson unlock locally.", exception)
            }
            syncLessonUnlocks(account, localUnlocks, notifyNetworkFailure = true)
            return
        }

        lifecycleScope.launch {
            try {
                val resolvedAccount = SupabaseGuestAuth.currentAccount()
                    ?: run {
                        SupabaseGuestAuth.getOrCreateGuestUserId()
                        SupabaseGuestAuth.currentAccount()
                    }
                    ?: error("Could not resolve an account for lesson unlock storage.")
                accountState = resolvedAccount
                val localUnlocks = lessonUnlockRepository.loadLocal(resolvedAccount.id) + lessonId
                lessonUnlockRepository.saveLocal(resolvedAccount.id, localUnlocks)
                unlockedCatalogueLessonIds = localUnlocks
                syncLessonUnlocks(resolvedAccount, localUnlocks, notifyNetworkFailure = true)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not persist lesson unlock locally.", exception)
                if (NetworkErrors.isNetworkFailure(exception)) showNetworkIssue()
            }
        }
    }

    private fun recordVisibleLessonCount(newCount: Int) {
        visibleLessonCount = maxOf(visibleLessonCount, newCount)
        lifecycleScope.launch {
            try {
                val account = accountState ?: SupabaseGuestAuth.currentAccount()
                    ?: run {
                        SupabaseGuestAuth.getOrCreateGuestUserId()
                        SupabaseGuestAuth.currentAccount()
                    }
                    ?: error("Could not resolve an account for visible lesson storage.")
                accountState = account
                val mergedCount = lessonUnlockRepository.saveLocalVisibleLessonCount(
                    account.id,
                    visibleLessonCount
                )
                visibleLessonCount = mergedCount
                syncLessonUnlocks(
                    account,
                    lessonUnlockRepository.loadLocal(account.id) + unlockedCatalogueLessonIds
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not persist the visible lesson count.", exception)
                if (NetworkErrors.isNetworkFailure(exception)) showNetworkIssue()
            }
        }
    }

    private fun syncLessonUnlocks(
        account: SupabaseAccount,
        localUnlocks: Set<String>,
        notifyNetworkFailure: Boolean = false
    ) {
        val generation = unlockRestoreGeneration
        lifecycleScope.launch {
            val remoteData = try {
                lessonUnlockRepository.loadFromSupabase(account.id)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not load lesson unlocks before sync.", exception)
                if (notifyNetworkFailure && NetworkErrors.isNetworkFailure(exception)) {
                    showNetworkIssue()
                }
                return@launch
            }
            if (!isCurrentUnlockAccount(account.id, generation)) return@launch

            val mergedUnlocks = lessonUnlockRepository.loadLocal(account.id) +
                localUnlocks +
                unlockedCatalogueLessonIds +
                remoteData.unlockedLessonIds
            val mergedVisibleCount = lessonUnlockRepository.saveLocalVisibleLessonCount(
                account.id,
                maxOf(
                    lessonUnlockRepository.loadLocalVisibleLessonCount(account.id),
                    remoteData.visibleLessonCount
                )
            )
            try {
                lessonUnlockRepository.saveLocal(account.id, mergedUnlocks)
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not persist synced lesson unlocks locally.", exception)
            }
            unlockedCatalogueLessonIds = mergedUnlocks
            visibleLessonCount = mergedVisibleCount
            if (mergedUnlocks != remoteData.unlockedLessonIds ||
                mergedVisibleCount != remoteData.visibleLessonCount
            ) {
                try {
                    lessonUnlockRepository.saveToSupabase(
                        account.id,
                        mergedUnlocks,
                        mergedVisibleCount
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e("LessonUnlocks", "Could not sync lesson unlocks to Supabase.", exception)
                    if (notifyNetworkFailure && NetworkErrors.isNetworkFailure(exception)) {
                        showNetworkIssue()
                    }
                }
            }
        }
    }

    private fun isCurrentUnlockAccount(userId: String, generation: Int): Boolean =
        generation == unlockRestoreGeneration && accountState?.id == userId

    private fun refreshLessonCatalogue() {
        val repository = LessonCatalogueRepository(this)
        lifecycleScope.launch {
            val cachedEntries = repository.loadCached()
            catalogueState = LessonCatalogueUiState(
                entries = cachedEntries,
                isInitialLoading = cachedEntries.isEmpty(),
                isRefreshing = true
            )
            try {
                val freshEntries = repository.refresh()
                catalogueState = LessonCatalogueUiState(
                    entries = freshEntries,
                    isInitialLoading = false,
                    isRefreshing = false
                )
            } catch (exception: IOException) {
                Log.w("LessonCatalogue", "Could not refresh public lesson catalogue", exception)
                catalogueState = catalogueState.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    refreshFailed = true,
                    refreshNetworkFailed = NetworkErrors.isNetworkFailure(exception)
                )
            } catch (exception: JSONException) {
                Log.w("LessonCatalogue", "R2 returned invalid lesson catalogue JSON", exception)
                catalogueState = catalogueState.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    refreshFailed = true,
                    refreshNetworkFailed = false
                )
            } catch (exception: CancellationException) {
                catalogueState = catalogueState.copy(isInitialLoading = false, isRefreshing = false)
                throw exception
            } catch (exception: Exception) {
                Log.e("LessonCatalogue", "Unexpected catalogue refresh failure", exception)
                catalogueState = catalogueState.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    refreshFailed = true,
                    refreshNetworkFailed = false
                )
            }
        }
    }

    private suspend fun checkLessonCatalogueUpdates(): List<LessonCatalogueEntry> {
        catalogueState = catalogueState.copy(
            isRefreshing = true,
            refreshFailed = false,
            refreshNetworkFailed = false
        )
        try {
            val freshEntries = LessonCatalogueRepository(this).refresh()
            catalogueState = LessonCatalogueUiState(
                entries = freshEntries,
                isInitialLoading = false
            )
            return freshEntries
        } catch (exception: CancellationException) {
            catalogueState = catalogueState.copy(isInitialLoading = false, isRefreshing = false)
            throw exception
        } catch (exception: IOException) {
            Log.w("LessonCatalogue", "Could not check for new catalogue lessons", exception)
            catalogueState = catalogueState.copy(
                isInitialLoading = false,
                isRefreshing = false,
                refreshFailed = true,
                refreshNetworkFailed = NetworkErrors.isNetworkFailure(exception)
            )
            throw exception
        } catch (exception: JSONException) {
            Log.w("LessonCatalogue", "R2 returned invalid lesson catalogue JSON", exception)
            catalogueState = catalogueState.copy(
                isInitialLoading = false,
                isRefreshing = false,
                refreshFailed = true,
                refreshNetworkFailed = false
            )
            throw exception
        } catch (exception: Exception) {
            Log.e("LessonCatalogue", "Unexpected catalogue update check failure", exception)
            catalogueState = catalogueState.copy(
                isInitialLoading = false,
                isRefreshing = false,
                refreshFailed = true,
                refreshNetworkFailed = false
            )
            throw exception
        }
    }

    private fun saveLanguage(language: String) {
        activeLanguage = language
        getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)
            .edit()
            .putString(LANGUAGE_KEY, language)
            .apply()
    }

    private fun navigateTo(destination: AppScreen) {
        routeHistory += screenState
        screenState = destination
    }

    private fun setRoot(destination: AppScreen) {
        routeHistory.clear()
        screenState = destination
    }

    private fun goBack() {
        screenState = routeHistory.removeLastOrNull() ?: AppScreen.Landing
    }

    private fun loadLesson(lesson: LessonCatalogueEntry, fromCommunity: Boolean = false) {
        if (!fromCommunity) routeHistory += AppScreen.Browse
        val lessonType = lesson.normalizedType
        if (lessonType == null) {
            showLessonLoadFailure(
                "This lesson has an unsupported Kolam type.",
                fromCommunity
            )
            return
        }
        val packageUrl = lesson.packageUrls.firstOrNull()
        if (packageUrl.isNullOrBlank()) {
            showLessonLoadFailure(
                "This lesson does not have a downloadable package.",
                fromCommunity
            )
            return
        }

        screenState = if (fromCommunity) AppScreen.Loading else AppScreen.LessonDownloading
        lifecycleScope.launch {
            try {
                val loadedScreen = withContext(Dispatchers.IO) {
                    val destination = File(
                        File(cacheDir, "catalogue-lessons"),
                        packageUrl.toByteArray(Charsets.UTF_8).sha256Hex()
                    )
                    val isCachedValid = destination.isDirectory && File(destination, "path.json").isFile
                    if (!isCachedValid) {
                        KmpLessonExtractor.extractPackage(
                            encryptedPackage = downloadLessonPackage(packageUrl),
                            sharedSecret = KmpSecret.value(),
                            destination = destination
                        )
                    }
                    if (lessonType == "Rangoli") {
                        AppScreen.RangoliReady(
                            RangoliLesson.load(destination),
                            lesson.toPublishLessonDetails()
                        )
                    } else {
                        AppScreen.SikkuReady(
                            SikkuLesson.load(destination),
                            lesson.toPublishLessonDetails()
                        )
                    }
                }
                screenState = loadedScreen
            } catch (e: IOException) {
                Log.e("KmpLesson", "Could not read lesson assets", e)
                showLessonLoadFailure(
                    NetworkErrors.messageFor(e, e.message ?: "Could not read lesson files"),
                    fromCommunity
                )
            } catch (e: GeneralSecurityException) {
                Log.e("KmpLesson", "Could not decrypt lesson package", e)
                showLessonLoadFailure(e.message ?: "Could not decrypt lesson", fromCommunity)
            } catch (e: JSONException) {
                Log.e("KmpLesson", "Invalid lesson JSON data", e)
                showLessonLoadFailure(e.message ?: "Invalid lesson data", fromCommunity)
            } catch (e: IllegalArgumentException) {
                Log.e("KmpLesson", "Invalid lesson package", e)
                showLessonLoadFailure(e.message ?: "Invalid lesson package", fromCommunity)
            } catch (e: CancellationException) {
                if (screenState == AppScreen.Loading ||
                    screenState == AppScreen.LessonDownloading
                ) {
                    screenState = if (fromCommunity) AppScreen.Community else AppScreen.Browse
                }
                throw e
            } catch (e: Exception) {
                Log.e("KmpLesson", "Unexpected lesson loading failure", e)
                showLessonLoadFailure(
                    e.message?.takeIf(String::isNotBlank)
                        ?: withSelectedAppLanguage().getString(R.string.lessons_load_failed),
                    fromCommunity
                )
            } finally {
                if (screenState == AppScreen.Loading ||
                    screenState == AppScreen.LessonDownloading
                ) {
                    screenState = if (fromCommunity) AppScreen.Community else AppScreen.Browse
                }
            }
        }
    }

    private fun showLessonLoadFailure(message: String, fromCommunity: Boolean) {
        if (fromCommunity) {
            communityLessonOpenError = message
            screenState = AppScreen.Community
        } else {
            screenState = AppScreen.Failed(message)
        }
    }

    private fun downloadLessonPackage(packageUrl: String): ByteArray {
        val connection = URL(packageUrl).openConnection() as? HttpsURLConnection
            ?: throw IOException("Lesson package URL must use HTTPS")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            val status = connection.responseCode
            if (status !in 200..299) {
                throw HttpStatusFailureException(
                    statusCode = status,
                    message = "Lesson package request failed with HTTP $status"
                )
            }
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val SIGN_IN_SDK_TIMEOUT_MILLIS = 90_000L
        const val GOOGLE_CHOOSER_TIMEOUT_MILLIS = 180_000L
    }
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(byte) }

internal fun Intent.toContactSupportNotification(): ContactSupportNotification? {
    if (!getStringExtra("type").equals("support_message", ignoreCase = true) ||
        !getStringExtra("sender").equals("ADMIN", ignoreCase = true)
    ) return null
    val conversationId = sequenceOf(
        getStringExtra("conversationId"),
        getStringExtra("conversation_id")
    ).firstOrNull { !it.isNullOrBlank() }
    val messageId = sequenceOf(
        getStringExtra("messageId"),
        getStringExtra("message_id"),
        getStringExtra("id")
    ).firstOrNull { !it.isNullOrBlank() }
    return ContactSupportNotification(
        conversationId = conversationId?.takeIf(String::isNotBlank),
        messageId = messageId?.takeIf(String::isNotBlank)
    )
}

private fun Intent.toAnnouncementNotification(): AnnouncementNotification? {
    if (!getStringExtra("type").let {
            it.equals("announcement", ignoreCase = true) ||
                it.equals("announcement_message", ignoreCase = true)
        }
    ) return null
    return AnnouncementNotification(
        announcementId = sequenceOf(
            getStringExtra("announcementId"),
            getStringExtra("announcement_id")
        ).firstOrNull { !it.isNullOrBlank() }
    )
}

@Composable
private fun KolamMasterApp(
    screen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    activeLanguage: String,
    catalogue: LessonCatalogueUiState,
    unlockedCatalogueLessonIds: Set<String>,
    visibleLessonCount: Int,
    assetSetupProgress: AssetSetupProgress,
    isAssetSetupRunning: Boolean,
    assetSetupError: Int?,
    account: SupabaseAccount?,
    isAuthProcessing: Boolean,
    publishPickerLesson: PublishLessonDetails?,
    onPublishLesson: (PublishLessonDetails) -> Unit,
    onDismissPublishPicker: () -> Unit,
    onPublishImagesSelected: (PublishLessonDetails, List<Uri>, Set<Uri>) -> Unit,
    authLoadingMessage: Int?,
    authError: String?,
    authErrorTitle: String,
    onNavigate: (AppScreen) -> Unit,
    onLessonUnlocked: (String) -> Unit,
    onVisibleLessonCountChanged: (Int) -> Unit,
    onRetryAssetSetup: () -> Unit,
    onGoBack: () -> Unit,
    onHome: () -> Unit,
    onReturnToCatalogue: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onContinueAsGuest: () -> Unit,
    onSignOut: () -> Unit,
    onDismissAuthError: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onLanguageBack: () -> Unit,
    onLoadLesson: (LessonCatalogueEntry) -> Unit,
    onLoadCommunityLesson: (LessonCatalogueEntry) -> Unit,
    onCheckMoreThumbnails: suspend () -> List<LessonCatalogueEntry>,
    communityLessonOpenError: String?,
    onDismissCommunityLessonOpenError: () -> Unit,
    onCommunityLessonMissing: () -> Unit,
    supportNotification: ContactSupportNotification?,
    onSupportNotificationHandled: () -> Unit,
    onForegroundSupportAlertOpen: (SupportUnreadStore.ForegroundAlert) -> Unit,
    announcementNotification: AnnouncementNotification?,
    onAnnouncementNotificationHandled: () -> Unit,
    onOpenForegroundAnnouncement: (String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val localizedContext = context.withSelectedAppLanguage()
    val browseCatalogueState = remember { BrowseCatalogueState() }
    val contactAccountId = account?.takeUnless { it.isGuest }?.id
    val contactLocalCache = remember(context) { ContactLocalCache(context) }
    var storeDialog by remember { mutableStateOf<String?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var contactConversations by remember(contactAccountId, contactLocalCache) {
        mutableStateOf(
            contactAccountId?.let(contactLocalCache::loadConversations)?.value.orEmpty()
        )
    }
    var isContactLoading by remember(contactAccountId, contactLocalCache) {
        mutableStateOf(
            screen == AppScreen.ContactUs &&
                contactAccountId != null &&
                contactLocalCache.loadConversations(contactAccountId) == null
        )
    }
    var contactLoadError by remember(contactAccountId) { mutableStateOf<String?>(null) }
    var contactSignInRequired by remember(contactAccountId) { mutableStateOf(false) }
    var contactRefreshKey by remember { mutableIntStateOf(0) }
    var contactRetryKey by remember(contactAccountId) { mutableIntStateOf(0) }
    var lastContactRetryKey by remember(contactAccountId) { mutableIntStateOf(0) }
    var lastContactRefreshKey by remember { mutableIntStateOf(0) }
    var isNewContactConversation by remember(contactAccountId) { mutableStateOf(false) }
    var selectedContactConversationId by remember(contactAccountId) {
        mutableStateOf<String?>(null)
    }
    var verifiedContactConversation by remember(contactAccountId) {
        mutableStateOf<ContactConversation?>(null)
    }
    var contactDetailRefreshKey by remember { mutableIntStateOf(0) }
    var observedSupportUnreadVersion by remember {
        mutableStateOf(SupportUnreadStore.unreadVersion(context))
    }
    var observedSupportInboxRefreshSignal by remember {
        mutableIntStateOf(SupportUnreadStore.inboxRefreshSignal(context))
    }
    var contactRefreshInFlight by remember(contactAccountId) { mutableStateOf(false) }
    val contactRepository = remember(contactLocalCache) {
        ContactRepository(localCache = contactLocalCache)
    }
    val announcementRepository = remember(context) { AnnouncementRepository(context) }
    val communityRepository = remember(context) { CommunityRepository(context) }
    var communitySubmissions by remember(communityRepository) {
        mutableStateOf(communityRepository.loadCachedSubmissions())
    }
    var communityLoading by remember { mutableStateOf(false) }
    var communityError by remember { mutableStateOf<String?>(null) }
    var communityRefreshKey by remember { mutableIntStateOf(0) }
    var communityLessonRequestId by remember { mutableStateOf<String?>(null) }
    var announcementRefreshSignal by remember {
        mutableStateOf(AnnouncementRepository.refreshSignal(context))
    }
    var announcementRefreshKey by remember { mutableIntStateOf(0) }
    var announcements by remember(account?.id) {
        mutableStateOf(account?.let(announcementRepository::loadCached).orEmpty())
    }
    var announcementUnread by remember(account?.id) { mutableStateOf(false) }
    var announcementLoading by remember(account?.id) { mutableStateOf(account != null) }
    var announcementError by remember(account?.id) { mutableStateOf<String?>(null) }
    var announcementActionError by remember(account?.id) { mutableStateOf<String?>(null) }
    var announcementNetworkRetryAction by remember(account?.id) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    var announcementMarkReadRetryKey by remember(account?.id) { mutableIntStateOf(0) }
    var isMarkingAllAnnouncementsRead by remember(account?.id) { mutableStateOf(false) }
    var selectedAnnouncementId by remember(account?.id) { mutableStateOf<String?>(null) }
    var announcementDataLoaded by remember(account?.id) { mutableStateOf(false) }
    var lastAnnouncementRefreshSignal by remember(account?.id) {
        mutableStateOf<Long?>(null)
    }
    var lastAnnouncementRefreshKey by remember(account?.id) {
        mutableIntStateOf(-1)
    }
    var wasAnnouncementsScreenVisible by remember(account?.id) { mutableStateOf(false) }

    DisposableEffect(context) {
        val preferences = context.getSharedPreferences(
            "kolam_master_announcements",
            android.content.Context.MODE_PRIVATE
        )
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == AnnouncementRepository.REFRESH_SIGNAL_KEY) {
                    announcementRefreshSignal =
                        AnnouncementRepository.refreshSignal(context)
                }
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(screen == AppScreen.Community, communityRefreshKey) {
        if (screen != AppScreen.Community) return@LaunchedEffect
        communityLoading = true
        communityError = null
        try {
            communitySubmissions = communityRepository.loadApprovedSubmissions()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            communityError = if (NetworkErrors.isNetworkFailure(exception)) {
                localizedContext.getString(R.string.community_load_failed_network)
            } else {
                localizedContext.getString(R.string.community_load_failed)
            }
        } finally {
            communityLoading = false
        }
    }
    LaunchedEffect(account?.id, announcements, observedSupportUnreadVersion) {
        val currentAccount = account
        if (currentAccount == null) {
            LauncherBadgeHelper.updateExistingNotification(
                context,
                PendingAnnouncementStore.count(context) +
                    SupportUnreadStore.unreadMessageCount(context)
            )
            return@LaunchedEffect
        }
        SupportUnreadStore.useAccount(context, currentAccount.id)
        val announcementUnreadCount = PendingAnnouncementStore.reconcile(
            context,
            announcements.count { !it.isRead },
            announcements.map(Announcement::id).toSet()
        )
        LauncherBadgeHelper.updateExistingNotification(
            context,
            announcementUnreadCount + SupportUnreadStore.unreadMessageCount(context)
        )
    }

    LaunchedEffect(
        account?.id,
        announcementRefreshSignal,
        announcementRefreshKey,
        screen == AppScreen.Announcements
    ) {
        val currentAccount = account
        if (currentAccount == null) {
            announcements = emptyList()
            announcementUnread = false
            announcementLoading = true
            announcementDataLoaded = false
            return@LaunchedEffect
        }

        wasAnnouncementsScreenVisible = screen == AppScreen.Announcements

        if (!announcementDataLoaded) {
            announcements = announcementRepository.loadCached(currentAccount)
            announcementUnread = announcements.any { !it.isRead }
            announcementLoading = announcements.isEmpty()
            announcementDataLoaded = true
        }

        val isCacheStale = announcements.isEmpty() || !announcementRepository.isFresh(currentAccount.id)
        val shouldRefresh = isCacheStale ||
            lastAnnouncementRefreshSignal != announcementRefreshSignal ||
            lastAnnouncementRefreshKey != announcementRefreshKey

        if (!shouldRefresh) return@LaunchedEffect

        if (announcements.isEmpty()) {
            announcementLoading = true
        }
        announcementError = null
        announcementNetworkRetryAction = null
        try {
            announcements = announcementRepository.refresh()
            announcementUnread = announcements.any { !it.isRead }
            announcementError = null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (announcements.isEmpty()) {
                announcementError = if (NetworkErrors.isNetworkFailure(exception)) {
                    NetworkErrors.DISPLAY_TEXT
                } else {
                    localizedContext.getString(R.string.announcements_load_failed)
                }
            } else {
                announcementError = if (NetworkErrors.isNetworkFailure(exception)) {
                    localizedContext.getString(R.string.announcements_refresh_saved)
                } else {
                    null
                }
            }
            if (NetworkErrors.isNetworkFailure(exception)) {
                announcementNetworkRetryAction = { announcementRefreshKey++ }
            }
        } finally {
            announcementLoading = false
            announcementDataLoaded = true
            lastAnnouncementRefreshSignal = announcementRefreshSignal
            lastAnnouncementRefreshKey = announcementRefreshKey
        }
    }

    LaunchedEffect(announcementNotification, account?.id) {
        val request = announcementNotification ?: return@LaunchedEffect
        onNavigate(AppScreen.Announcements)
        val currentAccount = account ?: return@LaunchedEffect
        val announcementId = request.announcementId
        selectedAnnouncementId = null
        if (announcementId.isNullOrBlank()) {
            onAnnouncementNotificationHandled()
            return@LaunchedEffect
        }

        val cached = announcementRepository.loadCached(currentAccount)
        if (!announcementDataLoaded) {
            announcements = cached
            announcementUnread = cached.any { !it.isRead }
        }
        var matchingAnnouncement = announcements.firstOrNull { it.id == announcementId }
            ?: cached.firstOrNull { it.id == announcementId }
        if (matchingAnnouncement == null) {
            if (announcements.isEmpty()) announcementLoading = true
            try {
                announcements = announcementRepository.refresh()
                announcementUnread = announcements.any { !it.isRead }
                announcementDataLoaded = true
                announcementNetworkRetryAction = null
                matchingAnnouncement = announcements.firstOrNull { it.id == announcementId }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (announcements.isEmpty()) {
                    announcementError = if (NetworkErrors.isNetworkFailure(exception)) {
                        NetworkErrors.DISPLAY_TEXT
                    } else {
                        localizedContext.getString(R.string.announcements_load_failed)
                    }
                } else if (NetworkErrors.isNetworkFailure(exception)) {
                    announcementError =
                        localizedContext.getString(R.string.announcements_refresh_saved)
                }
                if (NetworkErrors.isNetworkFailure(exception)) {
                    announcementNetworkRetryAction = { announcementRefreshKey++ }
                }
            } finally {
                announcementLoading = false
            }
        }
        if (matchingAnnouncement != null) {
            selectedAnnouncementId = matchingAnnouncement?.id
        }
        onAnnouncementNotificationHandled()
    }

    LaunchedEffect(
        selectedAnnouncementId,
        account?.id,
        announcementMarkReadRetryKey
    ) {
        val currentAccount = account ?: return@LaunchedEffect
        val selected = announcements.firstOrNull { it.id == selectedAnnouncementId }
            ?: return@LaunchedEffect
        if (selected.isRead) return@LaunchedEffect
        announcementNetworkRetryAction = null
        try {
            val readAt = announcementRepository.markAsRead(selected.id)
            announcements = announcements.map { item ->
                if (item.id == selected.id) item.copy(readAt = readAt) else item
            }
            announcementUnread = announcements.any { !it.isRead }
            announcementError = null
            announcementActionError = null
            announcementNetworkRetryAction = null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            announcementActionError = if (NetworkErrors.isNetworkFailure(exception)) {
                NetworkErrors.DISPLAY_TEXT
            } else {
                localizedContext.getString(R.string.announcement_mark_failed)
            }
            announcementNetworkRetryAction = if (NetworkErrors.isNetworkFailure(exception)) {
                { announcementMarkReadRetryKey++ }
            } else {
                null
            }
        }
    }
    LaunchedEffect(
        screen,
        contactAccountId,
        contactRefreshKey,
        contactRetryKey,
        observedSupportInboxRefreshSignal,
        selectedContactConversationId
    ) {
        if (screen != AppScreen.ContactUs || contactAccountId == null) {
            if (contactAccountId == null) {
                contactConversations = emptyList()
                contactLoadError = null
                contactSignInRequired = false
            }
            isContactLoading = false
            return@LaunchedEffect
        }
        if (selectedContactConversationId != null) {
            isContactLoading = false
            return@LaunchedEffect
        }

        val cacheSnapshot = contactLocalCache.loadConversations(contactAccountId)
        if (cacheSnapshot != null && contactConversations.isEmpty()) {
            contactConversations = cacheSnapshot.value
        }
        val hasPendingPushFallback = SupportUnreadStore.consumeInboxRefreshSignal(context)
        val explicitRefreshRequested =
            contactRefreshKey != lastContactRefreshKey ||
                contactRetryKey != lastContactRetryKey
        lastContactRetryKey = contactRetryKey
        lastContactRefreshKey = contactRefreshKey
        val shouldRefreshList = shouldRefreshContactConversationList(
            hasPendingPushFallback = hasPendingPushFallback,
            explicitRefreshRequested = explicitRefreshRequested
        )
        if (!shouldRefreshList || contactRefreshInFlight) {
            contactLoadError = null
            isContactLoading = false
            return@LaunchedEffect
        }

        isContactLoading = true
        contactRefreshInFlight = true
        contactLoadError = null
        contactSignInRequired = false
        try {
            val refreshedConversations =
                contactRepository.getCurrentUserContactConversations()
            contactConversations = refreshedConversations
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (exception is ContactSignInRequiredException) {
                contactSignInRequired = true
            } else {
                contactLoadError = if (NetworkErrors.isNetworkFailure(exception)) {
                    NetworkErrors.DISPLAY_TEXT
                } else {
                    localizedContext.getString(R.string.conversations_load_failed)
                }
            }
        } finally {
            isContactLoading = false
            contactRefreshInFlight = false
        }
    }
    var unreadContactConversationIds by remember {
        mutableStateOf(SupportUnreadStore.unreadConversationIds(context))
    }
    var hasUnreadSupportNotifications by remember {
        mutableStateOf(SupportUnreadStore.hasUnread(context))
    }
    var foregroundSupportAlert by remember {
        mutableStateOf(SupportUnreadStore.foregroundAlert(context))
    }
    var foregroundAnnouncementAlert by remember {
        mutableStateOf(ForegroundAnnouncementAlertStore.pending(context))
    }
    DisposableEffect(context) {
        val preferences = ForegroundAnnouncementAlertStore.preferences(context)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                foregroundAnnouncementAlert =
                    ForegroundAnnouncementAlertStore.pending(context)
            }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        foregroundAnnouncementAlert = ForegroundAnnouncementAlertStore.pending(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    DisposableEffect(context, screen, selectedContactConversationId) {
        val preferences = context.getSharedPreferences(
            "kolam_master_support_notifications",
            android.content.Context.MODE_PRIVATE
        )
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            val updatedUnreadConversationIds =
                SupportUnreadStore.unreadConversationIds(context)
            unreadContactConversationIds = updatedUnreadConversationIds
            observedSupportUnreadVersion = SupportUnreadStore.unreadVersion(context)
            hasUnreadSupportNotifications = SupportUnreadStore.hasUnread(context)
            foregroundSupportAlert = SupportUnreadStore.foregroundAlert(context)
            if (SupportUnreadStore.isInboxRefreshSignal(key)) {
                observedSupportInboxRefreshSignal =
                    SupportUnreadStore.inboxRefreshSignal(context)
            }
            val activeConversationId = selectedContactConversationId
            if (screen == AppScreen.ContactUs &&
                activeConversationId != null &&
                key == SupportUnreadStore.conversationRefreshPreferenceKey(
                    activeConversationId
                )
            ) {
                if (SupportUnreadStore.consumeConversationRefreshSignal(
                        context,
                        activeConversationId
                    )
                ) {
                    contactDetailRefreshKey++
                }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        unreadContactConversationIds = SupportUnreadStore.unreadConversationIds(context)
        observedSupportUnreadVersion = SupportUnreadStore.unreadVersion(context)
        observedSupportInboxRefreshSignal = SupportUnreadStore.inboxRefreshSignal(context)
        hasUnreadSupportNotifications = SupportUnreadStore.hasUnread(context)
        foregroundSupportAlert = SupportUnreadStore.foregroundAlert(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var supportNotificationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(supportNotification, contactAccountId) {
        val reference = supportNotification ?: return@LaunchedEffect
        verifiedContactConversation = null
        if (contactAccountId == null) {
            onNavigate(AppScreen.ContactUs)
            return@LaunchedEffect
        }
        try {
            val conversationId = reference.conversationId ?: reference.messageId?.let {
                contactRepository.getContactConversationIdForMessage(it)
            }
            if (conversationId == null) {
                selectedContactConversationId = null
                contactRefreshKey++
                onNavigate(AppScreen.ContactUs)
                onSupportNotificationHandled()
                return@LaunchedEffect
            }
            if (reference.conversationId != null) {
                verifiedContactConversation =
                    contactRepository.getContactConversation(conversationId)
            }
            selectedContactConversationId = conversationId
            SupportUnreadStore.consumeConversationRefreshSignal(context, conversationId)
            contactDetailRefreshKey++
            SupportUnreadStore.clearConversation(context, conversationId, reference.messageId)
            unreadContactConversationIds =
                SupportUnreadStore.unreadConversationIds(context)
            isNewContactConversation = false
            onNavigate(AppScreen.ContactUs)
            onSupportNotificationHandled()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            selectedContactConversationId = null
            contactRefreshKey++
            supportNotificationError = if (NetworkErrors.isNetworkFailure(exception)) {
                NetworkErrors.MESSAGE
            } else {
                SUPPORT_CONVERSATION_OPEN_FAILED
            }
            onNavigate(AppScreen.ContactUs)
            onSupportNotificationHandled()
        }
    }
    LaunchedEffect(screen, selectedContactConversationId) {
        SupportUnreadStore.setActiveConversation(
            selectedContactConversationId.takeIf { screen == AppScreen.ContactUs }
        )
    }
    LaunchedEffect(screen, contactAccountId) {
        if (contactAccountId == null) {
            isNewContactConversation = false
            selectedContactConversationId = null
        } else if (screen != AppScreen.ContactUs) {
            isNewContactConversation = false
        }
    }

    fun submitMarkAllAnnouncementsRead(announcementIds: List<String>) {
        if (announcementIds.isEmpty() || isMarkingAllAnnouncementsRead) return
        scope.launch {
            isMarkingAllAnnouncementsRead = true
            announcementNetworkRetryAction = null
            try {
                val readAt = announcementRepository.markAllAsRead(announcementIds)
                announcements = announcements.map { item ->
                    if (item.id in announcementIds) item.copy(readAt = readAt) else item
                }
                announcementUnread = announcements.any { !it.isRead }
                announcementError = null
                announcementActionError = null
                announcementNetworkRetryAction = null
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                announcementActionError = if (NetworkErrors.isNetworkFailure(exception)) {
                    NetworkErrors.DISPLAY_TEXT
                } else {
                    ANNOUNCEMENTS_MARK_ALL_FAILED
                }
                if (NetworkErrors.isNetworkFailure(exception)) {
                    announcementNetworkRetryAction = {
                        submitMarkAllAnnouncementsRead(announcementIds)
                    }
                }
            } finally {
                isMarkingAllAnnouncementsRead = false
            }
        }
    }

    val myFoldersSelectedFolderId = remember(account?.takeUnless { it.isGuest }?.id) {
        mutableStateOf<String?>(null)
    }
    val handleAnnouncementDetailBack: () -> Unit = { selectedAnnouncementId = null }
    LaunchedEffect(screen) {
        if (screen != AppScreen.MyKolams) myFoldersSelectedFolderId.value = null
    }
    val handleScreenBack: () -> Unit = {
        when {
            screen == AppScreen.Announcements && selectedAnnouncementId != null -> {
                selectedAnnouncementId = null
            }
            screen == AppScreen.ContactUs && selectedContactConversationId != null -> {
                selectedContactConversationId = null
                verifiedContactConversation = null
            }
            screen == AppScreen.ContactUs && isNewContactConversation -> {
                isNewContactConversation = false
            }
            screen == AppScreen.MyKolams && myFoldersSelectedFolderId.value != null -> {
                myFoldersSelectedFolderId.value = null
            }
            else -> onGoBack()
        }
    }
    val rootAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    var publishSuccessActive by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        publishSuccessActive = false
    }
    val onMenuAction: (DrawerAction) -> Unit = { action ->
        when (action) {
            DrawerAction.Home -> onHome()
            DrawerAction.Profile -> onNavigate(AppScreen.Profile)
            DrawerAction.MyFolders -> onNavigate(AppScreen.MyKolams)
            DrawerAction.Language -> onNavigate(AppScreen.Language)
            DrawerAction.ContactUs -> {
                selectedContactConversationId = null
                verifiedContactConversation = null
                isNewContactConversation = false
                contactRefreshKey++
                onNavigate(AppScreen.ContactUs)
            }
            DrawerAction.Announcements -> onNavigate(AppScreen.Announcements)
            DrawerAction.UpdateApp -> openStoreFlow(
                context,
                { storeDialog = it },
                context.withSelectedAppLanguage().getString(R.string.update_app)
            )
            DrawerAction.RateApp -> openStoreFlow(
                context,
                { storeDialog = it },
                context.withSelectedAppLanguage().getString(R.string.rate_app)
            )
        }
    }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = when (screen) {
            AppScreen.ChooseLanguage,
            AppScreen.SignInInvitation,
            AppScreen.DownloadingAssets,
            AppScreen.Opening,
            AppScreen.Landing,
            AppScreen.Browse,
            AppScreen.MyKolams,
            AppScreen.Profile,
            AppScreen.Language,
            AppScreen.ContactUs,
            AppScreen.Announcements,
            AppScreen.Community,
            AppScreen.History,
            is AppScreen.PublishReady -> KolamBackground
            is AppScreen.SikkuReady,
            is AppScreen.RangoliReady -> Color.White
            else -> MaterialTheme.colorScheme.surface
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = rootAlpha.value }
            ) {
                if (screen != AppScreen.Landing &&
                    screen != AppScreen.Opening &&
                    screen != AppScreen.DownloadingAssets
                ) {
                    AppNavigationHeader(
                        onBack = handleScreenBack,
                        onHome = onHome,
                        onOpenDrawer = { drawerOpen = true },
                        showNavigationControls = screen != AppScreen.ChooseLanguage &&
                            screen != AppScreen.SignInInvitation,
                        unreadSupport = hasUnreadSupportNotifications,
                        unreadAnnouncements = announcementUnread
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (publishSuccessActive && screen is AppScreen.PublishReady) {
                        PublishConfetti(Modifier.matchParentSize())
                    }
                    AppScreenContent(
                        screen = screen,
                        browseCatalogueState = browseCatalogueState,
                        onScreenChange = onScreenChange,
                        activeLanguage = activeLanguage,
                        catalogue = catalogue,
                        unlockedCatalogueLessonIds = unlockedCatalogueLessonIds,
                        visibleLessonCount = visibleLessonCount,
                        assetSetupProgress = assetSetupProgress,
                        isAssetSetupRunning = isAssetSetupRunning,
                        assetSetupError = assetSetupError,
                        account = account,
                        isAuthProcessing = isAuthProcessing,
                        onNavigate = onNavigate,
                        onLessonUnlocked = onLessonUnlocked,
                        onVisibleLessonCountChanged = onVisibleLessonCountChanged,
                        onRetryAssetSetup = onRetryAssetSetup,
                        onGoBack = onGoBack,
                        onHome = onHome,
                        onReturnToCatalogue = onReturnToCatalogue,
                        onGoogleSignIn = onGoogleSignIn,
                        onContinueAsGuest = onContinueAsGuest,
                        onSignOut = onSignOut,
                        onLanguageChange = onLanguageChange,
                        onLanguageSelected = onLanguageSelected,
                        onLanguageBack = onLanguageBack,
                        onLoadLesson = onLoadLesson,
                        onLoadCommunityLesson = onLoadCommunityLesson,
                        onCheckMoreThumbnails = onCheckMoreThumbnails,
                        communityLessonOpenError = communityLessonOpenError,
                        onDismissCommunityLessonOpenError =
                            onDismissCommunityLessonOpenError,
                        onCommunityLessonMissing = {
                            communityLessonRequestId = null
                            onCommunityLessonMissing()
                        },
                        communityLessonRequestId = communityLessonRequestId,
                        onCommunityLessonRequest = { id ->
                            communityLessonRequestId = id
                            onNavigate(AppScreen.Browse)
                        },
                        onCommunityLessonRequestHandled = {
                            communityLessonRequestId = null
                        },
                        onPublishLesson = onPublishLesson,
                        onPublishSuccess = { publishSuccessActive = true },
                        contactAccountId = contactAccountId,
                        contactLocalCache = contactLocalCache,
                        contactDetailRefreshKey = contactDetailRefreshKey,
                        unreadSupport = hasUnreadSupportNotifications,
                        unreadAnnouncements = announcementUnread,
                        selectedMyFolderId = myFoldersSelectedFolderId,
                        contactConversations = contactConversations,
                        isContactLoading = isContactLoading,
                        contactLoadError = contactLoadError,
                        isContactSignedIn = contactAccountId != null &&
                            !contactSignInRequired,
                        onRetryContactLoad = { contactRetryKey++ },
                        isNewContactConversation = isNewContactConversation,
                        selectedContactConversationId = selectedContactConversationId,
                        onContactConversationSelected = {
                            selectedContactConversationId = it
                            verifiedContactConversation = null
                            val hasPendingRefresh =
                                SupportUnreadStore.consumeConversationRefreshSignal(context, it)
                            if (hasPendingRefresh) {
                                contactDetailRefreshKey++
                            }
                            SupportUnreadStore.clearConversation(context, it)
                            unreadContactConversationIds =
                                SupportUnreadStore.unreadConversationIds(context)
                        },
                        onLoadContactMessages = contactRepository::getContactMessages,
                        onLoadContactConversation = { conversationId ->
                            val verifiedConversation = verifiedContactConversation
                                ?.takeIf { it.id == conversationId }
                            if (verifiedConversation != null) {
                                verifiedContactConversation = null
                                verifiedConversation
                            } else {
                                contactRepository.getContactConversation(conversationId)
                            }
                        },
                        onSendContactMessage = contactRepository::sendContactMessage,
                        onUploadContactImage = contactRepository::uploadContactImage,
                        unreadContactConversationIds = unreadContactConversationIds,
                        announcements = announcements,
                        announcementsLoading = announcementLoading,
                        announcementsError = announcementError,
                        announcementsActionError = announcementActionError,
                        announcementNetworkRetryAction = announcementNetworkRetryAction,
                        onDismissAnnouncementNetworkError = {
                            announcementNetworkRetryAction = null
                        },
                        selectedAnnouncement = announcements.firstOrNull {
                            it.id == selectedAnnouncementId
                        },
                        communitySubmissions = communitySubmissions,
                        communityLoading = communityLoading,
                        communityError = communityError,
                        onRefreshCommunity = { communityRefreshKey++ },
                        isMarkingAllAnnouncementsRead = isMarkingAllAnnouncementsRead,
                        onRetryAnnouncements = { announcementRefreshKey++ },
                        onAnnouncementSelected = { selectedAnnouncementId = it.id },
                        onAnnouncementDetailBack = handleAnnouncementDetailBack,
                        onMarkAllAnnouncementsRead = {
                            submitMarkAllAnnouncementsRead(
                                announcementIdsForMarkAll(announcements)
                            )
                        },
                        onNewContactConversation = {
                            if (contactAccountId != null) {
                                isNewContactConversation = true
                            }
                        },
                        onCreateContactConversation = { phone, subject, initialMessage, imageBytes ->
                            val createdConversation = createContactConversationForAccount(
                                repository = contactRepository,
                                account = account,
                                subject = subject.trim(),
                                initialMessage = initialMessage.trim(),
                                phone = phone.trim(),
                                initialImageBytes = imageBytes
                            )
                            selectedContactConversationId = createdConversation.id
                            isNewContactConversation = false
                            contactConversations = contactAccountId
                                ?.let(contactLocalCache::loadConversations)
                                ?.value
                                .orEmpty()
                        },
                        onContactDetailRefreshHandled = { contactDetailRefreshKey = 0 },
                        onBegin = {
                            scope.launch {
                                rootAlpha.animateTo(
                                    0f,
                                    androidx.compose.animation.core.tween(
                                        420,
                                        easing = androidx.compose.animation.core.LinearEasing
                                    )
                                )
                                onHome()
                                androidx.compose.runtime.withFrameNanos { }
                                rootAlpha.animateTo(
                                    1f,
                                    androidx.compose.animation.core.tween(
                                        420,
                                        easing = androidx.compose.animation.core.LinearEasing
                                    )
                                )
                            }
                        },
                        onMenuAction = onMenuAction
                    )
                }
            }
            if (screen != AppScreen.Landing &&
                screen != AppScreen.Opening &&
                screen != AppScreen.DownloadingAssets
            ) {
                DrawerOverlay(
                    visible = drawerOpen,
                    settingsExpanded = settingsExpanded,
                    onSettingsExpandedChange = { settingsExpanded = it },
                    onBack = { drawerOpen = false },
                    onSelect = { action ->
                        scope.launch {
                            drawerOpen = false
                            kotlinx.coroutines.delay(260)
                            onMenuAction(action)
                        }
                    },
                    unreadContactUs = hasUnreadSupportNotifications,
                    unreadAnnouncements = announcementUnread
                )
            }
        }
    }

    publishPickerLesson?.let { lesson ->
        GalleryPublishPickerSheet(
            languageIdentifier = activeLanguage,
            onDismiss = onDismissPublishPicker,
            onProceed = {
                onPublishImagesSelected(lesson, emptyList(), emptySet())
            }
        )
    }

    StoreActionDialog(
        title = storeDialog,
        message = stringResource(R.string.connect_internet_to_continue),
        onDismiss = { storeDialog = null }
    )
    supportNotificationError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { supportNotificationError = null },
            title = {
                androidx.compose.material3.Text(stringResource(R.string.contact_us))
            },
            text = {
                androidx.compose.material3.Text(
                    if (message == SUPPORT_CONVERSATION_OPEN_FAILED) {
                        stringResource(R.string.support_conversation_open_failed)
                    } else if (message == NetworkErrors.MESSAGE) {
                        stringResource(R.string.network_error_message)
                    } else {
                        message
                    }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { supportNotificationError = null },
                    colors = kolamStandardButtonColors()
                ) { androidx.compose.material3.Text(stringResource(R.string.ok)) }
            }
        )
    }
    foregroundSupportAlert?.let { alert ->
        SupportForegroundAlertDialog(
            alert = alert,
            languageIdentifier = activeLanguage,
            onOpen = {
                SupportUnreadStore.dismissForegroundAlert(context, alert.messageId)
                foregroundSupportAlert = SupportUnreadStore.foregroundAlert(context)
                onForegroundSupportAlertOpen(alert)
            },
            onLater = {
                SupportUnreadStore.dismissForegroundAlert(context, alert.messageId)
                foregroundSupportAlert = SupportUnreadStore.foregroundAlert(context)
            }
        )
    }
    if (foregroundSupportAlert == null) {
        foregroundAnnouncementAlert?.let { alert ->
            AnnouncementForegroundAlertDialog(
                announcementId = alert.announcementId,
                languageIdentifier = activeLanguage,
                onOpen = { announcementId ->
                    ForegroundAnnouncementAlertStore.dismiss(context, alert)
                    foregroundAnnouncementAlert = null
                    onOpenForegroundAnnouncement(announcementId)
                },
                onLater = {
                    ForegroundAnnouncementAlertStore.dismiss(context, alert)
                    foregroundAnnouncementAlert = null
                }
            )
        }
    }
    AuthLoadingOverlay(message = authLoadingMessage)
    authError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissAuthError,
            title = {
                androidx.compose.material3.Text(
                    when (authErrorTitle) {
                        NetworkErrors.TITLE, "No internet connection" ->
                            stringResource(R.string.network_error_title)
                        AUTH_SIGN_IN_PROBLEM ->
                            stringResource(R.string.sign_in_problem)
                        else -> authErrorTitle
                    }
                )
            },
            text = {
                androidx.compose.material3.Text(
                    when (message) {
                        NetworkErrors.MESSAGE, "Please connect to the internet and try again." ->
                            stringResource(R.string.network_error_message)
                        AUTH_SIGN_IN_TIMEOUT ->
                            stringResource(R.string.sign_in_timed_out)
                        AUTH_GOOGLE_SIGN_IN_FAILED ->
                            stringResource(R.string.google_signin_failed)
                        else -> message
                    }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = onDismissAuthError,
                    colors = kolamStandardButtonColors()
                ) {
                    androidx.compose.material3.Text(stringResource(R.string.ok))
                }
            }
        )
    }

    BackHandler(enabled = publishPickerLesson == null &&
        !drawerOpen &&
        screen !is AppScreen.Landing &&
        screen !is AppScreen.ChooseLanguage &&
        screen !is AppScreen.Opening &&
        screen !is AppScreen.DownloadingAssets
    ) {
        when (screen) {
            AppScreen.SignInInvitation -> onScreenChange(AppScreen.ChooseLanguage)
            AppScreen.MyKolams -> handleScreenBack()
            AppScreen.ContactUs -> handleScreenBack()
            else -> onGoBack()
        }
    }

    var exitPressedOnce by remember { mutableStateOf(false) }
    LaunchedEffect(screen) {
        if (screen != AppScreen.Landing) {
            exitPressedOnce = false
        }
    }

    BackHandler(enabled = publishPickerLesson == null && !drawerOpen && screen is AppScreen.Landing) {
        if (exitPressedOnce) {
            var curr = context
            while (curr is android.content.ContextWrapper) {
                if (curr is android.app.Activity) {
                    curr.finish()
                    break
                }
                curr = curr.baseContext
            }
        } else {
            exitPressedOnce = true
            android.widget.Toast.makeText(context, "Press back again to exit.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun AppScreenContent(
    screen: AppScreen,
    browseCatalogueState: BrowseCatalogueState,
    onScreenChange: (AppScreen) -> Unit,
    activeLanguage: String,
    catalogue: LessonCatalogueUiState,
    unlockedCatalogueLessonIds: Set<String>,
    visibleLessonCount: Int,
    assetSetupProgress: AssetSetupProgress,
    isAssetSetupRunning: Boolean,
    assetSetupError: Int?,
    account: SupabaseAccount?,
    isAuthProcessing: Boolean,
    onNavigate: (AppScreen) -> Unit,
    onLessonUnlocked: (String) -> Unit,
    onVisibleLessonCountChanged: (Int) -> Unit,
    onRetryAssetSetup: () -> Unit,
    onGoBack: () -> Unit,
    onHome: () -> Unit,
    onReturnToCatalogue: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onContinueAsGuest: () -> Unit,
    onSignOut: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onLanguageBack: () -> Unit,
    onLoadLesson: (LessonCatalogueEntry) -> Unit,
    onLoadCommunityLesson: (LessonCatalogueEntry) -> Unit,
    onCheckMoreThumbnails: suspend () -> List<LessonCatalogueEntry>,
    communityLessonOpenError: String?,
    onDismissCommunityLessonOpenError: () -> Unit,
    onCommunityLessonMissing: () -> Unit,
    onPublishLesson: (PublishLessonDetails) -> Unit,
    onPublishSuccess: () -> Unit,
    communityLessonRequestId: String?,
    onCommunityLessonRequest: (String) -> Unit,
    onCommunityLessonRequestHandled: () -> Unit,
    contactAccountId: String?,
    contactLocalCache: ContactLocalCache,
    contactDetailRefreshKey: Int,
    unreadSupport: Boolean,
    unreadAnnouncements: Boolean,
    selectedMyFolderId: androidx.compose.runtime.MutableState<String?>,
    contactConversations: List<ContactConversation>,
    isContactLoading: Boolean,
    contactLoadError: String?,
    isContactSignedIn: Boolean,
    onRetryContactLoad: () -> Unit,
    isNewContactConversation: Boolean,
    selectedContactConversationId: String?,
    onContactConversationSelected: (String) -> Unit,
    onLoadContactMessages: suspend (String) -> List<ContactMessage>,
    onLoadContactConversation: suspend (String) -> ContactConversation,
    onSendContactMessage: suspend (String, String, String?) -> ContactMessage,
    onUploadContactImage: suspend (ByteArray) -> String,
    unreadContactConversationIds: Set<String>,
    onNewContactConversation: () -> Unit,
    onCreateContactConversation: suspend (String, String, String, ByteArray?) -> Unit,
    announcements: List<Announcement>,
    announcementsLoading: Boolean,
    announcementsError: String?,
    announcementsActionError: String?,
    announcementNetworkRetryAction: (() -> Unit)?,
    onDismissAnnouncementNetworkError: () -> Unit,
    selectedAnnouncement: Announcement?,
    communitySubmissions: List<CommunitySubmission>,
    communityLoading: Boolean,
    communityError: String?,
    onRefreshCommunity: () -> Unit,
    isMarkingAllAnnouncementsRead: Boolean,
    onRetryAnnouncements: () -> Unit,
    onAnnouncementSelected: (Announcement) -> Unit,
    onAnnouncementDetailBack: () -> Unit,
    onMarkAllAnnouncementsRead: () -> Unit,
    onBegin: () -> Unit,
    onMenuAction: (DrawerAction) -> Unit,
    onContactDetailRefreshHandled: () -> Unit
) {
    when (screen) {
        AppScreen.ChooseLanguage -> LanguageSelectionScreen(onLanguageSelected)
        AppScreen.SignInInvitation -> SignInInvitationScreen(
            onGoogleSignIn = onGoogleSignIn,
            onSkip = onContinueAsGuest,
            isLoading = isAuthProcessing
        )
        AppScreen.DownloadingAssets -> InitialAssetSetupScreen(
            progress = assetSetupProgress,
            isRunning = isAssetSetupRunning,
            errorMessage = assetSetupError,
            onRetry = onRetryAssetSetup
        )
        AppScreen.Opening -> OpeningScreen(onBegin)
        AppScreen.Landing -> LandingScreen(
            onDestination = { destination ->
                onNavigate(
                    when (destination) {
                        LandingDestination.Browse -> AppScreen.Browse
                        LandingDestination.MyKolams -> AppScreen.MyKolams
                        LandingDestination.Community -> AppScreen.Community
                        LandingDestination.History -> AppScreen.History
                    }
                )
            },
            onMenuAction = onMenuAction,
            unreadSupport = unreadSupport,
            unreadAnnouncements = unreadAnnouncements
        )
        AppScreen.Browse -> BrowseLessonScreen(
            catalogue = catalogue,
            browseState = browseCatalogueState,
            unlockedLessonIds = unlockedCatalogueLessonIds,
            accountId = account?.takeUnless { it.isGuest }?.id,
            isGuest = account?.isGuest == true,
            isAuthProcessing = isAuthProcessing,
            visibleLessonCount = visibleLessonCount,
            onVisibleLessonCountChanged = onVisibleLessonCountChanged,
            onLessonUnlocked = onLessonUnlocked,
            onGoogleSignIn = onGoogleSignIn,
            communityLessonId = communityLessonRequestId,
            onCommunityLessonRequestHandled = onCommunityLessonRequestHandled,
            onCommunityLessonMissing = {
                onCommunityLessonRequestHandled()
                onCommunityLessonMissing()
            },
            onCommunityLessonSelected = { lesson ->
                onLoadCommunityLesson(lesson)
            },
            onCheckMoreThumbnails = onCheckMoreThumbnails,
            onLessonSelected = onLoadLesson
        )
        AppScreen.MyKolams -> MyFoldersDestination(
            accountId = account?.takeUnless { it.isGuest }?.id,
            catalogueEntries = catalogue.entries,
            onSignIn = onGoogleSignIn,
            onCancel = onGoBack,
            isSignInProcessing = isAuthProcessing,
            selectedFolderIdState = selectedMyFolderId
        )
        AppScreen.Profile -> ProfileDestination(
            onSignIn = onGoogleSignIn,
            onBack = onGoBack,
            account = account
                ?.takeUnless { it.isGuest }
                ?.let {
                    AccountProfile(
                        name = it.name,
                        email = it.email,
                        avatarUrl = it.avatarUrl
                    )
                },
            onSignOut = onSignOut
        )
        AppScreen.Language -> LanguageSettingsScreen(
            activeLanguage = activeLanguage,
            onSelect = onLanguageChange
        )
        AppScreen.ContactUs -> {
            if (isContactSignedIn && selectedContactConversationId != null) {
                ContactConversationDetailDestination(
                    conversationId = selectedContactConversationId,
                    accountId = contactAccountId,
                    localCache = contactLocalCache,
                    onLoadConversation = onLoadContactConversation,
                    onLoadMessages = onLoadContactMessages,
                    onSendMessage = onSendContactMessage,
                    onUploadImage = onUploadContactImage,
                    externalRefreshKey = contactDetailRefreshKey,
                    onExternalRefreshHandled = onContactDetailRefreshHandled
                )
            } else if (isContactSignedIn && isNewContactConversation) {
                ContactNewConversationDestination(
                    onCreate = onCreateContactConversation
                )
            } else {
                ContactUsDestination(
                    onSignIn = onGoogleSignIn,
                    onBack = onGoBack,
                    signedIn = isContactSignedIn,
                    conversations = contactConversations,
                    isLoading = isContactLoading,
                    errorMessage = contactLoadError,
                    onRetry = onRetryContactLoad,
                    onNewConversation = onNewContactConversation,
                    onConversationSelected = onContactConversationSelected,
                    unreadConversationIds = unreadContactConversationIds
                )
            }
        }
        AppScreen.Announcements -> AnnouncementsDestination(
            announcements = announcements,
            isLoading = announcementsLoading,
            errorMessage = announcementsError,
            actionErrorMessage = announcementsActionError,
            selectedAnnouncement = selectedAnnouncement,
            isMarkingAllRead = isMarkingAllAnnouncementsRead,
            onRetry = onRetryAnnouncements,
            networkRetryAction = announcementNetworkRetryAction,
            onDismissNetworkDialog = onDismissAnnouncementNetworkError,
            onAnnouncementSelected = onAnnouncementSelected,
            onBackFromDetail = onAnnouncementDetailBack,
            onMarkAllRead = onMarkAllAnnouncementsRead
        )
        AppScreen.Community -> CommunityScreen(
            submissions = communitySubmissions,
            isLoading = communityLoading,
            errorMessage = communityError,
            onRefresh = onRefreshCommunity,
            catalogueEntries = catalogue.entries,
            isCatalogueLoading = catalogue.isInitialLoading || catalogue.isRefreshing,
            lessonOpenError = communityLessonOpenError,
            onOpenLesson = onCommunityLessonRequest,
            onDismissLessonOpenError = onDismissCommunityLessonOpenError
        )
        AppScreen.History -> HistoryScreen(Modifier.fillMaxSize())
        AppScreen.Loading -> LessonLoadingScreen(R.string.loading_lesson)
        AppScreen.LessonDownloading -> LessonLoadingScreen(R.string.downloading_lesson)
        is AppScreen.SikkuReady -> SikkuLessonScreen(
            lesson = screen.lesson,
            onPublish = { onPublishLesson(screen.publishLesson) },
            modifier = Modifier.fillMaxSize()
        )
        is AppScreen.RangoliReady -> RangoliLessonScreen(
            lesson = screen.lesson,
            onPublish = { onPublishLesson(screen.publishLesson) },
            modifier = Modifier.fillMaxSize()
        )
        is AppScreen.PublishReady -> GalleryPublishScreen(
            lesson = screen.lesson,
            initialImageUris = screen.initialImageUris,
            temporaryImageUris = screen.temporaryImageUris,
            account = account,
            repository = remember { GalleryPublishRepository() },
            onBack = onReturnToCatalogue,
            onPublishSuccess = onPublishSuccess,
            modifier = Modifier.fillMaxSize()
        )
        is AppScreen.Failed -> androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            if (screen.message == NetworkErrors.DISPLAY_TEXT) {
                androidx.compose.material3.Text(
                    stringResource(R.string.network_error_title),
                    color = KolamText,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                androidx.compose.material3.Text(
                    stringResource(R.string.network_error_message),
                    color = KolamText
                )
            } else {
                androidx.compose.material3.Text(
                    stringResource(R.string.lesson_error_detail, screen.message),
                    color = KolamText
                )
            }
            androidx.compose.material3.Button(onClick = onGoBack) {
                androidx.compose.material3.Text(stringResource(R.string.back))
            }
        }
    }
}

@Composable
private fun LessonLoadingScreen(messageResource: Int) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamSurface),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = KolamBronze,
                strokeWidth = 2.dp
            )
            androidx.compose.material3.Text(
                text = stringResource(messageResource),
                modifier = Modifier.weight(1f),
                color = KolamText,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}

private val supportedLanguageNames = supportedLanguages.mapTo(mutableSetOf()) { it.first }
