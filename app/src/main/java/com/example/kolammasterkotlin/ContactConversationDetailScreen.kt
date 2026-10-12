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
import androidx.compose.ui.res.stringResource
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
private const val CONTACT_SEND_RECONCILIATION_WINDOW_MILLIS = 5 * 60 * 1000L

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
    accountId: String? = null,
    localCache: ContactLocalCache? = null,
    foregroundRefreshKey: Int = 0,
    externalRefreshKey: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val contactMessageFailed = stringResource(R.string.contact_message_not_sent)
    val contactImageFailed = stringResource(R.string.contact_image_not_sent)
    val contactConversationClosed = stringResource(R.string.conversation_closed)
    val contactConversationClosedMessage = stringResource(R.string.conversation_closed_message)
    val contactUsTitle = stringResource(R.string.contact_us)
    val contactConversationRefreshFallback = stringResource(R.string.contact_refresh_saved_messages)
    val contactConversationLoadFailed = stringResource(R.string.conversation_load_failed)
    val contactLoadRetryFailed = stringResource(R.string.contact_load_retry_failed)
    val contactImageReadFailed = stringResource(R.string.contact_image_read_failed)
    val contactImageSizeLimit = stringResource(R.string.contact_image_size_limit)
    val contactImageBatchFailed = stringResource(R.string.contact_image_batch_failed)
    val contactCameraPermissionMessage =
        stringResource(R.string.contact_camera_permission_message)
    val contactCameraPermissionTitle =
        stringResource(R.string.contact_camera_permission_title)
    val cameraOpenFailed = stringResource(R.string.camera_open_failed)
    val conversationLabel = stringResource(R.string.conversation_label)
    val contactPrepareImagePattern = stringResource(R.string.contact_prepare_image)
    val contactUploadImagePattern = stringResource(R.string.contact_upload_image)
    val contactSaveImagePattern = stringResource(R.string.contact_save_image)
    val cachedConversation = remember(accountId, conversationId, localCache) {
        accountId?.let { localCache?.loadConversation(it, conversationId) }
    }
    val cachedMessages = remember(accountId, conversationId, localCache) {
        accountId?.let { localCache?.loadMessages(it, conversationId) }
    }
    var conversation by remember(accountId, conversationId) {
        mutableStateOf(cachedConversation?.value)
    }
    var messages by remember(accountId, conversationId) {
        mutableStateOf(
            cachedMessages?.value.orEmpty().map {
                ContactThreadMessage(it.id, it, ContactMessageDelivery.Sent)
            }
        )
    }
    var isLoading by remember(accountId, conversationId) {
        mutableStateOf(cachedMessages == null)
    }
    var hasLoaded by remember(accountId, conversationId) {
        mutableStateOf(cachedMessages != null)
    }
    var initialLoadFailed by remember(accountId, conversationId) {
        mutableStateOf(false)
    }
    var initialLoadWasNetworkFailure by remember(accountId, conversationId) {
        mutableStateOf(false)
    }
    var loadError by remember(accountId, conversationId) { mutableStateOf<String?>(null) }
    var retryKey by remember(accountId, conversationId) { mutableIntStateOf(0) }
    var lastHandledRetryKey by remember(accountId, conversationId) {
        mutableIntStateOf(0)
    }
    var lastHandledForegroundRefreshKey by remember(accountId, conversationId) {
        mutableIntStateOf(foregroundRefreshKey)
    }
    var lastHandledExternalRefreshKey by remember(accountId, conversationId) {
        mutableIntStateOf(0)
    }
    var isSending by remember(accountId, conversationId) { mutableStateOf(false) }
    var isImageBatchActive by remember(accountId, conversationId) { mutableStateOf(false) }
    var messageText by remember(accountId, conversationId) { mutableStateOf("") }
    var uploadProgress by remember(accountId, conversationId) { mutableStateOf<String?>(null) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var networkRetryAction by remember(accountId, conversationId) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    var imageViewerUrl by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(
        accountId,
        conversationId,
        retryKey,
        foregroundRefreshKey,
        externalRefreshKey
    ) {
        val latestCachedConversation =
            accountId?.let { localCache?.loadConversation(it, conversationId) }
        val latestCachedMessages = accountId?.let {
            localCache?.loadMessages(it, conversationId)
        }
        if (conversation == null) conversation = latestCachedConversation?.value
        if (messages.isEmpty() && latestCachedMessages != null) {
            messages = latestCachedMessages.value.map {
                ContactThreadMessage(it.id, it, ContactMessageDelivery.Sent)
            }
        }
        if (latestCachedMessages != null) hasLoaded = true
        val mustRefresh = retryKey != lastHandledRetryKey ||
            foregroundRefreshKey != lastHandledForegroundRefreshKey ||
            externalRefreshKey != lastHandledExternalRefreshKey
        lastHandledRetryKey = retryKey
        lastHandledForegroundRefreshKey = foregroundRefreshKey
        lastHandledExternalRefreshKey = externalRefreshKey
        if (!mustRefresh &&
            latestCachedMessages != null &&
            localCache?.isFresh(latestCachedMessages.savedAtMillis) == true
        ) {
            isLoading = false
            return@LaunchedEffect
        }

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
            initialLoadFailed = false
            initialLoadWasNetworkFailure = false
            loadError = null
            networkRetryAction = null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (NetworkErrors.isNetworkFailure(exception)) {
                errorTitle = NetworkErrors.TITLE
                errorMessage = NetworkErrors.MESSAGE
                networkRetryAction = { retryKey++ }
            }
            if (hasLoaded) {
                loadError = if (NetworkErrors.isNetworkFailure(exception)) {
                    NetworkErrors.DISPLAY_TEXT
                } else {
                    contactConversationRefreshFallback
                }
            } else {
                initialLoadFailed = true
                initialLoadWasNetworkFailure =
                    NetworkErrors.isNetworkFailure(exception)
                showContactLoadError(
                    exception = exception,
                    contactTitle = context.getString(R.string.contact_us),
                    fallback = contactLoadRetryFailed
                ) { title, body ->
                    errorTitle = title
                    errorMessage = body
                }
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

    fun reportFailure(
        exception: Exception,
        fallback: String,
        retry: (() -> Unit)? = null
    ) {
        val networkFailure = NetworkErrors.isNetworkFailure(exception)
        errorTitle = if (networkFailure) NetworkErrors.TITLE else contactUsTitle
        errorMessage = if (networkFailure) NetworkErrors.MESSAGE else fallback
        networkRetryAction = retry.takeIf { networkFailure }
    }

    suspend fun retryFailedContactMessage(
        localId: String,
        text: String,
        imageUrl: String?,
        localCreatedAt: String
    ) {
        if (isSending) return
        isSending = true
        try {
            val localCreatedAtMillis = localCreatedAt.toLongOrNull()
            val refreshedMessages = onLoadMessages(conversationId)
            val alreadyDelivered = refreshedMessages.firstOrNull {
                it.sender.equals("USER", ignoreCase = true) &&
                    it.message == text &&
                    it.imageUrl == imageUrl &&
                    localCreatedAtMillis != null &&
                    runCatching {
                        kotlin.math.abs(
                            java.time.Instant.parse(it.createdAt).toEpochMilli() -
                                localCreatedAtMillis
                        ) <= CONTACT_SEND_RECONCILIATION_WINDOW_MILLIS
                    }.getOrDefault(false)
            }
            if (alreadyDelivered != null) {
                messages = messages.map {
                    if (it.localId == localId) {
                        ContactThreadMessage(
                            alreadyDelivered.id,
                            alreadyDelivered,
                            ContactMessageDelivery.Sent
                        )
                    } else {
                        it
                    }
                }
            } else {
                val saved = sendSupportReplyAndClearUnread(context, conversationId) {
                    onSendMessage(conversationId, text, imageUrl)
                }
                messages = messages.map {
                    if (it.localId == localId) {
                        ContactThreadMessage(saved.id, saved, ContactMessageDelivery.Sent)
                    } else {
                        it
                    }
                }.sortedBy { it.message.createdAt }
            }
            errorTitle = null
            errorMessage = null
            networkRetryAction = null
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            reportFailure(exception, contactMessageFailed) {
                scope.launch {
                    retryFailedContactMessage(localId, text, null, localCreatedAt)
                }
            }
        } finally {
            isSending = false
        }
    }

    suspend fun sendTextMessage() {
        val trimmed = messageText.trim()
        if (trimmed.isEmpty() || isSending) return
        if (conversation?.status.equals("CLOSED", ignoreCase = true)) {
            errorTitle = contactUsTitle
            errorMessage = contactConversationClosedMessage
            retryKey++
            return
        }
        val localId = "local-${UUID.randomUUID()}"
        val localCreatedAt = System.currentTimeMillis().toString()
        val localMessage = ContactMessage(
            id = localId,
            conversationId = conversationId,
            sender = "USER",
            message = trimmed,
            imageUrl = null,
            createdAt = localCreatedAt
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
            reportFailure(exception, contactMessageFailed) {
                scope.launch {
                    retryFailedContactMessage(
                        localId,
                        trimmed,
                        null,
                        localCreatedAt
                    )
                }
            }
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
        uploadProgress = String.format(
            java.util.Locale.getDefault(),
            contactPrepareImagePattern,
            index + 1,
            total
        )
        var localId: String? = null
        var localCreatedAt: String? = null
        var uploadedImageUrl: String? = null
        try {
            val currentConversation = onLoadConversation(conversationId)
            conversation = currentConversation
            check(currentConversation.status.equals("OPEN", ignoreCase = true)) {
                "This conversation has been closed."
            }
            val bytes = withContext(Dispatchers.Default) { bitmap.toContactWebp() }
            require(bytes.size <= MAX_CONTACT_IMAGE_BYTES) {
                contactImageSizeLimit
            }
            uploadProgress = String.format(
                java.util.Locale.getDefault(),
                contactUploadImagePattern,
                index + 1,
                total
            )
            val imageUrl = onUploadImage(bytes)
            uploadedImageUrl = imageUrl
            val localMessageId = "local-${UUID.randomUUID()}"
            localId = localMessageId
            val createdAt = System.currentTimeMillis().toString()
            localCreatedAt = createdAt
            val localMessage = ContactMessage(
                id = localMessageId,
                conversationId = conversationId,
                sender = "USER",
                message = "",
                imageUrl = imageUrl,
                createdAt = createdAt
            )
            messages = messages + ContactThreadMessage(
                localMessageId,
                localMessage,
                ContactMessageDelivery.Sending
            )
            uploadProgress = String.format(
                java.util.Locale.getDefault(),
                contactSaveImagePattern,
                index + 1,
                total
            )
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
            reportFailure(exception, contactImageFailed) {
                scope.launch {
                    val failedId = localId
                    val failedCreatedAt = localCreatedAt
                    val imageUrl = uploadedImageUrl
                    if (failedId != null &&
                        failedCreatedAt != null &&
                        imageUrl != null
                    ) {
                        retryFailedContactMessage(
                            failedId,
                            "",
                            imageUrl,
                            failedCreatedAt
                        )
                    } else {
                        uploadBitmap(bitmap, index, total, batchItem)
                    }
                }
            }
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
                        reportFailure(exception, contactImageReadFailed)
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
                reportFailure(exception, cameraOpenFailed)
            }
        } else {
            errorTitle = contactCameraPermissionTitle
            errorMessage = contactCameraPermissionMessage
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
                                String.format(
                                    java.util.Locale.getDefault(),
                                    contactPrepareImagePattern,
                                    index + 1,
                                    selectedUris.size
                                )
                            uploadBitmap(
                                context.decodeContactImage(uri),
                                index,
                                selectedUris.size,
                                batchItem = true
                            )
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            reportFailure(exception, contactImageBatchFailed)
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
            text = conversation?.subject ?: conversationLabel,
            color = KolamGold,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        val isClosed = conversation?.status?.equals("CLOSED", ignoreCase = true) == true
        if (isClosed) {
            Text(
                contactConversationClosed,
                color = KolamMutedText,
                fontSize = 15.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        loadError?.let { message ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (message) {
                        NetworkErrors.DISPLAY_TEXT ->
                            stringResource(R.string.contact_no_internet_saved)
                        else -> message
                    },
                    color = KolamContainerSecondary,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    onClick = { retryKey++ },
                    colors = kolamStandardButtonColors()
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
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
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = KolamCoolSilver
                        ),
                        border = BorderStroke(1.dp, KolamSilverBorder)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(24.dp),
                            color = KolamBronze
                        )
                    }
                }
            } else if (!hasLoaded && (errorMessage != null || initialLoadFailed)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
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
                                if (errorTitle == NetworkErrors.TITLE ||
                                    (errorTitle == null && initialLoadWasNetworkFailure)
                                ) {
                                    stringResource(R.string.network_error_message)
                                } else {
                                    contactConversationLoadFailed
                                },
                                color = KolamContainerSecondary
                            )
                            TextButton(
                                onClick = { retryKey++ },
                                colors = kolamStandardButtonColors()
                            ) { Text(stringResource(R.string.retry)) }
                        }
                    }
                }
            } else if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = KolamCoolSilver
                        ),
                        border = BorderStroke(1.dp, KolamSilverBorder)
                    ) {
                        Text(
                            stringResource(R.string.no_messages_in_conversation),
                            modifier = Modifier.padding(16.dp),
                            color = KolamContainerSecondary
                        )
                    }
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
                            onImageClick = { imageViewerUrl = it },
                            localCache = localCache
                        )
                    }
                }
            }
        }
        if (!isClosed && conversation != null) {
            uploadProgress?.let {
                Text(
                    it,
                    color = KolamContainerSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        KolamCoolSilver,
                        RoundedCornerShape(28.dp)
                    )
                    .border(
                        BorderStroke(1.dp, KolamSilverBorder),
                        RoundedCornerShape(28.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showAttachmentOptions = true },
                    enabled = !isSending && !isImageBatchActive,
                    modifier = Modifier.size(44.dp),
                    colors = kolamStandardButtonColors()
                ) {
                    Text("+", fontSize = 26.sp, color = KolamButtonText)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 10.dp)
                ) {
                    if (messageText.isEmpty()) {
                        Text(stringResource(R.string.type_message), color = KolamContainerSecondary)
                    }
                    BasicTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        enabled = !isSending && !isImageBatchActive,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = context.getString(R.string.message_input) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = KolamText,
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
                        .semantics { contentDescription = context.getString(R.string.send_message) },
                    colors = kolamStandardButtonColors()
                ) {
                    if (isSending && uploadProgress == null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = KolamButtonText
                        )
                    } else {
                        Text("➤", fontSize = 20.sp, color = KolamButtonText)
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

    if (errorTitle == NetworkErrors.TITLE &&
        errorMessage != null &&
        networkRetryAction != null
    ) {
        NetworkRetryDialog(
            onRetry = {
                val retry = networkRetryAction
                errorTitle = null
                errorMessage = null
                networkRetryAction = null
                retry?.invoke()
            },
            onClose = {
                errorTitle = null
                errorMessage = null
                networkRetryAction = null
            }
        )
    } else if (errorTitle != null && errorMessage != null) {
        AlertDialog(
            onDismissRequest = {
                errorTitle = null
                errorMessage = null
            },
            title = {
                Text(
                    if (errorTitle == NetworkErrors.TITLE) {
                        stringResource(R.string.network_error_title)
                    } else {
                        errorTitle.orEmpty()
                    }
                )
            },
            text = { Text(errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        errorTitle = null
                        errorMessage = null
                    },
                    colors = kolamStandardButtonColors()
                ) { Text(stringResource(R.string.ok)) }
            },
            containerColor = KolamCoolSilver
        )
    }

    imageViewerUrl?.let { url ->
        ContactImageViewer(url = url, onClose = { imageViewerUrl = null })
    }
}

