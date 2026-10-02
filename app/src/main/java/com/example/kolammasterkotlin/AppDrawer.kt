package com.example.kolammasterkotlin

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

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

private val drawerBackground = Color(0xFF34393F)
private val drawerMuted = Color(0xFFD9D2C6)
private val drawerSwitchOffTrack = Color(0xFF454A50)
private val drawerSwitchOnThumb = Color(0xFFC9A86A)
private val unreadColor = Color(0xFFFF6B6B)

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
                    text = "Back",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clickable(onClick = onBack)
                        .padding(vertical = 8.dp),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(24.dp))

                DrawerMenuRow("Home") { onSelect(DrawerAction.Home) }
                DrawerMenuRow("Profile") { onSelect(DrawerAction.Profile) }
                DrawerMenuRow("My Folders") { onSelect(DrawerAction.MyFolders) }
                DrawerMenuRow("Language") { onSelect(DrawerAction.Language) }
                DrawerMenuRow("Contact Us", unread = unreadContactUs) {
                    onSelect(DrawerAction.ContactUs)
                }
                DrawerMenuRow("Announcements", unread = unreadAnnouncements) {
                    onSelect(DrawerAction.Announcements)
                }
                DrawerSettingsRow(settingsExpanded) {
                    onSettingsExpandedChange(!settingsExpanded)
                }
                if (settingsExpanded) {
                    SettingsNotifications()
                }
                DrawerMenuRow("Update App") { onSelect(DrawerAction.UpdateApp) }
                DrawerMenuRow("Rate App") { onSelect(DrawerAction.RateApp) }
            }
        }
    }
}

@Composable
private fun DrawerMenuRow(
    label: String,
    unread: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(
                    color = Color.White.copy(alpha = 0.16f),
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
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (unread) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(unreadColor, androidx.compose.foundation.shape.CircleShape)
            )
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
                    color = Color.White.copy(alpha = 0.16f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Settings",
            modifier = Modifier.weight(1f),
            color = Color.White,
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
    val permissionGranted = remember {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
    var contactNotifications by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Notifications",
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
                text = "Contact Us Notifications",
                modifier = Modifier.weight(1f),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (permissionGranted && contactNotifications) "ON" else "OFF",
                modifier = Modifier.padding(end = 6.dp),
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Switch(
                checked = permissionGranted && contactNotifications,
                onCheckedChange = { contactNotifications = it },
                enabled = permissionGranted,
                colors = SwitchDefaults.colors(
                    uncheckedTrackColor = drawerSwitchOffTrack,
                    checkedTrackColor = Color(0xFF8F6F38),
                    uncheckedThumbColor = drawerMuted,
                    checkedThumbColor = drawerSwitchOnThumb
                )
            )
        }
    }
}

@Composable
internal fun LanguageSettingsScreen(
    activeLanguage: String,
    onSelect: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Language",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(20.dp))
        supportedLanguages.forEach { (identifier, nativeName) ->
            DrawerMenuRow(
                label = if (identifier == activeLanguage) "$nativeName  ✓" else nativeName,
                onClick = { onSelect(identifier) }
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

internal data class AccountProfile(
    val name: String,
    val email: String,
    val photo: ImageBitmap? = null
)

@Composable
internal fun ProfileDestination(
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    account: AccountProfile? = null,
    onSignOut: () -> Unit = {}
) {
    DestinationScaffold(title = "Profile", onBack = onBack) {
        if (account == null) {
            GuestSignInPrompt(
                message = "You are currently using Kolam Master as a Guest.",
                onSignIn = onSignIn
            )
        } else {
            account.photo?.let { photo ->
                androidx.compose.foundation.Image(
                    bitmap = photo,
                    contentDescription = "Profile photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp)
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(account.name, color = Color.White, fontSize = 20.sp)
            Text(account.email, color = drawerMuted, fontSize = 16.sp)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onSignOut) { Text("Sign Out") }
        }
    }
}

@Composable
internal fun MyFoldersDestination(
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    signedIn: Boolean = false
) {
    DestinationScaffold(title = "My Folders", onBack = onCancel) {
        if (signedIn) {
            Text("Your folders will appear here.", color = Color.White, fontSize = 18.sp)
        } else {
            Text("Sign in to view your folders.", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onSignIn, enabled = false) { Text("Sign in with Google") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
internal fun ContactUsDestination(
    onSignIn: () -> Unit,
    onBack: () -> Unit,
    signedIn: Boolean = false,
    conversations: List<String> = emptyList(),
    onNewConversation: () -> Unit = {}
) {
    DestinationScaffold(title = "Contact Us", onBack = onBack) {
        conversations.forEach { conversation ->
            Text(conversation, color = Color.White, fontSize = 16.sp)
        }
        if (signedIn) {
            Button(onClick = onNewConversation) { Text("New Conversation") }
        } else if (conversations.isEmpty()) {
            GuestSignInPrompt(
                message = "Sign in to start a new conversation.",
                onSignIn = onSignIn
            )
        }
    }
}

@Composable
private fun GuestSignInPrompt(message: String, onSignIn: () -> Unit) {
    Text(message, color = Color.White, fontSize = 18.sp)
    Spacer(Modifier.height(18.dp))
    Button(onClick = onSignIn, enabled = false) { Text("Sign in with Google") }
}

@Composable
internal fun AnnouncementsDestination(onBack: () -> Unit) {
    DestinationScaffold(title = "Announcements", onBack = onBack) {
        Text(
            text = "Announcements will be available here.",
            color = Color.White,
            fontSize = 18.sp
        )
    }
}

@Composable
private fun DestinationScaffold(
    title: String,
    onBack: () -> Unit,
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
        Text(title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        content()
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onBack) { Text("Back") }
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
                TextButton(onClick = onDismiss) { Text("OK") }
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
