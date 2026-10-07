package com.kolammaster.app

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.kolammaster.app.notifications.sendSupportReplyAndClearUnread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal const val MAX_CONTACT_IMAGE_BYTES = 10 * 1024 * 1024

private enum class ContactMessageDelivery {
    Sending,
    Sent,
    Failed
}

private data class ContactThreadMessage(
    val localId: String,
    val message: ContactMessage,
    val delivery: ContactMessageDelivery
)

private sealed interface ContactMessageImageState {
    data object Loading : ContactMessageImageState
    data class Loaded(val bitmap: ImageBitmap) : ContactMessageImageState
    data object Unavailable : ContactMessageImageState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ContactConversationDetailDestination(
    conversationId: String,
    onLoadConversation: suspend (String) -> ContactConversation,
    onLoadMessages: suspend (String) -> List<ContactMessage>,
    onSendMessage: suspend (String, String, String?) -> ContactMessage,
    onUploadImage: suspend (ByteArray) -> String,
    foregroundRefreshKey: Int = 0,
    externalRefreshKey: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var conversation by remember(conversationId) {
        mutableStateOf<ContactConversation?>(null)
    }
    var messages by remember(conversationId) {
        mutableStateOf<List<ContactThreadMessage>>(emptyList())
    }
    var isLoading by remember(conversationId) { mutableStateOf(true) }
    var hasLoaded by remember(conversationId) { mutableStateOf(false) }
    var retryKey by remember(conversationId) { mutableIntStateOf(0) }
    var isSending by remember(conversationId) { mutableStateOf(false) }
    var isImageBatchActive by remember(conversationId) { mutableStateOf(false) }
    var messageText by remember(conversationId) { mutableStateOf("") }
    var uploadProgress by remember(conversationId) { mutableStateOf<String?>(null) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(conversationId, retryKey, foregroundRefreshKey, externalRefreshKey) {
        isLoading = true
        try {
            val latestConversation = onLoadConversation(conversationId)
            val latestMessages = onLoadMessages(conversationId)
                .sortedBy(ContactMessage::createdAt)
                .map { ContactThreadMessage(it.id, it, ContactMessageDelivery.Sent) }
            conversation = latestConversation
            messages = latestMessages + messages.filter {
                it.delivery != ContactMessageDelivery.Sent
            }
            hasLoaded = true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            showContactLoadError(exception) { title, body ->
                errorTitle = title
                errorMessage = body
            }
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(messages.size, isLoading, isImeVisible) {
        if (!isLoading && messages.isNotEmpty()) {
            if (isImeVisible) delay(300)
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    fun reportFailure(exception: Exception, fallback: String) {
        val networkFailure = NetworkErrors.isNetworkFailure(exception)
        errorTitle = if (networkFailure) NetworkErrors.TITLE else "Contact Us"
        errorMessage = if (networkFailure) NetworkErrors.MESSAGE else fallback
    }

    suspend fun sendTextMessage() {
        val trimmed = messageText.trim()
        if (trimmed.isEmpty() || isSending) return
        if (conversation?.status.equals("CLOSED", ignoreCase = true)) {
            errorTitle = "Contact Us"
            errorMessage = "This conversation has been closed. Your message was not sent."
            retryKey++
            return
        }
        val localId = "local-${UUID.randomUUID()}"
        val localMessage = ContactMessage(
            id = localId,
            conversationId = conversationId,
            sender = "USER",
            message = trimmed,
            imageUrl = null,
            createdAt = System.currentTimeMillis().toString()
        )
        messages = messages + ContactThreadMessage(
            localId,
            localMessage,
            ContactMessageDelivery.Sending
        )
        messageText = ""
        isSending = true
        try {
            val saved = sendSupportReplyAndClearUnread(context, conversationId) {
                onSendMessage(conversationId, trimmed, null)
            }
            messages = messages.map {
                if (it.localId == localId) {
                    ContactThreadMessage(saved.id, saved, ContactMessageDelivery.Sent)
                } else {
                    it
                }
            }.sortedBy { it.message.createdAt }
            conversation = conversation?.copy(updatedAt = saved.createdAt)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            messages = messages.map {
                if (it.localId == localId) it.copy(delivery = ContactMessageDelivery.Failed)
                else it
            }
            reportFailure(exception, "Your message was not sent. Please try again.")
            if (exception is ContactConversationClosedException) retryKey++
        } finally {
            isSending = false
        }
    }

    suspend fun uploadBitmap(
        bitmap: Bitmap,
        index: Int,
        total: Int,
        batchItem: Boolean = false
    ) {
        if (isSending || (isImageBatchActive && !batchItem)) return
        isSending = true
        uploadProgress = "Preparing image ${index + 1} of $total..."
        var localId: String? = null
        try {
            val currentConversation = onLoadConversation(conversationId)
            conversation = currentConversation
            check(currentConversation.status.equals("OPEN", ignoreCase = true)) {
                "This conversation has been closed."
            }
            val bytes = withContext(Dispatchers.Default) { bitmap.toContactWebp() }
            require(bytes.size <= MAX_CONTACT_IMAGE_BYTES) {
                "Image must be 10 MB or smaller after WebP conversion."
            }
            uploadProgress = "Uploading image ${index + 1} of $total..."
            val imageUrl = onUploadImage(bytes)
            val localMessageId = "local-${UUID.randomUUID()}"
            localId = localMessageId
            val localMessage = ContactMessage(
                id = localMessageId,
                conversationId = conversationId,
                sender = "USER",
                message = "",
                imageUrl = imageUrl,
                createdAt = System.currentTimeMillis().toString()
            )
            messages = messages + ContactThreadMessage(
                localMessageId,
                localMessage,
                ContactMessageDelivery.Sending
            )
            uploadProgress = "Saving image ${index + 1} of $total..."
            val saved = sendSupportReplyAndClearUnread(context, conversationId) {
                onSendMessage(conversationId, "", imageUrl)
            }
            messages = messages.map {
                if (it.localId == localMessageId) {
                    ContactThreadMessage(saved.id, saved, ContactMessageDelivery.Sent)
                } else {
                    it
                }
            }.sortedBy { it.message.createdAt }
            conversation = conversation?.copy(updatedAt = saved.createdAt)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            localId?.let { failedId ->
                messages = messages.map {
                    if (it.localId == failedId) it.copy(delivery = ContactMessageDelivery.Failed)
                    else it
                }
            }
            reportFailure(exception, "The image was not sent. Please try again.")
            if (exception is ContactConversationClosedException ||
                exception.message == "This conversation has been closed."
            ) retryKey++
        } finally {
            uploadProgress = null
            isSending = false
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = cameraUri
        cameraUri = null
        if (uri != null) {
            if (captured) {
                scope.launch {
                    try {
                        val bitmap = context.decodeContactImage(uri)
                        uploadBitmap(bitmap, 0, 1)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (exception: Exception) {
                        reportFailure(exception, "The selected image could not be read.")
                    } finally {
                        context.contentResolver.delete(uri, null, null)
                    }
                }
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                val imageDirectory = File(context.cacheDir, "contact-images")
                if (!imageDirectory.exists() && !imageDirectory.mkdirs()) {
                    throw IOException("Could not prepare the camera image file.")
                }
                val imageFile = File.createTempFile("contact-", ".jpg", imageDirectory)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.contact-uploads",
                    imageFile
                )
                cameraUri = uri
                cameraLauncher.launch(uri)
            } catch (exception: Exception) {
                cameraUri?.let { context.contentResolver.delete(it, null, null) }
                cameraUri = null
                reportFailure(exception, "The camera could not be opened.")
            }
        } else {
            errorTitle = "Camera permission"
            errorMessage = "Allow camera access to take a Contact Us photo."
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { selectedUris ->
        if (selectedUris.isNotEmpty()) {
            scope.launch {
                if (isSending || isImageBatchActive) return@launch
                isImageBatchActive = true
                try {
                    selectedUris.forEachIndexed { index, uri ->
                        try {
                            uploadProgress =
                                "Preparing image ${index + 1} of ${selectedUris.size}..."
                            uploadBitmap(
                                context.decodeContactImage(uri),
                                index,
                                selectedUris.size,
                                batchItem = true
                            )
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            reportFailure(exception, "A selected image could not be sent.")
                        }
                    }
                } finally {
                    uploadProgress = null
                    isImageBatchActive = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .consumeWindowInsets(WindowInsets.systemBars)
            .imePadding()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = if (isImeVisible) 4.dp else 12.dp)
    ) {
        Text(
            text = conversation?.subject ?: "Conversation",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        val isClosed = conversation?.status?.equals("CLOSED", ignoreCase = true) == true
        if (isClosed) {
            Text(
                "This conversation has been closed. If you still need assistance, please start a new conversation.",
                color = Color(0xFFE8E8E8),
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        PullToRefreshBox(
            isRefreshing = isLoading && hasLoaded,
            onRefresh = { retryKey++ },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (isLoading && !hasLoaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFC9A86A))
                }
            } else if (!hasLoaded && errorMessage != null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (errorTitle == NetworkErrors.TITLE) {
                                NetworkErrors.DISPLAY_TEXT
                            } else {
                                "Could not load this conversation."
                            },
                            color = Color.White
                        )
                        TextButton(onClick = { retryKey++ }) { Text("Retry") }
                    }
                }
            } else if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages in this conversation yet.", color = Color.White)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .imeNestedScroll(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages, key = ContactThreadMessage::localId) { item ->
                        ContactMessageBubble(
                            item = item,
                            onImageClick = { imageViewerUrl = it }
                        )
                    }
                }
            }
        }
        if (!isClosed && conversation != null) {
            uploadProgress?.let {
                Text(
                    it,
                    color = Color(0xFFE8E8E8),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFF34393F),
                        RoundedCornerShape(28.dp)
                    )
                    .border(
                        BorderStroke(1.dp, Color(0xFFB8B8B8)),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showAttachmentOptions = true },
                    enabled = !isSending && !isImageBatchActive,
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("+", fontSize = 26.sp, color = Color.White)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    if (messageText.isEmpty()) {
                        Text("Type a message...", color = Color(0xFFE0E0E0))
                    }
                    BasicTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        enabled = !isSending && !isImageBatchActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Message input" },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 16.sp
                        ),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Sentences
                        )
                    )
                }
                TextButton(
                    onClick = { scope.launch { sendTextMessage() } },
                    enabled = !isSending && !isImageBatchActive && messageText.isNotBlank(),
                    modifier = Modifier
                        .size(44.dp)
                        .semantics { contentDescription = "Send message" }
                ) {
                    if (isSending && uploadProgress == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFC9A86A)
                        )
                    } else {
                        Text("➤", fontSize = 20.sp, color = Color(0xFFC9A86A))
                    }
                }
            }
        }
    }

    if (showAttachmentOptions) {
        ContactImageAttachmentOptionsDialog(
            onCamera = {
                showAttachmentOptions = false
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onGallery = {
                showAttachmentOptions = false
                galleryLauncher.launch("image/*")
            },
            onDismiss = { showAttachmentOptions = false }
        )
    }

    if (errorTitle != null && errorMessage != null) {
        AlertDialog(
            onDismissRequest = {
                errorTitle = null
                errorMessage = null
            },
            title = { Text(errorTitle.orEmpty()) },
            text = { Text(errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    errorTitle = null
                    errorMessage = null
                }) { Text("OK") }
            }
        )
    }

    imageViewerUrl?.let { url ->
        ContactImageViewer(url = url, onClose = { imageViewerUrl = null })
    }
}

