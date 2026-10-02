package com.example.kolammasterkotlin

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
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
import com.example.kolammasterkotlin.ui.theme.KolamMasterKotlinTheme
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
    data class SikkuReady(val lesson: SikkuLesson) : AppScreen
    data class RangoliReady(val lesson: RangoliLesson) : AppScreen
    data class Failed(val message: String) : AppScreen
}

class MainActivity : ComponentActivity() {
    private var screenState by mutableStateOf<AppScreen>(AppScreen.ChooseLanguage)
    private var activeLanguage by mutableStateOf("English")
    private val routeHistory = mutableListOf<AppScreen>()

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

        setContent {
            KolamMasterKotlinTheme {
                KolamMasterApp(
                    screen = screenState,
                    onScreenChange = { screenState = it },
                    activeLanguage = activeLanguage,
                    onNavigate = ::navigateTo,
                    onGoBack = ::goBack,
                    onHome = { setRoot(AppScreen.Landing) },
                    onLanguageChange = { language ->
                        saveLanguage(language)
                        goBack()
                    },
                    onLanguageSelected = { language ->
                        saveLanguage(language)
                        routeHistory.clear()
                        setRoot(AppScreen.SignInInvitation)
                    },
                    onLoadLesson = ::loadLesson
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
    onNavigate: (AppScreen) -> Unit,
    onGoBack: () -> Unit,
    onHome: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onLoadLesson: (Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var storeDialog by remember { mutableStateOf<String?>(null) }
    val rootAlpha = remember { androidx.compose.animation.core.Animatable(1f) }
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
        androidx.compose.foundation.layout.Box(
            Modifier
                .fillMaxSize()
                .padding(insets)
                .graphicsLayer { alpha = rootAlpha.value }
        ) {
            AppScreenContent(
                screen = screen,
                onScreenChange = onScreenChange,
                activeLanguage = activeLanguage,
                onNavigate = onNavigate,
                onGoBack = onGoBack,
                onHome = onHome,
                onLanguageChange = onLanguageChange,
                onLanguageSelected = onLanguageSelected,
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
                onStoreAction = { actionTitle ->
                    openStoreFlow(context, { storeDialog = it }, actionTitle)
                }
            )
        }
    }

    StoreActionDialog(
        title = storeDialog,
        message = "Connect to the internet to continue.",
        onDismiss = { storeDialog = null }
    )

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
    onNavigate: (AppScreen) -> Unit,
    onGoBack: () -> Unit,
    onHome: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onLanguageSelected: (String) -> Unit,
    onLoadLesson: (Boolean) -> Unit,
    onBegin: () -> Unit,
    onStoreAction: (String) -> Unit
) {
    when (screen) {
        AppScreen.ChooseLanguage -> LanguageSelectionScreen(onLanguageSelected)
        AppScreen.SignInInvitation -> SignInInvitationScreen(
            onSkip = { onScreenChange(AppScreen.Opening) }
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
            onMenuAction = { action ->
                when (action) {
                    DrawerAction.Home -> onHome()
                    DrawerAction.Profile -> onNavigate(AppScreen.Profile)
                    DrawerAction.MyFolders -> onNavigate(AppScreen.MyKolams)
                    DrawerAction.Language -> onNavigate(AppScreen.Language)
                    DrawerAction.ContactUs -> onNavigate(AppScreen.ContactUs)
                    DrawerAction.Announcements -> onNavigate(AppScreen.Announcements)
                    DrawerAction.UpdateApp -> onStoreAction("Update App")
                    DrawerAction.RateApp -> onStoreAction("Rate App")
                }
            }
        )
        AppScreen.Browse -> LessonBrowserScreen(
            onBack = onGoBack,
            onSelectLesson = onLoadLesson
        )
        AppScreen.MyKolams -> MyFoldersDestination(
            onSignIn = {},
            onCancel = onGoBack,
            signedIn = false
        )
        AppScreen.Profile -> ProfileDestination(
            onSignIn = {},
            onBack = onGoBack
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
