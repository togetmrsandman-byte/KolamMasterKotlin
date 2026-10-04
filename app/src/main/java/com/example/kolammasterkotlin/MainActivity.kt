package com.kolammaster.app

import android.app.Activity
import android.content.pm.ActivityInfo
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
import com.kolammaster.app.ui.theme.KolamMasterKotlinTheme
import com.kolammaster.app.auth.SupabaseAccount
import com.kolammaster.app.auth.SupabaseGuestAuth
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

private const val PREFERENCES_NAME = "kolam_master_preferences"
private const val LANGUAGE_KEY = "language"

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
    data class CatalogueLesson(val lesson: LessonCatalogueEntry) : AppScreen
    data class SikkuReady(val lesson: SikkuLesson) : AppScreen
    data class RangoliReady(val lesson: RangoliLesson) : AppScreen
    data class Failed(val message: String) : AppScreen
}

class MainActivity : ComponentActivity() {
    private var screenState by mutableStateOf<AppScreen>(AppScreen.ChooseLanguage)
    private var activeLanguage by mutableStateOf("English")
    private var catalogueState by mutableStateOf(LessonCatalogueUiState())
    private var accountState by mutableStateOf<SupabaseAccount?>(null)
    private var isAuthProcessing by mutableStateOf(false)
    private var authLoadingMessage by mutableStateOf<String?>(null)
    private var authError by mutableStateOf<String?>(null)
    private val routeHistory = mutableListOf<AppScreen>()
    private lateinit var googleSignInClient: com.google.android.gms.auth.api.signin.GoogleSignInClient

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                isAuthProcessing = false
                authLoadingMessage = null
                return@registerForActivityResult
            }
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                val idToken = account.idToken
                    ?: throw IllegalStateException("Google did not return an ID token.")
                finishGoogleSignIn(idToken)
            } catch (exception: ApiException) {
                showAuthError(exception)
            } catch (exception: IllegalStateException) {
                showAuthError(exception)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        screenState = if (savedLanguage == null) AppScreen.ChooseLanguage else AppScreen.Opening
        googleSignInClient = GoogleSignIn.getClient(
            this,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .requestEmail()
                .build()
        )

        lifecycleScope.launch {
            try {
                if (savedLanguage != null) {
                    SupabaseGuestAuth.getOrCreateGuestUserId()
                }
                accountState = SupabaseGuestAuth.currentAccount()
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
                    account = accountState,
                    isAuthProcessing = isAuthProcessing,
                    authLoadingMessage = authLoadingMessage,
                    authError = authError,
                    onNavigate = ::navigateTo,
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
                    onLoadLesson = ::loadLesson
                )
            }
        }

        refreshLessonCatalogue()
    }

    private fun beginGoogleSignIn() {
        if (isAuthProcessing) return
        isAuthProcessing = true
        authLoadingMessage = "Signing you in, please wait..."
        authError = null
        lifecycleScope.launch {
            try {
                val currentAccount = SupabaseGuestAuth.currentAccount()
                if (currentAccount != null && !currentAccount.isGuest) {
                    accountState = currentAccount
                    if (screenState == AppScreen.SignInInvitation) {
                        screenState = AppScreen.Opening
                    }
                    isAuthProcessing = false
                    authLoadingMessage = null
                    return@launch
                }
                SupabaseGuestAuth.getOrCreateGuestUserId()
                googleSignInLauncher.launch(googleSignInClient.signInIntent)
            } catch (exception: Exception) {
                showAuthError(exception)
            }
        }
    }

    private fun finishGoogleSignIn(idToken: String) {
        lifecycleScope.launch {
            try {
                accountState = SupabaseGuestAuth.mergeGuestWithGoogleIdToken(idToken)
                if (screenState == AppScreen.SignInInvitation) {
                    screenState = AppScreen.Opening
                }
            } catch (exception: Exception) {
                showAuthError(exception)
            } finally {
                isAuthProcessing = false
                authLoadingMessage = null
            }
        }
    }

    private fun continueAsGuest() {
        if (isAuthProcessing) return
        isAuthProcessing = true
        authError = null
        lifecycleScope.launch {
            try {
                val currentAccount = SupabaseGuestAuth.currentAccount()
                if (currentAccount == null || currentAccount.isGuest) {
                    SupabaseGuestAuth.getOrCreateGuestUserId()
                }
                accountState = SupabaseGuestAuth.currentAccount()
                screenState = AppScreen.Opening
            } catch (exception: Exception) {
                showAuthError(exception)
            } finally {
                isAuthProcessing = false
                authLoadingMessage = null
            }
        }
    }

    private fun signOutToGuest() {
        if (isAuthProcessing) return
        isAuthProcessing = true
        authLoadingMessage = "Signing you out, please wait..."
        authError = null
        lifecycleScope.launch {
            try {
                googleSignInClient.signOut()
                SupabaseGuestAuth.signOutAndCreateGuest()
                accountState = SupabaseGuestAuth.currentAccount()
            } catch (exception: Exception) {
                showAuthError(exception)
            } finally {
                isAuthProcessing = false
                authLoadingMessage = null
            }
        }
    }

    private fun showAuthError(exception: Exception) {
        authError = exception.message
            ?.takeIf { it.isNotBlank() }
            ?.let { "Authentication failed: $it" }
            ?: "Authentication failed. Check your connection and try again."
        isAuthProcessing = false
        authLoadingMessage = null
    }

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
                    refreshFailed = true
                )
            } catch (exception: JSONException) {
                Log.w("LessonCatalogue", "R2 returned invalid lesson catalogue JSON", exception)
                catalogueState = catalogueState.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    refreshFailed = true
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

    private fun loadLesson(rangoli: Boolean) {
        routeHistory += AppScreen.Browse
        screenState = AppScreen.Loading
        lifecycleScope.launch {
            try {
                val lesson = withContext(Dispatchers.IO) {
                    val assetName = if (rangoli) "Shivan.kmp" else "Sikku.kmp"
                    val destinationName = if (rangoli) "shivan-kmp" else "sikku-kmp"
                    val destination = File(cacheDir, destinationName)
                    val names = KmpLessonExtractor.extractAsset(
                        context = this@MainActivity,
                        assetName = assetName,
                        sharedSecret = KmpSecret.value(),
                        destination = destination
                    )
                    names.forEach { Log.i("KmpLesson", "Extracted $assetName file: $it") }
                    if (rangoli) {
                        AppScreen.RangoliReady(RangoliLesson.load(destination))
                    } else {
                        AppScreen.SikkuReady(SikkuLesson.load(destination))
                    }
                }
                screenState = lesson
            } catch (e: IOException) {
                Log.e("KmpLesson", "Could not read lesson assets", e)
                screenState = AppScreen.Failed(e.message ?: "Could not read lesson files")
            } catch (e: GeneralSecurityException) {
                Log.e("KmpLesson", "Could not decrypt lesson package", e)
                screenState = AppScreen.Failed(e.message ?: "Could not decrypt lesson")
            } catch (e: JSONException) {
                Log.e("KmpLesson", "Invalid lesson JSON data", e)
                screenState = AppScreen.Failed(e.message ?: "Invalid lesson data")
            } catch (e: IllegalArgumentException) {
                Log.e("KmpLesson", "Invalid lesson package", e)
                screenState = AppScreen.Failed(e.message ?: "Invalid lesson package")
            }
        }
    }
}

