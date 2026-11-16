package com.kolammaster.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import android.content.Intent
import android.net.Uri
import java.util.Locale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface AnnouncementImageState {
    data object Loading : AnnouncementImageState
    data class Loaded(val bitmap: ImageBitmap) : AnnouncementImageState
    data object Failed : AnnouncementImageState
}

@Composable
internal fun AnnouncementsDestination(
    announcements: List<Announcement>,
    isLoading: Boolean,
    errorMessage: String?,
    actionErrorMessage: String?,
    selectedAnnouncement: Announcement?,
    isMarkingAllRead: Boolean,
    onRetry: () -> Unit,
    networkRetryAction: (() -> Unit)? = null,
    onDismissNetworkDialog: () -> Unit = {},
    onAnnouncementSelected: (Announcement) -> Unit,
    onBackFromDetail: () -> Unit,
    onMarkAllRead: () -> Unit,
    activeLanguage: String = DEFAULT_LANGUAGE_IDENTIFIER
) {
    BackHandler(enabled = selectedAnnouncement != null, onBack = onBackFromDetail)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        actionErrorMessage?.let { message ->
            Text(
                text = if (message == NetworkErrors.DISPLAY_TEXT) {
                    currentAppString("network_error_message")
                } else if (message == "\u0000announcements.mark_all_failed") {
                    currentAppString("announcements_mark_all_failed")
                } else {
                    message
                },
                color = KolamBronze,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (selectedAnnouncement == null) {
            AnnouncementsList(
                announcements = announcements,
                isLoading = isLoading,
                errorMessage = errorMessage,
                isMarkingAllRead = isMarkingAllRead,
                onRetry = onRetry,
                onAnnouncementSelected = onAnnouncementSelected,
                onMarkAllRead = onMarkAllRead,
                activeLanguage = activeLanguage
            )
        } else {
            AnnouncementDetail(
                announcement = selectedAnnouncement,
                onBack = onBackFromDetail,
                activeLanguage = activeLanguage
            )
        }
    }
    networkRetryAction?.let { retry ->
        NetworkRetryDialog(
            onRetry = {
                onDismissNetworkDialog()
                retry()
            },
            onClose = onDismissNetworkDialog
        )
    }
}

@Composable
private fun AnnouncementsList(
    announcements: List<Announcement>,
    isLoading: Boolean,
    errorMessage: String?,
    isMarkingAllRead: Boolean,
    onRetry: () -> Unit,
    onAnnouncementSelected: (Announcement) -> Unit,
    onMarkAllRead: () -> Unit,
    activeLanguage: String = DEFAULT_LANGUAGE_IDENTIFIER
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AutoFitSingleLineText(
            text = currentAppString("announcement"),
            modifier = Modifier.weight(1f),
            maxFontSize = 24f,
            minFontSize = 16f,
            color = KolamGold,
            fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Start
        )
        val hasUnreadAnnouncements = announcements.any { !it.isRead }
        Button(
            onClick = onMarkAllRead,
            enabled = hasUnreadAnnouncements && !isMarkingAllRead,
            modifier = Modifier
                .width(140.dp)
                .height(40.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = KolamButtonYellow,
                contentColor = KolamButtonText,
                disabledContainerColor = KolamButtonYellow,
                disabledContentColor = KolamButtonText
            )
        ) {
            if (isMarkingAllRead) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = KolamButtonText
                )
            } else {
                AutoFitSingleLineText(
                    text = currentAppString("mark_all_read"),
                    modifier = Modifier.fillMaxWidth(),
                    maxFontSize = 14f,
                    minFontSize = 9f,
                    color = KolamButtonText
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (announcements.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = KolamCoolSilver),
                border = BorderStroke(1.dp, KolamSilverBorder)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isLoading -> CircularProgressIndicator(color = KolamBronze)
                        errorMessage != null -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                if (errorMessage == NetworkErrors.DISPLAY_TEXT) {
                                    currentAppString("network_error_message")
                                } else {
                                    errorMessage
                                },
                                color = KolamContainerSecondary,
                                fontSize = 16.sp
                            )
                            Button(onClick = onRetry) { Text(currentAppString("retry")) }
                        }
                        else -> Text(
                            currentAppString("no_announcements"),
                            color = KolamContainerSecondary,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (errorMessage != null) {
            item {
                Text(
                    text = currentAppString("saved_announcements_warning"),
                    color = KolamBronze,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                TextButton(onClick = onRetry, colors = kolamStandardButtonColors()) {
                    Text(currentAppString("retry"))
                }
            }
        }
        items(announcements, key = Announcement::id) { announcement ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAnnouncementSelected(announcement) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = KolamCoolSilver),
                border = BorderStroke(1.dp, KolamSilverBorder)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = announcement.localizedSubject(activeLanguage).ifBlank {
                                currentAppString("announcement")
                            },
                            modifier = Modifier.weight(1f),
                            color = KolamText,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!announcement.isRead) {
                            Spacer(Modifier.width(10.dp))
                            Box(
                                Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFF6B6B))
                            )
                        }
                    }
                    AnnouncementRepository.displayDate(announcement.createdAt)?.let { date ->
                        Text(
                            text = date,
                            color = KolamContainerSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                    ClickableAnnouncementText(
                        text = announcement.localizedMessage(activeLanguage),
                        color = KolamContainerSecondary,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnouncementDetail(
    announcement: Announcement,
    onBack: () -> Unit,
    activeLanguage: String = DEFAULT_LANGUAGE_IDENTIFIER
) {
    var showImage by remember(announcement.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, colors = kolamStandardButtonColors()) {
            AutoFitSingleLineText(
                text = currentAppString("back_to_announcements"),
                maxFontSize = 14f,
                minFontSize = 10f,
                color = KolamButtonText
            )
        }
        Card(
            modifier = Modifier
                .fillMaxSize(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = KolamCoolSilver),
            border = BorderStroke(1.dp, KolamSilverBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = announcement.localizedSubject(activeLanguage).ifBlank { currentAppString("announcement") },
                    color = KolamText,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Bold
                )
                AnnouncementRepository.displayDate(announcement.createdAt)?.let { date ->
                    Text(
                        text = date,
                        color = KolamContainerSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                ClickableAnnouncementText(
                    text = announcement.localizedMessage(activeLanguage),
                    color = KolamText,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier.padding(top = 18.dp)
                )
                announcement.imageUrl?.takeIf(String::isNotBlank)?.let { url ->
                    Spacer(Modifier.height(18.dp))
                    AnnouncementImage(
                        url = url,
                        showImageDialog = showImage,
                        onImageOpen = { showImage = true },
                        onImageClose = { showImage = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun AnnouncementImage(
    url: String,
    showImageDialog: Boolean,
    onImageOpen: () -> Unit,
    onImageClose: () -> Unit
) {
    val imageState = produceState<AnnouncementImageState>(
        initialValue = AnnouncementImageState.Loading,
        url
    ) {
        value = withContext(Dispatchers.IO) { loadThumbnail(url) }
            ?.let(AnnouncementImageState::Loaded)
            ?: AnnouncementImageState.Failed
    }.value
    when (val state = imageState) {
        AnnouncementImageState.Loading -> Text(
            text = currentAppString("loading_image"),
            color = KolamContainerSecondary,
            fontSize = 13.sp
        )
        AnnouncementImageState.Failed -> Text(
            text = currentAppString("image_load_failed"),
            color = KolamContainerSecondary,
            fontSize = 13.sp
        )
        is AnnouncementImageState.Loaded -> {
            Image(
                bitmap = state.bitmap,
                contentDescription = currentAppString("announcement_image"),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.bitmap.width.toFloat() / state.bitmap.height.toFloat())
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onImageOpen),
                contentScale = ContentScale.Fit
            )
            if (showImageDialog) {
                AlertDialog(
                    onDismissRequest = onImageClose,
                    confirmButton = {
                        TextButton(
                            onClick = onImageClose,
                            colors = kolamStandardButtonColors()
                        ) { Text(currentAppString("close")) }
                    },
                    text = {
                        Image(
                            bitmap = state.bitmap,
                            contentDescription = currentAppString("announcement_image"),
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit
                        )
                    },
                    containerColor = KolamCoolSilver
                )
            }
        }
    }
}

@Composable
private fun ClickableAnnouncementText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val context = LocalContext.current
    val annotatedString = remember(text) {
        buildAnnotatedString {
            val urlRegex = Regex("""\b(?:https?://|www\.)\S+\b""", RegexOption.IGNORE_CASE)
            var lastIndex = 0
            for (match in urlRegex.findAll(text)) {
                val range = match.range
                if (range.first > lastIndex) {
                    append(text.substring(lastIndex, range.first))
                }
                val rawUrl = match.value
                val fullUrl = if (rawUrl.startsWith("www.", ignoreCase = true)) "https://$rawUrl" else rawUrl

                pushStringAnnotation(tag = "URL", annotation = fullUrl)
                withStyle(
                    style = SpanStyle(
                        color = KolamGold,
                        textDecoration = TextDecoration.Underline
                    )
                ) {
                    append(rawUrl)
                }
                pop()
                lastIndex = range.last + 1
            }
            if (lastIndex < text.length) {
                append(text.substring(lastIndex))
            }
        }
    }

    ClickableText(
        text = annotatedString,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = fontSize,
            lineHeight = lineHeight
        ),
        maxLines = maxLines,
        overflow = overflow,
        onClick = { offset ->
            annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation ->
                    val urlStr = annotation.item
                    try {
                        val uri = Uri.parse(urlStr)
                        val scheme = uri.scheme?.lowercase(Locale.ROOT)
                        if (scheme == "http" || scheme == "https") {
                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        }
                    } catch (_: Exception) {}
                }
        }
    )
}
