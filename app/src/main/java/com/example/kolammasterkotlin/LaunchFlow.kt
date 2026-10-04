package com.kolammaster.app

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal val KolamBackground = Color(0xFF2F343A)
private val Gold = Color(0xFF8F6F38)
private val KolamMasterHeaderColor = Color(0xFF496B6B)

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
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "You can change this later from My Folders.",
                color = Color.White.copy(alpha = 0.82f),
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
                    Text(displayName, color = Color.White, fontSize = 18.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = Gold)
            ) { Text("Back", color = Color.White, fontSize = 16.sp) }
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
        Text(
            text = "Sign in with Google",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(SignInTitleToBenefitsSpacing))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = SignInBenefitsVerticalOffset),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.08f),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Color.White.copy(alpha = 0.18f)
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
                        color = Color.White,
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
            modifier = Modifier.offset(y = SignInGoogleButtonVerticalOffset),
            colors = ButtonDefaults.buttonColors(
                containerColor = Gold,
                disabledContainerColor = Gold,
                disabledContentColor = Color.White
            )
        ) {
            Text(
                "Continue with Google",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(SignInGoogleToSkipSpacing))
        Button(
            onClick = onSkip,
            enabled = !isLoading,
            modifier = Modifier.offset(y = SignInSkipButtonVerticalOffset),
            colors = ButtonDefaults.buttonColors(
                containerColor = Gold,
                disabledContainerColor = Gold,
                disabledContentColor = Color.White
            )
        ) {
            Text("Skip for now", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private val LanguageLogoSize = 200.dp
private val LanguageLogoVerticalOffset = 0.dp
private val LanguageLogoToSectionSpacing = 12.dp
private val LanguageSectionVerticalOffset = 0.dp
private val SignInLogoSize = 310.dp
private val SignInLogoVerticalOffset = 0.dp
private val SignInLogoToContentSpacing = 12.dp
private val SignInTitleToBenefitsSpacing = 16.dp
private val SignInBenefitsVerticalOffset = 0.dp
private val SignInBenefitsToGoogleSpacing = 16.dp
private val SignInGoogleToSkipSpacing = 8.dp
private val SignInGoogleButtonVerticalOffset = 0.dp
private val SignInSkipButtonVerticalOffset = 0.dp

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
            color = Color(0xFF34393F),
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
                    trackColor = Color.White.copy(alpha = 0.16f),
                    strokeWidth = 3.dp
                )
                Spacer(Modifier.width(18.dp))
                Text(
                    modifier = Modifier.weight(1f),
                    text = message,
                    color = Color.White,
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
            color = Color.White,
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
            color = Color.White,
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
                disabledContentColor = Color.White
            )
        ) {
            Text(
                text = "Begin",
                color = Color.White,
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
    onMenuAction: (DrawerAction) -> Unit
) {
    LandingPageFrame(onMenuAction = onMenuAction) {
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
            LandingHeader { drawerOpen = true }
            Box(modifier = Modifier.fillMaxSize()) {
                LandingDecoration(
                    Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                    mirrored = false
                )
                LandingDecoration(
                    Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                    mirrored = true
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
            }
        )
    }
}

@Composable
internal fun LandingArtworkFrame(
    showBrandArtwork: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
    ) {
        LandingDecoration(
            Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
            mirrored = false
        )
        LandingDecoration(
            Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            mirrored = true
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 58.dp),
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
    showNavigationControls: Boolean = true
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
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = "Kolam Master",
            modifier = Modifier
                .align(Alignment.Center)
                .clickable(onClick = onHome),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        if (showNavigationControls) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(width = 56.dp, height = 64.dp)
                    .clickable(onClick = onOpenDrawer),
                contentAlignment = Alignment.Center
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .width(22.dp)
                                .height(2.dp)
                                .background(Color.White)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LandingHeader(onOpenDrawer: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(KolamMasterHeaderColor)
    ) {
        Text(
            text = "Kolam Master",
            modifier = Modifier.align(Alignment.Center),
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(width = 56.dp, height = 64.dp)
                .clickable(onClick = onOpenDrawer),
            contentAlignment = Alignment.Center
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) {
                    Box(
                        Modifier
                            .width(22.dp)
                            .height(2.dp)
                            .background(Color.White)
                    )
                }
            }
        }
    }
}

@Composable
private fun LandingDecoration(modifier: Modifier, mirrored: Boolean) {
    val image = rememberAssetImage("kolam-border.png")
    Column(
        modifier = modifier
            .width(48.dp)
            .fillMaxHeight()
            .padding(vertical = 34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        repeat(4) {
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

@Composable
private fun LandingBubbles(
    modifier: Modifier,
    onDestination: (LandingDestination) -> Unit
) {
    val transition = rememberInfiniteTransition(label = "landing-bubbles")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                3200,
                easing = Easing { fraction ->
                    (1f - cos(PI * fraction).toFloat()) / 2f
                }
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shared-bubble-phase"
    )
    val density = LocalDensity.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        LandingDestination.entries.forEachIndexed { index, destination ->
            val phase = progress * 2f * PI.toFloat() + index * (PI.toFloat() / 2f)
            val horizontalAmplitude = 2.7f + 0.45f * sin(index * 1.7f)
            val verticalAmplitude = 4.275f + 0.675f * sin(index * 1.3f)
            val x = cos(phase) * horizontalAmplitude
            val y = sin(phase) * verticalAmplitude
            LandingBubble(
                title = destination.title,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .offset {
                        IntOffset(
                            with(density) { x.dp.roundToPx() },
                            with(density) { y.dp.roundToPx() }
                        )
                    }
                    .graphicsLayer { scaleX = 1f + 0.012f * progress; scaleY = 1f + 0.012f * progress },
                onClick = { onDestination(destination) }
            )
        }
    }
}

@Composable
private fun LandingBubble(title: String, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(64.dp)
    Box(
        modifier = modifier
            .size(width = 145.dp, height = 128.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Color.White,
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
            color = Color.White,
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
        Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
        Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
