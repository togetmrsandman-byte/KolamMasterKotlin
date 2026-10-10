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
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
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
private const val NOTIFICATIONS_PERMISSION_REQUESTED_KEY =
    "notifications_permission_requested"
private const val ONBOARDING_PERMISSION_PENDING_STATE_KEY =
    "onboarding_permission_pending"

private sealed interface AppScreen {
    data object ChooseLanguage : AppScreen
    data object SignInInvitation : AppScreen
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
    private var activeLanguage by mutableStateOf("English")
    private var catalogueState by mutableStateOf(LessonCatalogueUiState())
    private var unlockedCatalogueLessonIds by mutableStateOf<Set<String>>(emptySet())
    private var accountState by mutableStateOf<SupabaseAccount?>(null)
    private var isAuthProcessing by mutableStateOf(false)
    private var authLoadingMessage by mutableStateOf<String?>(null)
    private var authError by mutableStateOf<String?>(null)
    private var authErrorTitle by mutableStateOf("Sign-in problem")
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
                screenState = AppScreen.Opening
            }
        }
    private var pendingContactSupportNotification by
        mutableStateOf<ContactSupportNotification?>(null)
    private var pendingPublishLesson by mutableStateOf<PublishLessonDetails?>(null)
    private var publishPickerLesson by mutableStateOf<PublishLessonDetails?>(null)
    private var communityLessonOpenError by mutableStateOf<String?>(null)
    private var pendingAnnouncementNotification by
        mutableStateOf<AnnouncementNotification?>(null)
    private var contactForegroundRefreshKey by mutableIntStateOf(0)
    private var supportMessageVersion by mutableStateOf(0L)

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (!googleSignInAwaitingResult) return@registerForActivityResult
            googleSignInAwaitingResult = false
            googleChooserTimeoutJob?.cancel()
            googleChooserTimeoutJob = null
            if (result.resultCode != Activity.RESULT_OK && result.data == null) {
                isAuthProcessing = false
                authLoadingMessage = null
                authError = null
                pendingPublishLesson = null
                return@registerForActivityResult
            }
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
                        fallback = "Google sign-in could not be completed. Please try again.",
                        exposeNonNetworkDetails = false
                    )
                }
            } catch (exception: IllegalStateException) {
                pendingPublishLesson = null
                showGoogleSignInError(
                    exception,
                    fallback = "Google sign-in could not be completed. Please try again.",
                    exposeNonNetworkDetails = false
                )
            } catch (exception: Exception) {
                pendingPublishLesson = null
                showGoogleSignInError(
                    exception,
                    fallback = "Google sign-in could not be completed. Please try again.",
                    exposeNonNetworkDetails = false
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportMessageVersion = SupportUnreadStore.messageVersion(this)
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
        activeLanguage = savedLanguage ?: "English"
        lessonUnlockRepository = LessonUnlockRepository(this)
        screenState = when {
            onboardingPermissionFlow.isWaitingForPermissionResult -> AppScreen.SignInInvitation
            savedLanguage == null -> AppScreen.ChooseLanguage
            else -> AppScreen.Opening
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
                KolamMasterApp(
                    screen = screenState,
                    onScreenChange = { screenState = it },
                    activeLanguage = activeLanguage,
                    catalogue = catalogueState,
                    unlockedCatalogueLessonIds = unlockedCatalogueLessonIds,
                    account = accountState,
                    isAuthProcessing = isAuthProcessing,
                    publishPickerLesson = publishPickerLesson,
                    authLoadingMessage = authLoadingMessage,
                    authError = authError,
                    authErrorTitle = authErrorTitle,
                    onNavigate = ::navigateTo,
                    onLessonUnlocked = ::recordLessonUnlock,
                    onGoBack = ::goBack,
                    onHome = { setRoot(AppScreen.Landing) },
                    onGoogleSignIn = ::beginGoogleSignIn,
                    onContinueAsGuest = ::continueAsGuest,
                    onSignOut = ::signOutToGuest,
                    onDismissAuthError = { authError = null },
                    onLanguageChange = { language ->
                        saveLanguage(language)
                        goBack()
                    },
                    onLanguageSelected = { language ->
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
                    communityLessonOpenError = communityLessonOpenError,
                    onDismissCommunityLessonOpenError = {
                        communityLessonOpenError = null
                    },
                    onCommunityLessonMissing = {
                        communityLessonOpenError =
                            "The lesson associated with this kolam could not be found."
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
                    },
                    contactForegroundRefreshKey = contactForegroundRefreshKey,
                    supportMessageVersion = supportMessageVersion
                )
            }
        }

        refreshLessonCatalogue()
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
        if (screenState == AppScreen.ContactUs) contactForegroundRefreshKey++
        AnnouncementRepository.requestRefresh(this)
        refreshLauncherBadgeFromCache()
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
        authLoadingMessage = "Signing you in, please wait..."
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
                        continueOnboardingAfterPermission()
                    }
                    isAuthProcessing = false
                    authLoadingMessage = null
                    return@launch
                }
                runBoundedNetworkOperation(SIGN_IN_SDK_TIMEOUT_MILLIS) {
                    SupabaseGuestAuth.getOrCreateGuestUserId()
                }
                googleSignInAwaitingResult = true
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
                googleChooserTimeoutJob = lifecycleScope.launch {
                    delay(GOOGLE_CHOOSER_TIMEOUT_MILLIS)
                    if (isAuthProcessing) {
                        googleSignInAwaitingResult = false
                        pendingPublishLesson = null
                        authErrorTitle = "Sign-in problem"
                        authError = "Sign-in took too long. Please try again."
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
            try {
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
                    } catch (exception: Exception) {
                        Log.e("LessonUnlocks", "Could not migrate Guest unlocks locally.", exception)
                    }
                }
                accountState = newAccount
                registerFcmToken(newAccount)
                restoreLessonUnlocks(newAccount)
                openPendingPublishAfterSignIn(newAccount)
                if (screenState == AppScreen.SignInInvitation) {
                    continueOnboardingAfterPermission()
                }
            } catch (exception: CancellationException) {
                pendingPublishLesson = null
                throw exception
            } catch (exception: Exception) {
                pendingPublishLesson = null
                showGoogleSignInError(exception)
            } finally {
                clearAuthProcessing()
            }
        }
    }

    private fun continueAsGuest() {
        if (isAuthProcessing) return
        isAuthProcessing = true
        authError = null
        lifecycleScope.launch {
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
                continueOnboardingAfterPermission()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showAuthError(exception)
            } finally {
                clearAuthProcessing()
            }
        }
    }

    private fun requestPublish(lesson: PublishLessonDetails) {
        if (!hasValidatedInternetConnection()) {
            authErrorTitle = "No internet connection"
            authError = "Please connect to the internet and try again."
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

    private fun continueOnboardingAfterPermission() {
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
            screenState = AppScreen.Opening
            return
        }

        preferences.edit()
            .putBoolean(NOTIFICATIONS_PERMISSION_REQUESTED_KEY, true)
            .commit()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun signOutToGuest() {
        if (isAuthProcessing) return
        isAuthProcessing = true
        authLoadingMessage = "Signing you out, please wait..."
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
        fallback: String = "Authentication failed. Please try again.",
        exposeNonNetworkDetails: Boolean = true
    ) {
        val networkFailure = NetworkErrors.isNetworkFailure(exception)
        authErrorTitle = if (networkFailure) NetworkErrors.TITLE else "Sign-in problem"
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
        fallback: String = "Authentication failed. Please try again.",
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
        unlockedCatalogueLessonIds = localUnlocks
        lifecycleScope.launch {
            val remoteUnlocks = try {
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
                remoteUnlocks
            try {
                lessonUnlockRepository.saveLocal(account.id, mergedUnlocks)
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not persist merged lesson unlocks locally.", exception)
            }
            unlockedCatalogueLessonIds = mergedUnlocks
            if (mergedUnlocks != remoteUnlocks) {
                try {
                    lessonUnlockRepository.saveToSupabase(account.id, mergedUnlocks)
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

    private fun syncLessonUnlocks(
        account: SupabaseAccount,
        localUnlocks: Set<String>,
        notifyNetworkFailure: Boolean = false
    ) {
        val generation = unlockRestoreGeneration
        lifecycleScope.launch {
            val remoteUnlocks = try {
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
                remoteUnlocks
            try {
                lessonUnlockRepository.saveLocal(account.id, mergedUnlocks)
            } catch (exception: Exception) {
                Log.e("LessonUnlocks", "Could not persist synced lesson unlocks locally.", exception)
            }
            unlockedCatalogueLessonIds = mergedUnlocks
            if (mergedUnlocks != remoteUnlocks) {
                try {
                    lessonUnlockRepository.saveToSupabase(account.id, mergedUnlocks)
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

        screenState = AppScreen.Loading
        lifecycleScope.launch {
            try {
                val loadedScreen = withContext(Dispatchers.IO) {
                    val destination = File(
                        File(cacheDir, "catalogue-lessons"),
                        packageUrl.toByteArray(Charsets.UTF_8).sha256Hex()
                    )
                    KmpLessonExtractor.extractPackage(
                        encryptedPackage = downloadLessonPackage(packageUrl),
                        sharedSecret = KmpSecret.value(),
                        destination = destination
                    )
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
                if (screenState == AppScreen.Loading) {
                    screenState = if (fromCommunity) AppScreen.Community else AppScreen.Browse
                }
                throw e
            } catch (e: Exception) {
                Log.e("KmpLesson", "Unexpected lesson loading failure", e)
                showLessonLoadFailure(
                    e.message?.takeIf(String::isNotBlank)
                        ?: "Could not load this lesson. Please try again.",
                    fromCommunity
                )
            } finally {
                if (screenState == AppScreen.Loading) {
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
    account: SupabaseAccount?,
    isAuthProcessing: Boolean,
    publishPickerLesson: PublishLessonDetails?,
    onPublishLesson: (PublishLessonDetails) -> Unit,
    onDismissPublishPicker: () -> Unit,
    onPublishImagesSelected: (PublishLessonDetails, List<Uri>, Set<Uri>) -> Unit,
    authLoadingMessage: String?,
    authError: String?,
    authErrorTitle: String,
    onNavigate: (AppScreen) -> Unit,
    onLessonUnlocked: (String) -> Unit,
    onGoBack: () -> Unit,
    onHome: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onContinueAsGuest: () -> Unit,
    onSignOut: () -> Unit,
    onDismissAuthError: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onLanguageBack: () -> Unit,
    onLoadLesson: (LessonCatalogueEntry) -> Unit,
    onLoadCommunityLesson: (LessonCatalogueEntry) -> Unit,
    communityLessonOpenError: String?,
    onDismissCommunityLessonOpenError: () -> Unit,
    onCommunityLessonMissing: () -> Unit,
    supportNotification: ContactSupportNotification?,
    onSupportNotificationHandled: () -> Unit,
    onForegroundSupportAlertOpen: (SupportUnreadStore.ForegroundAlert) -> Unit,
    announcementNotification: AnnouncementNotification?,
    onAnnouncementNotificationHandled: () -> Unit,
    onOpenForegroundAnnouncement: (String?) -> Unit,
    contactForegroundRefreshKey: Int,
    supportMessageVersion: Long
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var storeDialog by remember { mutableStateOf<String?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var contactConversations by remember {
        mutableStateOf<List<ContactConversation>>(emptyList())
    }
    var isContactLoading by remember { mutableStateOf(false) }
    var contactLoadError by remember { mutableStateOf<String?>(null) }
    var contactSignInRequired by remember { mutableStateOf(false) }
    var contactRefreshKey by remember { mutableIntStateOf(0) }
    var isNewContactConversation by remember { mutableStateOf(false) }
    var selectedContactConversationId by remember { mutableStateOf<String?>(null) }
    var contactDetailRefreshKey by remember { mutableIntStateOf(0) }
    var observedSupportMessageVersion by remember {
        mutableStateOf(supportMessageVersion)
    }
    var observedSupportUnreadVersion by remember {
        mutableStateOf(SupportUnreadStore.unreadVersion(context))
    }
    val contactRepository = remember { ContactRepository() }
    val announcementRepository = remember(context) { AnnouncementRepository(context) }
    val communityRepository = remember { CommunityRepository() }
    var communitySubmissions by remember { mutableStateOf<List<CommunitySubmission>>(emptyList()) }
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
        communitySubmissions = emptyList()
        try {
            communitySubmissions = communityRepository.loadApprovedSubmissions()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            communityError = if (NetworkErrors.isNetworkFailure(exception)) {
                "Could not load the community. Check your connection and try again."
            } else {
                "Could not load the community. Please try again."
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

        val enteringAnnouncements = screen == AppScreen.Announcements &&
            !wasAnnouncementsScreenVisible
        wasAnnouncementsScreenVisible = screen == AppScreen.Announcements
        val shouldRefresh = !announcementDataLoaded ||
            lastAnnouncementRefreshSignal != announcementRefreshSignal ||
            lastAnnouncementRefreshKey != announcementRefreshKey ||
            enteringAnnouncements
        if (!shouldRefresh) return@LaunchedEffect

        if (!announcementDataLoaded) {
            announcements = announcementRepository.loadCached(currentAccount)
            announcementUnread = announcements.any { !it.isRead }
            announcementLoading = announcements.isEmpty()
        } else if (announcements.isEmpty()) {
            announcementLoading = true
        }
        announcementError = null
        try {
            announcements = announcementRepository.refresh()
            announcementUnread = announcements.any { !it.isRead }
            announcementError = null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (announcements.isEmpty()) {
                announcementError = "Could not load announcements. Please try again."
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
                matchingAnnouncement = announcements.firstOrNull { it.id == announcementId }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (announcements.isEmpty()) {
                    announcementError = "Could not load announcements. Please try again."
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

    LaunchedEffect(selectedAnnouncementId, account?.id) {
        val currentAccount = account ?: return@LaunchedEffect
        val selected = announcements.firstOrNull { it.id == selectedAnnouncementId }
            ?: return@LaunchedEffect
        if (selected.isRead) return@LaunchedEffect
        try {
            val readAt = announcementRepository.markAsRead(selected.id)
            announcements = announcements.map { item ->
                if (item.id == selected.id) item.copy(readAt = readAt) else item
            }
            announcementUnread = announcements.any { !it.isRead }
            announcementError = null
            announcementActionError = null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            announcementActionError = "Could not mark this announcement as read."
        }
    }
    val contactAccountId = account?.takeUnless { it.isGuest }?.id
    LaunchedEffect(
        screen,
        contactAccountId,
        contactRefreshKey,
        contactForegroundRefreshKey,
        observedSupportMessageVersion
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

        isContactLoading = true
        contactLoadError = null
        contactSignInRequired = false
        try {
            contactConversations = contactRepository.getCurrentUserContactConversations()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            contactConversations = emptyList()
            if (exception is ContactSignInRequiredException) {
                contactSignInRequired = true
            } else {
                contactLoadError = if (NetworkErrors.isNetworkFailure(exception)) {
                    NetworkErrors.DISPLAY_TEXT
                } else {
                    "Could not load conversations. Please try again."
                }
            }
        } finally {
            isContactLoading = false
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
            val updatedMessageVersion = SupportUnreadStore.messageVersion(context)
            observedSupportMessageVersion = updatedMessageVersion
            observedSupportUnreadVersion = SupportUnreadStore.unreadVersion(context)
            hasUnreadSupportNotifications = SupportUnreadStore.hasUnread(context)
            foregroundSupportAlert = SupportUnreadStore.foregroundAlert(context)
            val activeConversationId = selectedContactConversationId
            if (screen == AppScreen.ContactUs &&
                activeConversationId != null &&
                key == SupportUnreadStore.conversationRefreshPreferenceKey(
                    activeConversationId
                )
            ) {
                contactDetailRefreshKey++
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        unreadContactConversationIds = SupportUnreadStore.unreadConversationIds(context)
        observedSupportMessageVersion = SupportUnreadStore.messageVersion(context)
        observedSupportUnreadVersion = SupportUnreadStore.unreadVersion(context)
        hasUnreadSupportNotifications = SupportUnreadStore.hasUnread(context)
        foregroundSupportAlert = SupportUnreadStore.foregroundAlert(context)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var supportNotificationError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(supportNotification, contactAccountId) {
        val reference = supportNotification ?: return@LaunchedEffect
        if (contactAccountId == null) {
            onNavigate(AppScreen.ContactUs)
            return@LaunchedEffect
        }
        try {
            val conversationId = reference.conversationId ?: reference.messageId?.let {
                contactRepository.getContactConversationIdForMessage(it)
            }
            if (conversationId == null) {
                onNavigate(AppScreen.ContactUs)
                onSupportNotificationHandled()
                return@LaunchedEffect
            }
            if (reference.conversationId != null) {
                contactRepository.getContactConversation(conversationId)
            }
            selectedContactConversationId = conversationId
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
            supportNotificationError = if (NetworkErrors.isNetworkFailure(exception)) {
                NetworkErrors.MESSAGE
            } else {
                "This support conversation could not be opened."
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
                contactRefreshKey++
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
                isNewContactConversation = false
                contactRefreshKey++
                onNavigate(AppScreen.ContactUs)
            }
            DrawerAction.Announcements -> onNavigate(AppScreen.Announcements)
            DrawerAction.UpdateApp -> openStoreFlow(context, { storeDialog = it }, "Update App")
            DrawerAction.RateApp -> openStoreFlow(context, { storeDialog = it }, "Rate App")
        }
    }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = when (screen) {
            AppScreen.ChooseLanguage,
            AppScreen.SignInInvitation,
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
            else -> MaterialTheme.colorScheme.surface
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            if (publishSuccessActive && screen is AppScreen.PublishReady) {
                PublishConfetti(Modifier.matchParentSize())
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = rootAlpha.value }
            ) {
                if (screen != AppScreen.Landing && screen != AppScreen.Opening) {
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
                    AppScreenContent(
                        screen = screen,
                        onScreenChange = onScreenChange,
                        activeLanguage = activeLanguage,
                        catalogue = catalogue,
                        unlockedCatalogueLessonIds = unlockedCatalogueLessonIds,
                        account = account,
                        isAuthProcessing = isAuthProcessing,
                        onNavigate = onNavigate,
                        onLessonUnlocked = onLessonUnlocked,
                        onGoBack = onGoBack,
                        onHome = onHome,
                        onGoogleSignIn = onGoogleSignIn,
                        onContinueAsGuest = onContinueAsGuest,
                        onSignOut = onSignOut,
                        onLanguageChange = onLanguageChange,
                        onLanguageSelected = onLanguageSelected,
                        onLanguageBack = onLanguageBack,
                        onLoadLesson = onLoadLesson,
                        onLoadCommunityLesson = onLoadCommunityLesson,
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
                        contactForegroundRefreshKey = contactForegroundRefreshKey,
                        supportMessageVersion = supportMessageVersion,
                        contactDetailRefreshKey = contactDetailRefreshKey,
                        unreadSupport = hasUnreadSupportNotifications,
                        unreadAnnouncements = announcementUnread,
                        selectedMyFolderId = myFoldersSelectedFolderId,
                        contactConversations = contactConversations,
                        isContactLoading = isContactLoading,
                        contactLoadError = contactLoadError,
                        isContactSignedIn = contactAccountId != null &&
                            !contactSignInRequired,
                        onRetryContactLoad = { contactRefreshKey++ },
                        isNewContactConversation = isNewContactConversation,
                        selectedContactConversationId = selectedContactConversationId,
                        onContactConversationSelected = {
                            selectedContactConversationId = it
                            SupportUnreadStore.clearConversation(context, it)
                            unreadContactConversationIds =
                                SupportUnreadStore.unreadConversationIds(context)
                        },
                        onLoadContactMessages = contactRepository::getContactMessages,
                        onLoadContactConversation = contactRepository::getContactConversation,
                        onSendContactMessage = contactRepository::sendContactMessage,
                        onUploadContactImage = contactRepository::uploadContactImage,
                        unreadContactConversationIds = unreadContactConversationIds,
                        announcements = announcements,
                        announcementsLoading = announcementLoading,
                        announcementsError = announcementError,
                        announcementsActionError = announcementActionError,
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
                            val announcementIds = announcementIdsForMarkAll(announcements)
                            if (announcementIds.isNotEmpty() && !isMarkingAllAnnouncementsRead) {
                                scope.launch {
                                    isMarkingAllAnnouncementsRead = true
                                    try {
                                        val readAt =
                                            announcementRepository.markAllAsRead(announcementIds)
                                        announcements = announcements.map { item ->
                                            if (item.id in announcementIds) {
                                                item.copy(readAt = readAt)
                                            } else {
                                                item
                                            }
                                        }
                                        announcementUnread =
                                            announcements.any { !it.isRead }
                                        announcementError = null
                                        announcementActionError = null
                                    } catch (exception: CancellationException) {
                                        throw exception
                                    } catch (exception: Exception) {
                                        announcementActionError =
                                            "Could not mark announcements as read."
                                    } finally {
                                        isMarkingAllAnnouncementsRead = false
                                    }
                                }
                            }
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
                            contactRefreshKey++
                        },
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
            if (screen != AppScreen.Landing && screen != AppScreen.Opening) {
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
            onDismiss = onDismissPublishPicker,
            onProceed = {
                onPublishImagesSelected(lesson, emptyList(), emptySet())
            }
        )
    }

    StoreActionDialog(
        title = storeDialog,
        message = "Connect to the internet to continue.",
        onDismiss = { storeDialog = null }
    )
    supportNotificationError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { supportNotificationError = null },
            title = { androidx.compose.material3.Text("Contact Us") },
            text = { androidx.compose.material3.Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = { supportNotificationError = null }
                ) { androidx.compose.material3.Text("OK") }
            }
        )
    }
    foregroundSupportAlert?.let { alert ->
        SupportForegroundAlertDialog(
            alert = alert,
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
            title = { androidx.compose.material3.Text(authErrorTitle) },
            text = { androidx.compose.material3.Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onDismissAuthError) {
                    androidx.compose.material3.Text("OK")
                }
            }
        )
    }

    BackHandler(enabled = publishPickerLesson == null &&
        !drawerOpen &&
        screen !is AppScreen.Landing &&
        screen !is AppScreen.ChooseLanguage &&
        screen !is AppScreen.Opening
    ) {
        when (screen) {
            AppScreen.SignInInvitation -> onScreenChange(AppScreen.ChooseLanguage)
            AppScreen.MyKolams -> handleScreenBack()
            AppScreen.ContactUs -> handleScreenBack()
            else -> onGoBack()
        }
    }
}

@Composable
private fun AppScreenContent(
    screen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    activeLanguage: String,
    catalogue: LessonCatalogueUiState,
    unlockedCatalogueLessonIds: Set<String>,
    account: SupabaseAccount?,
    isAuthProcessing: Boolean,
    onNavigate: (AppScreen) -> Unit,
    onLessonUnlocked: (String) -> Unit,
    onGoBack: () -> Unit,
    onHome: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onContinueAsGuest: () -> Unit,
    onSignOut: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onLanguageBack: () -> Unit,
    onLoadLesson: (LessonCatalogueEntry) -> Unit,
    onLoadCommunityLesson: (LessonCatalogueEntry) -> Unit,
    communityLessonOpenError: String?,
    onDismissCommunityLessonOpenError: () -> Unit,
    onCommunityLessonMissing: () -> Unit,
    onPublishLesson: (PublishLessonDetails) -> Unit,
    onPublishSuccess: () -> Unit,
    communityLessonRequestId: String?,
    onCommunityLessonRequest: (String) -> Unit,
    onCommunityLessonRequestHandled: () -> Unit,
    contactForegroundRefreshKey: Int,
    supportMessageVersion: Long,
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
    onMenuAction: (DrawerAction) -> Unit
) {
    when (screen) {
        AppScreen.ChooseLanguage -> LanguageSelectionScreen(onLanguageSelected, onLanguageBack)
        AppScreen.SignInInvitation -> SignInInvitationScreen(
            onGoogleSignIn = onGoogleSignIn,
            onSkip = onContinueAsGuest,
            isLoading = isAuthProcessing
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
            unlockedLessonIds = unlockedCatalogueLessonIds,
            accountId = account?.takeUnless { it.isGuest }?.id,
            onGoogleSignIn = onGoogleSignIn,
            communityLessonId = communityLessonRequestId,
            onCommunityLessonRequestHandled = onCommunityLessonRequestHandled,
            onCommunityLessonMissing = {
                onCommunityLessonRequestHandled()
                onCommunityLessonMissing()
            },
            onCommunityLessonSelected = { lesson ->
                if (lesson.access == CatalogueAccess.LOCKED) {
                    onLessonUnlocked(lesson.id)
                }
                onLoadCommunityLesson(lesson)
            },
            onLessonSelected = { lesson ->
                if (lesson.access == CatalogueAccess.LOCKED) {
                    onLessonUnlocked(lesson.id)
                }
                onLoadLesson(lesson)
            }
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
            onSelect = onLanguageChange,
            onBack = onGoBack
        )
        AppScreen.ContactUs -> {
            if (isContactSignedIn && selectedContactConversationId != null) {
                ContactConversationDetailDestination(
                    conversationId = selectedContactConversationId,
                    onLoadConversation = onLoadContactConversation,
                    onLoadMessages = onLoadContactMessages,
                    onSendMessage = onSendContactMessage,
                    onUploadImage = onUploadContactImage,
                    foregroundRefreshKey = contactForegroundRefreshKey,
                    externalRefreshKey = contactDetailRefreshKey
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
        AppScreen.History -> PlaceholderDestination("Kolam History", onGoBack)
        AppScreen.Loading -> androidx.compose.material3.Text(
            text = "Loading lesson...",
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.padding(24.dp)
        )
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
            onBack = onGoBack,
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
                    NetworkErrors.TITLE,
                    color = Color.Black,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                )
                androidx.compose.material3.Text(NetworkErrors.MESSAGE, color = Color.Black)
            } else {
                androidx.compose.material3.Text(
                    "Could not load lesson: ${screen.message}",
                    color = Color.White
                )
            }
            androidx.compose.material3.Button(onClick = onGoBack) {
                androidx.compose.material3.Text("Back")
            }
        }
    }
}

private val supportedLanguageNames = setOf("English", "Tamil", "Hindi", "Telugu", "Kannada", "Malayalam")