@Composable
private fun KolamMasterApp(
    screen: AppScreen,
    onScreenChange: (AppScreen) -> Unit,
    activeLanguage: String,
    catalogue: LessonCatalogueUiState,
    account: SupabaseAccount?,
    isAuthProcessing: Boolean,
    authLoadingMessage: String?,
    authError: String?,
    onNavigate: (AppScreen) -> Unit,
    onGoBack: () -> Unit,
    onHome: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onContinueAsGuest: () -> Unit,
    onSignOut: () -> Unit,
    onDismissAuthError: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onLanguageBack: () -> Unit,
    onLoadLesson: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var storeDialog by remember { mutableStateOf<String?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val rootAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
    val onMenuAction: (DrawerAction) -> Unit = { action ->
        when (action) {
            DrawerAction.Home -> onHome()
            DrawerAction.Profile -> onNavigate(AppScreen.Profile)
            DrawerAction.MyFolders -> onNavigate(AppScreen.MyKolams)
            DrawerAction.Language -> onNavigate(AppScreen.Language)
            DrawerAction.ContactUs -> onNavigate(AppScreen.ContactUs)
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
            AppScreen.History -> KolamBackground
            else -> MaterialTheme.colorScheme.surface
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = rootAlpha.value }
            ) {
                if (screen != AppScreen.Landing && screen != AppScreen.Opening) {
                    AppNavigationHeader(
                        onBack = onGoBack,
                        onHome = onHome,
                        onOpenDrawer = { drawerOpen = true },
                        showNavigationControls = screen != AppScreen.ChooseLanguage &&
                            screen != AppScreen.SignInInvitation
                    )
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    AppScreenContent(
                        screen = screen,
                        onScreenChange = onScreenChange,
                        activeLanguage = activeLanguage,
                        catalogue = catalogue,
                        account = account,
                        isAuthProcessing = isAuthProcessing,
                        onNavigate = onNavigate,
                        onGoBack = onGoBack,
                        onHome = onHome,
                        onGoogleSignIn = onGoogleSignIn,
                        onContinueAsGuest = onContinueAsGuest,
                        onSignOut = onSignOut,
                        onLanguageChange = onLanguageChange,
                        onLanguageSelected = onLanguageSelected,
                        onLanguageBack = onLanguageBack,
                        onLoadLesson = onLoadLesson,
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
                    }
                )
            }
        }
    }

    StoreActionDialog(
        title = storeDialog,
        message = "Connect to the internet to continue.",
        onDismiss = { storeDialog = null }
    )
    AuthLoadingOverlay(message = authLoadingMessage)
    authError?.let { message ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissAuthError,
            title = { androidx.compose.material3.Text("Sign-in problem") },
            text = { androidx.compose.material3.Text(message) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onDismissAuthError) {
                    androidx.compose.material3.Text("OK")
                }
            }
        )
    }

    BackHandler(enabled = screen !is AppScreen.Landing &&
        screen !is AppScreen.ChooseLanguage &&
        screen !is AppScreen.Opening
    ) {
        when (screen) {
            AppScreen.SignInInvitation -> onScreenChange(AppScreen.ChooseLanguage)
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
    account: SupabaseAccount?,
    isAuthProcessing: Boolean,
    onNavigate: (AppScreen) -> Unit,
    onGoBack: () -> Unit,
    onHome: () -> Unit,
    onGoogleSignIn: () -> Unit,
    onContinueAsGuest: () -> Unit,
    onSignOut: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onLanguageBack: () -> Unit,
    onLoadLesson: (Boolean) -> Unit,
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
            onMenuAction = onMenuAction
        )
        AppScreen.Browse -> BrowseLessonScreen(
            catalogue = catalogue,
            onLessonSelected = { lesson ->
                onNavigate(AppScreen.CatalogueLesson(lesson))
            }
        )
        is AppScreen.CatalogueLesson -> CatalogueLessonDetailScreen(
            lesson = screen.lesson,
            onBack = onGoBack
        )
        AppScreen.MyKolams -> MyFoldersDestination(
            onSignIn = {},
            onCancel = onGoBack,
            signedIn = false
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
        AppScreen.ContactUs -> ContactUsDestination(
            onSignIn = {},
            onBack = onGoBack,
            signedIn = false
        )
        AppScreen.Announcements -> AnnouncementsDestination(onGoBack)
        AppScreen.Community -> PlaceholderDestination("Community", onGoBack)
        AppScreen.History -> PlaceholderDestination("Kolam History", onGoBack)
        AppScreen.Loading -> androidx.compose.material3.Text(
            text = "Loading lesson...",
            color = androidx.compose.ui.graphics.Color.White,
            modifier = Modifier.padding(24.dp)
        )
        is AppScreen.SikkuReady -> SikkuLessonScreen(
            lesson = screen.lesson,
            modifier = Modifier.fillMaxSize()
        )
        is AppScreen.RangoliReady -> RangoliLessonScreen(
            lesson = screen.lesson,
            modifier = Modifier.fillMaxSize()
        )
        is AppScreen.Failed -> androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
        ) {
            androidx.compose.material3.Text(
                "Could not load lesson: ${screen.message}",
                color = Color.White
            )
            androidx.compose.material3.Button(onClick = onGoBack) {
                androidx.compose.material3.Text("Back")
            }
        }
    }
}

private val supportedLanguageNames = setOf("English", "Tamil", "Hindi", "Telugu", "Kannada", "Malayalam")