@Composable
private fun ContactMessageBubble(
    item: ContactThreadMessage,
    onImageClick: (String) -> Unit,
    localCache: ContactLocalCache?
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
                containerColor = KolamCoolSilver
            ),
            border = BorderStroke(1.dp, KolamSilverBorder)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    text = if (isUserMessage) {
                        stringResource(R.string.you)
                    } else {
                        stringResource(R.string.support_sender_label)
                    },
                    color = KolamText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                if (item.message.message.isNotBlank()) {
                    Text(item.message.message, color = KolamText, fontSize = 16.sp)
                }
                item.message.imageUrl?.let { url ->
                    ContactMessageImage(
                        url = url,
                        onClick = { onImageClick(url) },
                        localCache = localCache
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        item.message.createdAt,
                        color = KolamContainerSecondary,
                        fontSize = 11.sp
                    )
                    if (isUserMessage) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            when (item.delivery) {
                                ContactMessageDelivery.Sending -> stringResource(R.string.message_sending)
                                ContactMessageDelivery.Failed -> stringResource(R.string.message_not_sent)
                                ContactMessageDelivery.Sent -> stringResource(R.string.message_sent)
                            },
                            color = if (item.delivery == ContactMessageDelivery.Failed) {
                                KolamError
                            } else {
                                KolamContainerSecondary
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
    },
    retryImage: suspend (String) -> ImageBitmap? = { imageUrl ->
        withContext(Dispatchers.IO) { loadThumbnail(imageUrl) }
    },
    localCache: ContactLocalCache? = null
) {
    var retryAttempt by remember(url) { mutableIntStateOf(0) }
    val imageState by produceState<ContactMessageImageState>(
        initialValue = ContactMessageImageState.Loading,
        url,
        retryAttempt,
        localCache
    ) {
        value = try {
            val image = if (localCache != null) {
                localCache.loadImage(url, retry = retryAttempt > 0)
            } else if (retryAttempt > 0) {
                retryImage(url)
            } else {
                loadImage(url)
            }
            image?.let(ContactMessageImageState::Loaded)
                ?: ContactMessageImageState.Unavailable
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ContactMessageImageState.Unavailable
        }
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
                Text(stringResource(R.string.loading_image), color = Color.White.copy(alpha = 0.75f))
            is ContactMessageImageState.Loaded ->
                Image(
                    bitmap = state.bitmap,
                    contentDescription = stringResource(R.string.contact_conversation_image),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            ContactMessageImageState.Unavailable -> Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(stringResource(R.string.image_unavailable), color = Color.White.copy(alpha = 0.75f))
                TextButton(
                    onClick = { retryAttempt++ },
                    colors = kolamStandardButtonColors()
                ) {
                    Text(stringResource(R.string.retry_image))
                }
            }
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
                    contentDescription = stringResource(R.string.full_screen_contact_image),
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
            ) { Text(stringResource(R.string.close)) }
        }
    }
}

private fun showContactLoadError(
    exception: Exception,
    contactTitle: String,
    fallback: String,
    showError: (String, String) -> Unit
) {
    if (NetworkErrors.isNetworkFailure(exception)) {
        showError(NetworkErrors.TITLE, NetworkErrors.MESSAGE)
    } else {
        showError(contactTitle, fallback)
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
        title = { Text(stringResource(R.string.add_attachment)) },
        text = {
            Column {
                Text(stringResource(R.string.choose_image_source))
                TextButton(
                    onClick = onCamera,
                    modifier = Modifier.fillMaxWidth(),
                    colors = kolamStandardButtonColors()
                ) { Text(stringResource(R.string.camera)) }
                TextButton(
                    onClick = onGallery,
                    modifier = Modifier.fillMaxWidth(),
                    colors = kolamStandardButtonColors()
                ) { Text(stringResource(R.string.photo_gallery)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = kolamStandardButtonColors()) {
                Text(stringResource(R.string.cancel))
            }
        },
        dismissButton = null,
        containerColor = KolamCoolSilver
    )
}