@Composable
private fun ContactMessageBubble(
    item: ContactThreadMessage,
    onImageClick: (String) -> Unit
) {
    val isUserMessage = item.message.sender.equals("USER", ignoreCase = true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUserMessage) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isUserMessage) Color(0xFF285B5B) else Color(0xFF4A4132)
            )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = if (isUserMessage) "You" else "Support",
                    color = if (isUserMessage) Color(0xFFC8E8DD) else Color(0xFFFFD48A),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                if (item.message.message.isNotBlank()) {
                    Text(item.message.message, color = Color.White, fontSize = 16.sp)
                }
                item.message.imageUrl?.let { url ->
                    ContactMessageImage(url, onClick = { onImageClick(url) })
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.message.createdAt,
                        color = Color.White.copy(alpha = 0.65f),
                        fontSize = 11.sp
                    )
                    if (isUserMessage) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (item.delivery) {
                                ContactMessageDelivery.Sending -> "Sending"
                                ContactMessageDelivery.Failed -> "Not sent"
                                ContactMessageDelivery.Sent -> "Sent"
                            },
                            color = if (item.delivery == ContactMessageDelivery.Failed) {
                                Color(0xFFFFB4AB)
                            } else {
                                Color.White.copy(alpha = 0.75f)
                            },
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ContactMessageImage(
    url: String,
    onClick: () -> Unit,
    loadImage: suspend (String) -> ImageBitmap? = { imageUrl ->
        withContext(Dispatchers.IO) { loadThumbnail(imageUrl) }
    }
) {
    val imageState by produceState<ContactMessageImageState>(
        initialValue = ContactMessageImageState.Loading,
        url
    ) {
        value = loadImage(url)?.let(ContactMessageImageState::Loaded)
            ?: ContactMessageImageState.Unavailable
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 220.dp)
            .height(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.2f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when (val state = imageState) {
            ContactMessageImageState.Loading ->
                Text("Image loading…", color = Color.White.copy(alpha = 0.75f))
            is ContactMessageImageState.Loaded ->
                Image(
                    bitmap = state.bitmap,
                    contentDescription = "Contact conversation image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            ContactMessageImageState.Unavailable ->
                Text("Image not available", color = Color.White.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun ContactImageViewer(url: String, onClose: () -> Unit) {
    val image by produceState<ImageBitmap?>(initialValue = null, url) {
        value = withContext(Dispatchers.IO) { loadThumbnail(url) }
    }
    val loadedImage = image
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offsetX by remember(url) { mutableFloatStateOf(0f) }
    var offsetY by remember(url) { mutableFloatStateOf(0f) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (loadedImage != null) {
                Image(
                    bitmap = loadedImage,
                    contentDescription = "Full-screen contact conversation image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(url) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                )
            } else {
                CircularProgressIndicator(color = Color.White)
            }
            Button(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
            ) { Text("Close") }
        }
    }
}

private fun showContactLoadError(
    exception: Exception,
    showError: (String, String) -> Unit
) {
    if (NetworkErrors.isNetworkFailure(exception)) {
        showError(NetworkErrors.TITLE, NetworkErrors.MESSAGE)
    } else {
        showError("Contact Us", "Could not load this conversation. Please try again.")
    }
}

internal suspend fun Context.decodeContactImage(uri: Uri): Bitmap =
    withContext(Dispatchers.IO) {
        contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
            ?: throw IOException("Could not read the selected image.")
    }

internal fun Bitmap.toContactWebp(): ByteArray {
    val output = ByteArrayOutputStream()
    val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
        Bitmap.CompressFormat.WEBP_LOSSY
    } else {
        @Suppress("DEPRECATION")
        Bitmap.CompressFormat.WEBP
    }
    check(compress(format, 86, output)) { "Could not convert the image to WebP." }
    return output.toByteArray()
}

@Composable
internal fun ContactImageAttachmentOptionsDialog(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add attachment") },
        text = {
            Column {
                Text("Choose how to add an image.")
                TextButton(
                    onClick = onCamera,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Camera") }
                TextButton(
                    onClick = onGallery,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Photo Gallery") }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        dismissButton = null
    )
}
