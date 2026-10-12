package com.kolammaster.app

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random
import com.kolammaster.app.ui.theme.KolamSolarAmber as ThemeKolamSolarAmber
import com.kolammaster.app.ui.theme.KolamButtonText as ThemeKolamButtonText
import com.kolammaster.app.ui.theme.KolamButtonYellow as ThemeKolamButtonYellow
import com.kolammaster.app.ui.theme.KolamContainerSecondary as ThemeKolamContainerSecondary
import com.kolammaster.app.ui.theme.KolamCoolSilver as ThemeKolamCoolSilver
import com.kolammaster.app.ui.theme.KolamDisabled as ThemeKolamDisabled
import com.kolammaster.app.ui.theme.KolamElevated as ThemeKolamElevated
import com.kolammaster.app.ui.theme.KolamError as ThemeKolamError
import com.kolammaster.app.ui.theme.KolamMidnight as ThemeKolamMidnight
import com.kolammaster.app.ui.theme.KolamMutedNavy as ThemeKolamMutedNavy
import com.kolammaster.app.ui.theme.KolamNavy as ThemeKolamNavy
import com.kolammaster.app.ui.theme.KolamSilver as ThemeKolamSilver
import com.kolammaster.app.ui.theme.KolamSilverBorder as ThemeKolamSilverBorder
import com.kolammaster.app.ui.theme.KolamWhite as ThemeKolamWhite

internal val KolamBackground = ThemeKolamMidnight
internal val KolamSurface = ThemeKolamNavy
internal val KolamNavy = ThemeKolamNavy
internal val KolamElevated = ThemeKolamElevated
internal val KolamText = ThemeKolamWhite
internal val KolamMutedText = ThemeKolamSilver
internal val KolamAccent = ThemeKolamSolarAmber
internal val KolamButtonYellow = ThemeKolamButtonYellow
internal val KolamButtonText = ThemeKolamButtonText
internal val KolamGold = KolamAccent
internal val KolamBronze = KolamAccent
internal val KolamBorder = ThemeKolamMutedNavy
internal val KolamSubtleSurface = ThemeKolamElevated
internal val KolamDisabled = ThemeKolamDisabled
internal val KolamError = ThemeKolamError
internal val KolamCoolSilver = ThemeKolamCoolSilver
internal val KolamContainerSecondary = ThemeKolamContainerSecondary
internal val KolamSilverBorder = ThemeKolamSilverBorder
private val Gold = KolamButtonYellow
private val KolamMasterHeaderColor = KolamAccent
private val HeaderTitleStyle = TextStyle(
    shadow = Shadow(
        color = KolamBackground.copy(alpha = 0.8f),
        offset = Offset(0f, 1.dp.value),
        blurRadius = 1.5f
    )
)

internal enum class LandingDestination(val title: String) {
    Browse("Learn Kolam"),
    MyKolams("My Folders"),
    Community("Community"),
    History("Kolam History")
}

internal val supportedLanguages = listOf(
    "English" to "English",
    "Tamil" to "தமிழ்",
    "Hindi" to "हिन्दी",
    "Telugu" to "తెలుగు",
    "Kannada" to "ಕನ್ನಡ",
    "Malayalam" to "മലയാളം"
)

@Composable
internal fun LanguageSelectionScreen(
    onSelected: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            bitmap = rememberAssetImage("kolam-logo.png"),
            contentDescription = "Kolam logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(LanguageLogoSize)
                .offset(y = LanguageLogoVerticalOffset)
        )
        Spacer(Modifier.height(LanguageLogoToSectionSpacing))
        Column(
            modifier = Modifier.offset(y = LanguageSectionVerticalOffset),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Choose Your Language",
                color = KolamGold,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "You can change this later from My Folders.",
                color = KolamMutedText,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            supportedLanguages.forEach { (languageId, displayName) ->
                Button(
                    onClick = { onSelected(languageId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gold)
                ) {
                    Text(displayName, color = KolamButtonText, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Gold)
            ) { Text("Back", color = KolamButtonText, fontSize = 16.sp) }
        }
    }
}

