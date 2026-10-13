package com.kolammaster.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import com.kolammaster.app.notifications.SupportUnreadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Instant

internal enum class DrawerAction {
    Home,
    Profile,
    MyFolders,
    Language,
    ContactUs,
    Announcements,
    UpdateApp,
    RateApp
}

private val drawerBackground = KolamSurface
private val drawerMuted = KolamMutedText
private val drawerSwitchOffTrack = KolamBorder
private val drawerSwitchOnThumb = KolamBronze
private val unreadColor = Color(0xFFFF6B6B)
private val FolderBenefitsHorizontalPadding = 14.dp
private val FolderBenefitsVerticalPadding = 14.dp
private val FolderBenefitSpacing = 8.dp
private val FolderSignedOutGroupVerticalOffset = (-120).dp
private val FolderSignedOutGoogleButtonVerticalOffset = 0.dp
private val FolderSignedOutBackButtonVerticalOffset = 0.dp
private val FolderSignedOutBenefitsToGoogleSpacing = 16.dp
private val FolderSignedOutGoogleToBackSpacing = 8.dp

@Composable
internal fun SupportUnreadIndicator(
    modifier: Modifier = Modifier,
    description: String = "Unread Support messages"
) {
    Box(
        modifier
            .size(8.dp)
            .semantics { contentDescription = description }
            .background(unreadColor, androidx.compose.foundation.shape.CircleShape)
    )
}

@Composable
internal fun DrawerOverlay(
    visible: Boolean,
    settingsExpanded: Boolean,
    onSettingsExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSelect: (DrawerAction) -> Unit,
    unreadContactUs: Boolean = false,
    unreadAnnouncements: Boolean = false
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
    ) {
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.34f))
                    .clickable(
                        interactionSource = remember {
                            androidx.compose.foundation.interaction.MutableInteractionSource()
                        },
                        indication = null,
                        onClick = {}
                    )
            )
        }
        val density = androidx.compose.ui.platform.LocalDensity.current
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.82f)
                .widthIn(max = 330.dp),
            enter = slideInHorizontally(
                animationSpec = tween(260),
                initialOffsetX = { density.run { 320.dp.roundToPx() } }
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(260),
                targetOffsetX = { density.run { 320.dp.roundToPx() } }
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(drawerBackground)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 42.dp, start = 22.dp, end = 22.dp)
            ) {
                Text(
                    text = stringResource(R.string.back),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable(onClick = onBack)
                        .padding(vertical = 8.dp),
                    color = KolamText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(24.dp))

                DrawerMenuRow(stringResource(R.string.home)) { onSelect(DrawerAction.Home) }
                DrawerMenuRow(stringResource(R.string.profile)) { onSelect(DrawerAction.Profile) }
                DrawerMenuRow(stringResource(R.string.my_folders)) { onSelect(DrawerAction.MyFolders) }
                DrawerMenuRow(stringResource(R.string.language_settings_label)) {
                    onSelect(DrawerAction.Language)
                }
                DrawerMenuRow(stringResource(R.string.contact_us), unread = unreadContactUs) {
                    onSelect(DrawerAction.ContactUs)
                }
                DrawerMenuRow(
                    stringResource(R.string.announcements),
                    unread = unreadAnnouncements,
                    unreadDescription = "Unread announcements"
                ) {
                    onSelect(DrawerAction.Announcements)
                }
                DrawerSettingsRow(settingsExpanded) {
                    onSettingsExpandedChange(!settingsExpanded)
                }
                if (settingsExpanded) {
                    SettingsNotifications()
                }
                DrawerMenuRow(stringResource(R.string.update_app)) {
                    onSelect(DrawerAction.UpdateApp)
                }
                DrawerMenuRow(stringResource(R.string.rate_app)) {
                    onSelect(DrawerAction.RateApp)
                }
            }
        }
    }
}

@Composable
private fun DrawerMenuRow(
    label: String,
    unread: Boolean = false,
    unreadDescription: String = "Unread Support messages",
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(
                    color = KolamBorder,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = KolamText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (unread) {
            SupportUnreadIndicator(description = unreadDescription)
        }
    }
}

