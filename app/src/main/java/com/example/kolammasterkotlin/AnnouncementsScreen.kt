package com.kolammaster.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun AnnouncementsDestination(
    announcements: List<Announcement>,
    isLoading: Boolean,
    errorMessage: String?,
    actionErrorMessage: String?,
    selectedAnnouncement: Announcement?,
    isMarkingAllRead: Boolean,
    onRetry: () -> Unit,
    onAnnouncementSelected: (Announcement) -> Unit,
    onBackFromDetail: () -> Unit,
    onMarkAllRead: () -> Unit
) {
    BackHandler(enabled = selectedAnnouncement != null, onBack = onBackFromDetail)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF202327))
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        actionErrorMessage?.let { message ->
            Text(
                text = message,
                color = Color(0xFFFFD28A),
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
                onMarkAllRead = onMarkAllRead
            )
        } else {
            AnnouncementDetail(
                announcement = selectedAnnouncement,
                onBack = onBackFromDetail
            )
        }
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
    onMarkAllRead: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Announcement",
            modifier = Modifier.weight(1f),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        val hasUnreadAnnouncements = announcements.any { !it.isRead }
        Button(
            onClick = onMarkAllRead,
            enabled = hasUnreadAnnouncements && !isMarkingAllRead,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1976D2),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFF1976D2),
                disabledContentColor = Color.White
            )
        ) {
            if (isMarkingAllRead) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Text(
                    "Mark All Read",
                    color = if (hasUnreadAnnouncements) Color.White else Color.LightGray
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
            when {
                isLoading -> CircularProgressIndicator(color = Color(0xFFC9A86A))
                errorMessage != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(errorMessage, color = Color.White, fontSize = 16.sp)
                    Button(onClick = onRetry) { Text("Retry") }
                }
                else -> Text(
                    "There are no announcements yet.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 16.sp
                )
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
                    text = "Could not refresh announcements. Showing saved announcements.",
                    color = Color(0xFFFFD28A),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
        items(announcements, key = Announcement::id) { announcement ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAnnouncementSelected(announcement) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3F6668))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = announcement.subject.ifBlank { "Announcement" },
                            modifier = Modifier.weight(1f),
                            color = Color.White,
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
                            color = Color(0xFFD9D2C6),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                    Text(
                        text = announcement.message,
                        color = Color.White.copy(alpha = 0.86f),
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
    onBack: () -> Unit
) {
    var showImage by remember(announcement.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("Back to announcements") }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF34393F))
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = announcement.subject.ifBlank { "Announcement" },
                color = Color.White,
                fontSize = 23.sp,
                fontWeight = FontWeight.Bold
            )
            AnnouncementRepository.displayDate(announcement.createdAt)?.let { date ->
                Text(
                    text = date,
                    color = Color(0xFFD9D2C6),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            Text(
                text = announcement.message,
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 18.dp)
            )
            announcement.imageUrl?.let { url ->
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

@Composable
private fun AnnouncementImage(
    url: String,
    showImageDialog: Boolean,
    onImageOpen: () -> Unit,
    onImageClose: () -> Unit
) {
    val bitmap = produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) { loadThumbnail(url) }
    }.value
    if (bitmap == null) {
        Text(
            text = "Image could not be loaded.",
            color = Color(0xFFD9D2C6),
            fontSize = 13.sp
        )
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Announcement image",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onImageOpen),
            contentScale = ContentScale.Fit
        )
        if (showImageDialog) {
            AlertDialog(
                onDismissRequest = onImageClose,
                confirmButton = { TextButton(onClick = onImageClose) { Text("Close") } },
                text = {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Announcement image",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.Fit
                    )
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}