@Composable
internal fun SignInInvitationScreen(
    onGoogleSignIn: () -> Unit,
    onSkip: () -> Unit,
    isLoading: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            bitmap = rememberAssetImage("kolam-logo.png"),
            contentDescription = "Kolam logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(SignInLogoSize)
                .offset(y = SignInLogoVerticalOffset)
        )
        Spacer(Modifier.height(SignInLogoToContentSpacing))
        Column(
            modifier = Modifier.offset(y = SignInContentVerticalOffset),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Sign in with Google",
                color = KolamGold,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(SignInTitleToBenefitsSpacing))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = KolamSurface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    KolamBorder
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "Save your kolams",
                        "Keep your creations organized",
                        "Join the Kolam community",
                        "Publish your kolams to the Kolam community"
                    ).forEach { benefit ->
                        Text(
                            text = "•  $benefit",
                            modifier = Modifier.fillMaxWidth(),
                            color = KolamText,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
            Spacer(Modifier.height(SignInBenefitsToGoogleSpacing))
            Button(
                onClick = onGoogleSignIn,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    disabledContainerColor = Gold,
                    disabledContentColor = KolamButtonText
                )
            ) {
                Text(
                    "Continue with Google",
                    color = KolamButtonText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(SignInGoogleToSkipSpacing))
            Button(
                onClick = onSkip,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Gold,
                    disabledContainerColor = Gold,
                    disabledContentColor = KolamButtonText
                )
            ) {
                Text(
                    "Skip for now",
                    color = KolamButtonText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
internal fun InitialAssetSetupScreen(
    progress: AssetSetupProgress,
    isRunning: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Preparing Kolam Master",
            color = KolamGold,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Downloading images for your first visit. Please wait.",
            color = KolamMutedText,
            fontSize = 16.sp,
            lineHeight = 23.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(28.dp))
        if (isRunning) {
            CircularProgressIndicator(color = Gold)
            Spacer(Modifier.height(16.dp))
            if (progress.total > 0) {
                Text(
                    text = "${progress.completed} of ${progress.total} images",
                    color = KolamText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = progress.message,
                color = KolamMutedText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        } else if (errorMessage != null) {
            Text(
                text = errorMessage,
                color = KolamText,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Gold)
            ) {
                Text("Retry", color = KolamButtonText, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private val LanguageLogoSize = 200.dp
private val LanguageLogoVerticalOffset = 0.dp
private val LanguageLogoToSectionSpacing = 12.dp
private val LanguageSectionVerticalOffset = 0.dp
private val SignInLogoSize = 310.dp
private val SignInLogoVerticalOffset = (-50).dp
private val SignInLogoToContentSpacing = 6.dp
private val SignInContentVerticalOffset = (-55).dp
private val SignInTitleToBenefitsSpacing = 16.dp
private val SignInBenefitsToGoogleSpacing = 16.dp
private val SignInGoogleToSkipSpacing = 8.dp

@Composable
internal fun AuthLoadingOverlay(message: String?) {
    if (message == null) return

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = KolamSurface,
            tonalElevation = 12.dp,
            shadowElevation = 20.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(30.dp),
                    color = Gold,
                    trackColor = KolamBorder,
                    strokeWidth = 3.dp
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    modifier = Modifier.weight(1f),
                    text = message,
                    color = KolamText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private val OpeningScreenLogoSize = 230.dp
private val OpeningScreenLogoVerticalOffset = 0.dp

@Composable
internal fun OpeningScreen(onBegin: () -> Unit) {
    val titleAlpha = remember { Animatable(0f) }
    val borderWidth = remember { Animatable(0f) }
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.04f) }
    val historyAlpha = remember { Animatable(0f) }
    val beginAlpha = remember { Animatable(0f) }
    val cubicOut = remember { CubicBezierEasing(0f, 0f, 0.58f, 1f) }
    val cubicInOut = remember { CubicBezierEasing(0.42f, 0f, 0.58f, 1f) }
    val logo = rememberAssetImage("kolam-logo.png")
    val border = rememberAssetImage("pre-landing-page-border.png")

    LaunchedEffect(Unit) {
        titleAlpha.animateTo(1f, tween(1050, easing = cubicOut))
        borderWidth.animateTo(300f, tween(1450, easing = cubicInOut))
        coroutineScope {
            launch { logoAlpha.animateTo(1f, tween(180, easing = LinearEasing)) }
            launch { logoScale.animateTo(1.75f, tween(1050, easing = cubicOut)) }
        }
        historyAlpha.animateTo(1f, tween(920, easing = cubicInOut))
        beginAlpha.animateTo(1f, tween(940, easing = cubicInOut))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(start = 28.dp, end = 28.dp, top = 58.dp, bottom = 52.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Kolam Master",
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = titleAlpha.value },
            color = KolamText,
            fontSize = 34.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            RevealingBorder(borderWidth.value, border)
            Box(
                modifier = Modifier.size(300.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = logo,
                    contentDescription = "Kolam Master logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(OpeningScreenLogoSize)
                        .offset(y = OpeningScreenLogoVerticalOffset)
                        .graphicsLayer {
                            alpha = logoAlpha.value
                            scaleX = logoScale.value
                            scaleY = logoScale.value
                        }
                )
            }
            RevealingBorder(borderWidth.value, border)
        }

        Text(
            text = "Kolam dates back to 500 BCE, mentioned in Sangam literature.",
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .graphicsLayer { alpha = historyAlpha.value },
            color = KolamText,
            fontSize = 20.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Button(
            onClick = onBegin,
            modifier = Modifier
                .defaultMinSize(minWidth = 166.dp, minHeight = 50.dp)
                .graphicsLayer { alpha = beginAlpha.value },
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 24.dp,
                vertical = 6.dp
            ),
            colors = ButtonDefaults.buttonColors(
                containerColor = Gold,
                disabledContainerColor = Gold,
                disabledContentColor = KolamText
            )
        ) {
            Text(
                text = "Begin",
                color = KolamText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RevealingBorder(width: Float, image: androidx.compose.ui.graphics.ImageBitmap) {
    Box(
        modifier = Modifier
            .width(width.dp)
            .height(48.dp)
            .clipToBounds()
    ) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.requiredSize(300.dp, 48.dp)
        )
    }
}

@Composable
internal fun LandingScreen(
    onDestination: (LandingDestination) -> Unit,
    onMenuAction: (DrawerAction) -> Unit,
    unreadSupport: Boolean = false,
    unreadAnnouncements: Boolean = false
) {
    LandingPageFrame(
        onMenuAction = onMenuAction,
        unreadSupport = unreadSupport,
        unreadAnnouncements = unreadAnnouncements
    ) {
        Spacer(Modifier.height(18.dp))
        LandingBubbles(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 8.dp, end = 8.dp, bottom = 20.dp),
            onDestination = onDestination
        )
    }
}

@Composable
internal fun LandingPageFrame(
    onMenuAction: (DrawerAction) -> Unit,
    unreadSupport: Boolean = false,
    unreadAnnouncements: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var drawerOpen by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerOpen) { drawerOpen = false }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
                LandingHeader(unreadSupport || unreadAnnouncements) { drawerOpen = true }
            Box(modifier = Modifier.fillMaxSize()) {
                LandingDecoration(
                    Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    mirrored = false,
                    pictureCount = 6
                )
                LandingDecoration(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    mirrored = true,
                    pictureCount = 6
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        bitmap = rememberAssetImage("landing-page-image.png"),
                        contentDescription = "Kolam Master",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .size(width = 300.dp, height = 50.dp)
                    )
                    content()
                }
            }
        }
        DrawerOverlay(
            visible = drawerOpen,
            settingsExpanded = settingsExpanded,
            onSettingsExpandedChange = { settingsExpanded = it },
            onBack = { drawerOpen = false },
            onSelect = { action ->
                scope.launch {
                    drawerOpen = false
                    delay(260)
                    onMenuAction(action)
                }
            },
            unreadContactUs = unreadSupport,
            unreadAnnouncements = unreadAnnouncements
        )
    }
}

@Composable
internal fun LandingArtworkFrame(
    showBrandArtwork: Boolean = true,
    showDecorations: Boolean = true,
    horizontalPadding: Dp = 58.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
    ) {
        if (showDecorations) {
            LandingDecoration(
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                mirrored = false
            )
            LandingDecoration(
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                mirrored = true
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (showBrandArtwork) {
                Image(
                    bitmap = rememberAssetImage("landing-page-image.png"),
                    contentDescription = "Kolam Master",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .fillMaxWidth()
                        .widthIn(max = 300.dp)
                        .height(50.dp)
                )
            }
            content()
        }
    }
}

@Composable
internal fun AppNavigationHeader(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onOpenDrawer: () -> Unit,
    showNavigationControls: Boolean = true,
    unreadSupport: Boolean = false,
    unreadAnnouncements: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(KolamMasterHeaderColor)
    ) {
        if (showNavigationControls) {
            Text(
                text = "←",
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(width = 56.dp, height = 64.dp)
                    .clickable(onClick = onBack)
                    .wrapContentSize(Alignment.Center),
                color = KolamText,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                style = HeaderTitleStyle
            )
        }
        Text(
            text = "Kolam Master",
            modifier = Modifier
                .align(Alignment.Center)
                .clickable(onClick = onHome),
            color = KolamText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            style = HeaderTitleStyle
        )
        if (showNavigationControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(width = 56.dp, height = 64.dp)
                    .semantics { contentDescription = "Open navigation menu" }
                    .clickable(onClick = onOpenDrawer),
                contentAlignment = Alignment.Center
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .size(width = 24.dp, height = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .offset(x = 1.dp, y = 1.dp)
                                    .size(width = 22.dp, height = 2.dp)
                                    .background(
                                        KolamBackground.copy(alpha = 0.7f),
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                            Box(
                                Modifier
                                    .offset(x = (-1).dp, y = (-1).dp)
                                    .size(width = 22.dp, height = 2.dp)
                                    .background(KolamText, RoundedCornerShape(2.dp))
                            )
                        }
                    }
                }
                if (unreadSupport || unreadAnnouncements) {
                    SupportUnreadIndicator(
                        Modifier
                            .align(Alignment.Center)
                            .offset(x = 13.dp, y = (-8).dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LandingHeader(unreadSupport: Boolean, onOpenDrawer: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(KolamMasterHeaderColor)
    ) {
        Text(
            text = "Kolam Master",
            modifier = Modifier.align(Alignment.Center),
            color = KolamText,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            style = HeaderTitleStyle
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(width = 56.dp, height = 64.dp)
                .semantics { contentDescription = "Open navigation menu" }
                .clickable(onClick = onOpenDrawer),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .size(width = 24.dp, height = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            Modifier
                                .offset(x = 1.dp, y = 1.dp)
                                .size(width = 22.dp, height = 2.dp)
                                .background(
                                    KolamBackground.copy(alpha = 0.7f),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Box(
                            Modifier
                                .offset(x = (-1).dp, y = (-1).dp)
                                .size(width = 22.dp, height = 2.dp)
                                .background(KolamText, RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
            if (unreadSupport) {
                SupportUnreadIndicator(
                    Modifier
                        .align(Alignment.Center)
                        .offset(x = 13.dp, y = (-8).dp)
                )
            }
        }
    }
}

@Composable
private fun LandingDecoration(
    modifier: Modifier,
    mirrored: Boolean,
    pictureCount: Int = 4
) {
    val image = rememberAssetImage("kolam-border.png")
    if (pictureCount == 6) {
        BoxWithConstraints(modifier = modifier.width(48.dp).fillMaxHeight()) {
            val oldTopPosition =
                24.dp + (maxHeight - 48.dp - 460.dp) / 6
            val bubbleBottomPosition = 329.dp + maxHeight / 2
            val bottomInset = (maxHeight - bubbleBottomPosition).coerceAtLeast(0.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = oldTopPosition, bottom = bottomInset),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                repeat(pictureCount) {
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(width = 38.dp, height = 92.dp)
                            .graphicsLayer { scaleX = if (mirrored) -1f else 1f }
                    )
                }
            }
        }
    } else {
        Column(
            modifier = modifier
                .width(48.dp)
                .fillMaxHeight()
                .padding(vertical = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly
        ) {
            repeat(pictureCount) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(width = 38.dp, height = 92.dp)
                        .graphicsLayer { scaleX = if (mirrored) -1f else 1f }
                )
            }
        }
    }
}

@Composable
private fun LandingBubbles(
    modifier: Modifier,
    onDestination: (LandingDestination) -> Unit
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        LandingDestination.entries.forEachIndexed { index, destination ->
            LandingBubble(
                title = destination.title,
                index = index,
                modifier = Modifier
                    .padding(horizontal = 8.dp),
                onClick = { onDestination(destination) }
            )
        }
    }
}

@Composable
private fun LandingBubble(
    title: String,
    index: Int,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val horizontalOffset = remember { Animatable(0f) }
    val verticalOffset = remember { Animatable(0f) }
    val easing = remember { CubicBezierEasing(0.45f, 0.05f, 0.55f, 0.95f) }
    LaunchedEffect(index) {
        val horizontalRandom = Random(index * 37 + 11)
        val verticalRandom = Random(index * 53 + 29)
        coroutineScope {
            launch {
                while (true) {
                    horizontalOffset.animateTo(
                        targetValue = horizontalRandom.nextFloat() * 5f - 2.5f,
                        animationSpec = tween(
                            durationMillis = horizontalRandom.nextInt(1800, 3400),
                            easing = easing
                        )
                    )
                }
            }
            launch {
                while (true) {
                    delay(verticalRandom.nextLong(120L, 650L))
                    verticalOffset.animateTo(
                        targetValue = verticalRandom.nextFloat() * 6f - 3f,
                        animationSpec = tween(
                            durationMillis = verticalRandom.nextInt(1900, 3600),
                            easing = easing
                        )
                    )
                }
            }
        }
    }
    val shape = RoundedCornerShape(50)
    val edgeTint = Brush.linearGradient(
        colors = listOf(
            KolamAccent,
            KolamAccent.copy(alpha = 0.82f),
            KolamAccent,
            KolamAccent.copy(alpha = 0.68f),
            KolamAccent
        )
    )
    Box(
        modifier = modifier
            .offset(x = horizontalOffset.value.dp, y = verticalOffset.value.dp)
            .size(width = 150.dp, height = 136.dp)
            .clip(shape)
            .background(KolamSurface)
            .border(1.dp, edgeTint, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = KolamText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun LessonBrowserScreen(
    onBack: () -> Unit,
    onSelectLesson: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Learn Kolam",
            color = KolamText,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))
        GoldActionButton("Sikku", onClick = { onSelectLesson(false) })
        Spacer(Modifier.height(12.dp))
        GoldActionButton("Rangoli", onClick = { onSelectLesson(true) })
        Spacer(Modifier.height(24.dp))
        GoldActionButton("Back", onClick = onBack)
    }
}

@Composable
internal fun PlaceholderDestination(title: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = KolamText, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        GoldActionButton("Back to Kolam Master", onClick = onBack)
    }
}

@Composable
private fun GoldActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minWidth = 166.dp, minHeight = 50.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Gold)
    ) {
        Text(text, color = KolamButtonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun rememberAssetImage(name: String): androidx.compose.ui.graphics.ImageBitmap {
    val context = LocalContext.current
    return remember(context, name) {
        val bitmap = context.assets.open(name).use(BitmapFactory::decodeStream)
            ?: error("Could not decode app asset: $name")
        bitmap.asImageBitmap()
    }
}