@Composable
private fun DrawerSettingsRow(expanded: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(
                    color = KolamBorder,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.settings),
            modifier = Modifier.weight(1f),
            color = KolamText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        Chevron(up = expanded)
    }
}

@Composable
private fun Chevron(up: Boolean) {
    val color = drawerMuted.copy(alpha = 0.75f)
    Canvas(Modifier.size(20.dp)) {
        val path = Path().apply {
            if (up) {
                moveTo(size.width * 0.2f, size.height * 0.65f)
                lineTo(size.width * 0.5f, size.height * 0.35f)
                lineTo(size.width * 0.8f, size.height * 0.65f)
            } else {
                moveTo(size.width * 0.2f, size.height * 0.35f)
                lineTo(size.width * 0.5f, size.height * 0.65f)
                lineTo(size.width * 0.8f, size.height * 0.35f)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun SettingsNotifications() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var permissionGranted by remember { mutableStateOf(notificationsAllowed(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
        context.getSharedPreferences("kolam_master_preferences", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("notifications_permission_requested", true)
            .apply()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = notificationsAllowed(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.notifications),
            modifier = Modifier.padding(top = 14.dp, bottom = 8.dp),
            color = drawerMuted,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.contact_notifications),
                modifier = Modifier.weight(1f),
                color = KolamText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    if (permissionGranted) R.string.on else R.string.off
                ),
                modifier = Modifier.padding(end = 6.dp),
                color = KolamText,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Switch(
                checked = permissionGranted,
                onCheckedChange = { enabled ->
                    if (enabled && Build.VERSION.SDK_INT >= 33 && !permissionGranted) {
                        val preferences = context.getSharedPreferences(
                            "kolam_master_preferences",
                            android.content.Context.MODE_PRIVATE
                        )
                        if (preferences.getBoolean("notifications_permission_requested", false)) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APP_NOTIFICATION_SETTINGS
                                ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            )
                        } else {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    } else if (!enabled) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                },
                colors = SwitchDefaults.colors(
                    uncheckedTrackColor = drawerSwitchOffTrack,
                    checkedTrackColor = KolamBronze,
                    uncheckedThumbColor = drawerMuted,
                    checkedThumbColor = drawerSwitchOnThumb
                )
            )
        }
    }
}

private fun notificationsAllowed(context: android.content.Context): Boolean =
    (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED) &&
        NotificationManagerCompat.from(context).areNotificationsEnabled()

@Composable
internal fun LanguageSettingsScreen(
    activeLanguage: String,
    onSelect: (String) -> Unit
) {
    LandingArtworkFrame(
        decorationPictureCount = 6,
        evenlySpaceSixDecorations = true
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.choose_your_language),
            modifier = Modifier.offset(y = LanguageMenuSectionVerticalOffset),
            color = KolamText,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .offset(y = LanguageMenuLanguageOptionsVerticalOffset),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            supportedLanguages.forEach { (identifier, nativeName) ->
                Button(
                    onClick = { onSelect(identifier) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp
                    ),
                    colors = ButtonDefaults.buttonColors(containerColor = KolamButtonYellow)
                ) {
                    Text(
                        text = if (identifier == activeLanguage) "$nativeName  ✓" else nativeName,
                        color = KolamButtonText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.language_changes_apply),
            modifier = Modifier.offset(y = LanguageMenuImmediateTextVerticalOffset),
            color = KolamMutedText,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
    }
}

private val LanguageMenuSectionVerticalOffset = 0.dp
private val LanguageMenuLanguageOptionsVerticalOffset = 0.dp
private val LanguageMenuImmediateTextVerticalOffset = 0.dp

internal data class AccountProfile(
    val name: String,
    val email: String,
    val avatarUrl: String? = null
)

@Composable
internal fun ProfileDestination(
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    account: AccountProfile? = null,
    onSignOut: () -> Unit = {}
) {
    var avatarRetryKey by remember(account?.avatarUrl) { mutableIntStateOf(0) }
    var profilePhoto by remember(account?.avatarUrl) { mutableStateOf<ImageBitmap?>(null) }
    var showOfflineDialog by remember(account?.avatarUrl) { mutableStateOf(false) }
    LaunchedEffect(account?.avatarUrl, avatarRetryKey) {
        val avatarUrl = account?.avatarUrl?.takeIf(String::isNotBlank)
            ?: return@LaunchedEffect
        try {
            profilePhoto = withContext(Dispatchers.IO) {
                loadProfileThumbnail(avatarUrl)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (NetworkErrors.isNetworkFailure(exception)) showOfflineDialog = true
        }
    }
    LandingArtworkFrame {
        Spacer(Modifier.height(18.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.profile),
                color = KolamGold,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(if (account == null) 25.dp else 18.dp))
            if (account == null) {
                GuestSignInPrompt(
                    message = stringResource(R.string.guest_account_message),
                    onSignIn = onSignIn,
                    enabled = true
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = KolamButtonYellow)
                ) { Text(stringResource(R.string.back)) }
            } else {
                profilePhoto?.let { image ->
                    Image(
                        bitmap = image,
                        contentDescription = stringResource(R.string.profile_photo_description),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(account.name, color = KolamText, fontSize = 20.sp)
                Text(account.email, color = drawerMuted, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(
                        onClick = onBack,
                        colors = ButtonDefaults.buttonColors(containerColor = KolamButtonYellow)
                    ) { Text(stringResource(R.string.back)) }
                    Button(
                        onClick = onSignOut,
                        colors = ButtonDefaults.buttonColors(containerColor = KolamButtonYellow)
                    ) { Text(stringResource(R.string.sign_out)) }
                }
            }
            Image(
                bitmap = rememberAssetImage("kolam-logo.png"),
                contentDescription = stringResource(R.string.kolam_logo_description),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(top = ProfileLogoTopPadding)
                    .size(ProfileLogoSize)
                    .offset(y = ProfileLogoVerticalOffset)
            )
        }
        Spacer(Modifier.weight(1f))
    }
    if (showOfflineDialog) {
        NetworkRetryDialog(
            onRetry = {
                showOfflineDialog = false
                avatarRetryKey++
            },
            onClose = { showOfflineDialog = false }
        )
    }
}

private fun loadProfileThumbnail(url: String): ImageBitmap? {
    val connection = URL(url).openConnection() as? HttpsURLConnection
        ?: throw IOException("Profile image URL must use HTTPS.")
    return try {
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.use { input ->
            BitmapFactory.decodeStream(input)?.asImageBitmap()
        }
    } finally {
        connection.disconnect()
    }
}

private val ProfileLogoSize = 270.dp
private val ProfileLogoTopPadding = 18.dp
private val ProfileLogoVerticalOffset = 0.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactUsDestination(
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    signedIn: Boolean,
    conversations: List<ContactConversation>,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    onNewConversation: () -> Unit = {},
    onConversationSelected: (String) -> Unit = {},
    unreadConversationIds: Set<String> = emptySet()
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val contactUsTitle = stringResource(R.string.contact_us)
    val newConversationDescription =
        stringResource(R.string.new_conversation_description)
    var contactTitleFontSize by remember(contactUsTitle) {
        mutableFloatStateOf(24f)
    }
    var networkDialogDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(errorMessage) {
        if (errorMessage != NetworkErrors.DISPLAY_TEXT) networkDialogDismissed = false
    }
    val listState = rememberLazyListState()
    val orderedConversations =
        orderContactConversations(conversations, unreadConversationIds)
    val orderedConversationIds = orderedConversations.map(ContactConversation::id)
    var previousConversationOrder by remember {
        mutableStateOf<List<String>?>(null)
    }
    val nearTopThresholdPx = with(density) { 48.dp.roundToPx() }
    LaunchedEffect(orderedConversationIds) {
        val previousOrder = previousConversationOrder
        previousConversationOrder = orderedConversationIds
        if (shouldRevealContactConversationPromotion(previousOrder, orderedConversationIds) &&
            (listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > nearTopThresholdPx)
        ) {
            listState.animateScrollToItem(0)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                contactUsTitle,
                modifier = Modifier.weight(1f),
                color = KolamGold,
                fontSize = contactTitleFontSize.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                onTextLayout = { layout ->
                    if (layout.didOverflowWidth && contactTitleFontSize > 16f) {
                        contactTitleFontSize -= 1f
                    }
                }
            )
            if (signedIn) {
                IconButton(
                    onClick = onNewConversation,
                    modifier = Modifier
                        .size(48.dp)
                        .semantics {
                            contentDescription = newConversationDescription
                        }
                ) {
                    Text(
                        text = "+",
                        color = KolamGold,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        if (!signedIn) {
            GuestSignInPrompt(
                message = stringResource(R.string.sign_in_new_conversation),
                onSignIn = onSignIn,
                enabled = true
            )
        } else {
            PullToRefreshBox(
                isRefreshing = isLoading && conversations.isNotEmpty(),
                onRefresh = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("contact-conversation-list"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (errorMessage != null && conversations.isNotEmpty()) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = KolamCoolSilver
                                ),
                                border = BorderStroke(1.dp, KolamSilverBorder)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        stringResource(R.string.refresh_conversations_cached),
                                        color = KolamContainerSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                    TextButton(
                                        onClick = onRetry,
                                        colors = kolamStandardButtonColors()
                                    ) { Text(stringResource(R.string.retry)) }
                                }
                            }
                        }
                    }
                    when {
                        isLoading && conversations.isEmpty() -> item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = KolamCoolSilver
                                ),
                                border = BorderStroke(1.dp, KolamSilverBorder)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator(color = KolamBronze)
                                    Spacer(Modifier.height(12.dp))
                                    Text(
                                        stringResource(R.string.loading_conversations),
                                        color = KolamContainerSecondary
                                    )
                                }
                            }
                        }
                        errorMessage != null && conversations.isEmpty() -> item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = KolamCoolSilver
                                ),
                                border = BorderStroke(1.dp, KolamSilverBorder)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        if (errorMessage == NetworkErrors.DISPLAY_TEXT) {
                                            stringResource(R.string.network_error_message)
                                        } else {
                                            errorMessage
                                        },
                                        color = KolamContainerSecondary,
                                        fontSize = 16.sp,
                                        textAlign = TextAlign.Center
                                    )
                                    TextButton(
                                        onClick = onRetry,
                                        colors = kolamStandardButtonColors()
                                    ) { Text(stringResource(R.string.retry)) }
                                }
                            }
                        }
                        conversations.isEmpty() -> item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = KolamCoolSilver
                                ),
                                border = BorderStroke(1.dp, KolamSilverBorder)
                            ) {
                                Text(
                                    stringResource(R.string.no_conversations),
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                    color = KolamContainerSecondary,
                                    fontSize = 16.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    items(orderedConversations, key = ContactConversation::id) { conversation ->
                        Card(
                            onClick = { onConversationSelected(conversation.id) },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, KolamSilverBorder),
                            colors = CardDefaults.cardColors(
                                containerColor = KolamCoolSilver
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        conversation.subject,
                                        color = KolamText,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val activityAt = conversation.latestMessageCreatedAt
                                        ?.takeIf(String::isNotBlank)
                                        ?: conversation.updatedAt.takeIf(String::isNotBlank)
                                        ?: conversation.createdAt
                                    Text(
                                        text = formatContactConversationTimestamp(activityAt),
                                        color = KolamContainerSecondary,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 5.dp)
                                    )
                                    Text(
                                        text = when (val preview =
                                            conversation.latestMessagePreview
                                                ?.takeIf(String::isNotBlank)
                                        ) {
                                            ContactJson.IMAGE_ATTACHMENT_PREVIEW ->
                                                stringResource(R.string.image_attachment)
                                            null -> stringResource(R.string.no_messages_yet)
                                            else -> preview
                                        },
                                        color = KolamContainerSecondary,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                                if (SupportUnreadStore.hasUnread(context, conversation.id)) {
                                    val unreadDescription = stringResource(
                                        R.string.unread_support_messages_for,
                                        conversation.subject
                                    )
                                    Box(
                                        Modifier
                                            .size(9.dp)
                                            .semantics {
                                                contentDescription = unreadDescription
                                            }
                                            .background(
                                                unreadColor,
                                                androidx.compose.foundation.shape.CircleShape
                                            )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (errorMessage == NetworkErrors.DISPLAY_TEXT && !networkDialogDismissed) {
        NetworkRetryDialog(
            onRetry = {
                networkDialogDismissed = true
                onRetry()
            },
            onClose = { networkDialogDismissed = true }
        )
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun formatContactConversationTimestamp(value: String): String {
    val instant = try {
        Instant.parse(value)
    } catch (_: IllegalArgumentException) {
        return value
    }
    return SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }.format(Date(instant.toEpochMilliseconds()))
}

@Composable
internal fun ColumnScope.MyFoldersGuestContent(
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    isSignInProcessing: Boolean
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier.offset(y = FolderSignedOutGroupVerticalOffset),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = 0.dp),
                shape = RoundedCornerShape(16.dp),
                color = KolamBackground,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    KolamBorder
                )
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = FolderBenefitsHorizontalPadding,
                        vertical = FolderBenefitsVerticalPadding
                    ),
                    verticalArrangement = Arrangement.spacedBy(FolderBenefitSpacing)
                ) {
                    Text(
                        stringResource(R.string.sign_in_to_create_folders),
                        color = KolamText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    FolderBenefit(stringResource(R.string.bookmark_favorite_kolams))
                    FolderBenefit(stringResource(R.string.organize_saved_kolams))
                    FolderBenefit(stringResource(R.string.keep_lessons_easy_to_find))
                }
            }
            Spacer(Modifier.height(FolderSignedOutBenefitsToGoogleSpacing))
            Button(
                onClick = onSignIn,
                enabled = !isSignInProcessing,
                modifier = Modifier.offset(y = FolderSignedOutGoogleButtonVerticalOffset),
                colors = ButtonDefaults.buttonColors(containerColor = KolamButtonYellow)
            ) { Text(stringResource(R.string.sign_in_with_google)) }
            Spacer(Modifier.height(FolderSignedOutGoogleToBackSpacing))
            Button(
                onClick = onBack,
                modifier = Modifier.offset(y = FolderSignedOutBackButtonVerticalOffset),
                colors = ButtonDefaults.buttonColors(containerColor = KolamButtonYellow)
            ) { Text(stringResource(R.string.back)) }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun FolderBenefit(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text("•", color = KolamBronze, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text(
            text = text,
            color = KolamMutedText,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun GuestSignInPrompt(
    message: String,
    onSignIn: () -> Unit,
    enabled: Boolean = false
) {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth(),
        color = KolamText,
        fontSize = 16.sp,
        lineHeight = 23.sp,
        textAlign = TextAlign.Center
    )
    Spacer(Modifier.height(18.dp))
    Button(onClick = onSignIn, enabled = enabled) {
        Text(stringResource(R.string.sign_in_with_google))
    }
}

@Composable
private fun DestinationScaffold(
    title: String,
    onBack: () -> Unit,
    prominentBackButton: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, color = KolamGold, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        content()
        Spacer(Modifier.height(24.dp))
        if (prominentBackButton) {
            Button(
                onClick = onBack,
                colors = ButtonDefaults.buttonColors(containerColor = KolamButtonYellow)
            ) { Text(stringResource(R.string.back)) }
        } else {
            TextButton(onClick = onBack, colors = kolamStandardButtonColors()) {
                Text(stringResource(R.string.back))
            }
        }
    }
}

@Composable
internal fun StoreActionDialog(
    title: String?,
    message: String,
    onDismiss: () -> Unit
) {
    if (title != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismiss, colors = kolamStandardButtonColors()) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }
}

internal fun openStoreFlow(
    context: android.content.Context,
    onOffline: (String) -> Unit,
    title: String
) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork
    val online = network != null &&
        connectivity.getNetworkCapabilities(network)
            ?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } == true
    if (!online) {
        onOffline(title)
        return
    }

    val packageUri = "https://play.google.com/store/apps/details?id=${context.packageName}"
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(packageUri))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
