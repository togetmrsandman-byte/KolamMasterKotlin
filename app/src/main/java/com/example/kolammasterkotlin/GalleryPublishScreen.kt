package com.kolammaster.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.core.content.FileProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardItem
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.kolammaster.app.auth.SupabaseAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private val publishGold = KolamBronze
private const val MAX_PUBLISH_IMAGES = 5

@Composable
internal fun GalleryPublishScreen(
    lesson: PublishLessonDetails,
    initialImageUris: List<Uri>,
    temporaryImageUris: Set<Uri>,
    account: SupabaseAccount?,
    repository: GalleryPublishRepository,
    onBack: () -> Unit,
    onPublishSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxImagesMessage = stringResource(R.string.publish_max_images, MAX_PUBLISH_IMAGES)
    val imageConversionFailedMessage = stringResource(R.string.image_conversion_failed)
    val capturedPhotoMissingMessage = stringResource(R.string.captured_photo_missing)
    val cameraCaptureCancelledMessage = stringResource(R.string.camera_capture_cancelled)
    val cameraPermissionAccessMessage = stringResource(R.string.camera_permission_access)
    val cameraPermissionRequestFailedMessage =
        stringResource(R.string.camera_permission_request_failed)
    val galleryOpenFailedMessage = stringResource(R.string.gallery_open_failed)
    val cameraOpenFailedMessage = stringResource(R.string.camera_open_failed)
    val unableShowRewardedAdMessage = stringResource(R.string.unable_show_rewarded_ad)
    val publishSubmitFailedMessage = stringResource(R.string.kolam_submit_failed)
    val noRewardMessage = stringResource(R.string.no_reward_not_submitted)
    val rewardedAdFailedPattern = stringResource(R.string.rewarded_ad_failed)
    val preparePublishFailedMessage = stringResource(R.string.prepare_publish_failed)
    val submittedPublishingMessage = stringResource(R.string.uploading_photos)
    val loadAdMessage = stringResource(R.string.loading_rewarded_ad)
    val publishSignInMessage = stringResource(R.string.publish_signin_required)
    val waitConversionMessage = stringResource(R.string.publish_wait_conversion)
    val creatorNameRequiredMessage = stringResource(R.string.publish_enter_creator_name)
    val photoRequiredMessage = stringResource(R.string.publish_add_photo)
    val missingLessonDetailsMessage = stringResource(R.string.publish_missing_lesson_details)
    val publishSuccessMessage = stringResource(R.string.publish_success_message)
    val photos = remember { mutableStateListOf<GalleryPublishPhoto>() }
    val convertingPhotos = remember { mutableStateListOf<Uri>() }
    val processingUris = remember { mutableSetOf<String>() }
    val completedSourceUris = remember { mutableSetOf<String>() }
    val conversionMutex = remember { Mutex() }
    var processingCount by remember { mutableIntStateOf(0) }
    var temporaryCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var processingJobs by remember { mutableStateOf<Map<String, Job>>(emptyMap()) }
    var isPreparingInitialImages by remember(initialImageUris) {
        mutableStateOf(initialImageUris.isNotEmpty())
    }
    var creatorName by remember(account?.id) { mutableStateOf(account?.name.orEmpty()) }
    val creatorEmail = creatorEmailForPublish(account).orEmpty()
    var isBusy by remember { mutableStateOf(false) }
    var isSubmitted by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var photoPickerError by remember { mutableStateOf<String?>(null) }
    fun addPhotos(uris: List<Uri>, onFinished: (() -> Unit)? = null) {
        if (uris.isEmpty()) {
            onFinished?.invoke()
            return
        }
        message = null
        val distinctUris = uris.distinctBy(Uri::toString).filter { uri ->
            uri.toString() !in completedSourceUris &&
                processingUris.add(uri.toString())
        }
        val availableSlots = (MAX_PUBLISH_IMAGES - photos.size - convertingPhotos.size)
            .coerceAtLeast(0)
        val accepted = distinctUris.take(availableSlots)
        val rejected = distinctUris.drop(availableSlots)
        rejected.forEach { processingUris.remove(it.toString()) }
        if (rejected.isNotEmpty()) {
            message = maxImagesMessage
        }
        if (accepted.isEmpty()) {
            onFinished?.invoke()
            return
        }
        processingCount += accepted.size
        convertingPhotos.addAll(accepted)
        var finishedCount = 0
        accepted.forEach { uri ->
            val key = uri.toString()
            val job = scope.launch {
                try {
                    val photo = conversionMutex.withLock {
                        context.prepareGalleryPublishPhoto(uri)
                    }
                    photos.add(photo)
                    completedSourceUris.add(key)
                    message = null
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e("GalleryPublishImage", "Image conversion failed.", exception)
                    message = exception.message?.takeIf(String::isNotBlank)
                        ?: imageConversionFailedMessage
                } finally {
                    processingUris.remove(key)
                    convertingPhotos.remove(uri)
                    processingCount = (processingCount - 1).coerceAtLeast(0)
                    processingJobs = processingJobs - key
                    finishedCount += 1
                    if (finishedCount == accepted.size) onFinished?.invoke()
                }
            }
            processingJobs = processingJobs + (key to job)
        }
    }

    LaunchedEffect(initialImageUris, temporaryImageUris) {
        if (initialImageUris.isNotEmpty()) {
            addPhotos(initialImageUris) {
                temporaryImageUris.forEach { uri ->
                    context.contentResolver.delete(uri, null, null)
                }
                isPreparingInitialImages = false
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            addPhotos(uris)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = temporaryCameraUri
        temporaryCameraUri = null
        if (uri == null) {
            message = capturedPhotoMissingMessage
            return@rememberLauncherForActivityResult
        }
        if (captured) {
            addPhotos(listOf(uri)) {
                context.contentResolver.delete(uri, null, null)
            }
        } else {
            message = cameraCaptureCancelledMessage
            context.contentResolver.delete(uri, null, null)
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                val directory = File(context.cacheDir, "publish-images")
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Could not prepare a camera photo.")
                }
                val imageFile = File.createTempFile("kolam-", ".jpg", directory)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.contact-uploads",
                    imageFile
                )
                temporaryCameraUri = uri
                cameraLauncher.launch(uri)
            } catch (exception: Exception) {
                temporaryCameraUri?.let { context.contentResolver.delete(it, null, null) }
                temporaryCameraUri = null
                message = exception.message ?: cameraOpenFailedMessage
            }
        } else {
            photoPickerError = cameraPermissionAccessMessage
        }
    }

    BackHandler(enabled = isBusy, onBack = {})

    DisposableEffect(Unit) {
        onDispose {
            processingJobs.values.forEach(Job::cancel)
            temporaryCameraUri?.let { uri -> context.contentResolver.delete(uri, null, null) }
        }
    }

    photoPickerError?.let { error ->
        AlertDialog(
            onDismissRequest = { photoPickerError = null },
            title = { Text(stringResource(R.string.publish_photo_unavailable)) },
            text = { Text(error) },
            confirmButton = {
                TextButton(
                    onClick = { photoPickerError = null },
                    colors = kolamStandardButtonColors()
                ) { Text(stringResource(R.string.ok)) }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isSubmitted) Color.Transparent else KolamBackground)
    ) {
        when {
            isSubmitted -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = KolamSurface,
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, publishGold.copy(alpha = 0.82f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                stringResource(R.string.submitted_for_review),
                                color = KolamGold,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Text(
                                text = publishSuccessMessage,
                                color = KolamMutedText,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
            else -> LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    stringResource(R.string.share_your_kolam),
                    color = KolamGold,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    lesson.lessonName,
                    color = KolamMutedText,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            item {
                OutlinedTextField(
                    value = creatorName,
                    onValueChange = {
                        creatorName = it
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.creator_name)) },
                    placeholder = { Text(stringResource(R.string.enter_your_name)) },
                    singleLine = true,
                    colors = publishTextFieldColors()
                )
            }
            item {
                OutlinedTextField(
                    value = creatorEmail,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.email)) },
                    placeholder = { Text(stringResource(R.string.email_unavailable)) },
                    readOnly = true,
                    minLines = 1,
                    maxLines = 3,
                    textStyle = TextStyle(color = KolamMutedText),
                    supportingText = if (creatorEmail.isBlank()) {
                        { Text(stringResource(R.string.email_unavailable_account)) }
                    } else {
                        null
                    },
                    colors = publishEmailTextFieldColors()
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            try {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } catch (exception: Exception) {
                                photoPickerError = exception.message
                                    ?: cameraPermissionRequestFailedMessage
                            }
                        },
                        enabled = !isBusy && !isPreparingInitialImages &&
                            photos.size + convertingPhotos.size < MAX_PUBLISH_IMAGES,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.take_photo))
                    }
                    Button(
                        onClick = {
                            try {
                                galleryLauncher.launch("image/*")
                            } catch (exception: Exception) {
                                photoPickerError = exception.message
                                    ?: galleryOpenFailedMessage
                            }
                        },
                        enabled = !isBusy && !isPreparingInitialImages &&
                            photos.size + convertingPhotos.size < MAX_PUBLISH_IMAGES,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.choose_photo))
                    }
                }
                Text(
                    stringResource(R.string.publish_image_limits),
                    color = KolamMutedText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            if (convertingPhotos.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(convertingPhotos, key = Uri::toString) {
                            ConvertingPhotoPreview()
                        }
                    }
                }
            }
            if (photos.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(photos) { index, photo ->
                            PublishPhotoPreview(
                                photo = photo,
                                index = index,
                                enabled = !isBusy && !isPreparingInitialImages,
                                onRemove = { photos.removeAt(index) }
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.publish_rewarded_ad_required),
                    color = KolamMutedText,
                    fontSize = 13.sp
                )
            }
            item {
                if (!isBusy) {
                    message?.let {
                        Text(
                            it,
                            color = KolamError,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                Button(
                    onClick = {
                        val currentAccount = account
                        if (currentAccount == null || currentAccount.isGuest) {
                            message = publishSignInMessage
                            return@Button
                        }
                        if (processingCount > 0) {
                            message = waitConversionMessage
                            return@Button
                        }
                        val missingFields = buildList {
                            if (creatorName.isBlank()) add(creatorNameRequiredMessage)
                            if (photos.none { it.webpBytes.isNotEmpty() }) {
                                add(photoRequiredMessage)
                            }
                        }
                        if (missingFields.isNotEmpty()) {
                            message = missingFields.joinToString(" ")
                            return@Button
                        }
                        if (lesson.category.isNullOrBlank() || lesson.difficulty.isNullOrBlank()) {
                            message = missingLessonDetailsMessage
                            return@Button
                        }
                        isBusy = true
                        message = loadAdMessage
                        scope.launch {
                            try {
                                val activity = context.findActivity()
                                    ?: throw IllegalStateException(unableShowRewardedAdMessage)
                                showPublishRewardedAd(
                                    activity = activity,
                                    onRewardEarned = {
                                        scope.launch {
                                            try {
                                                message = submittedPublishingMessage
                                                repository.submit(
                                                    currentAccount,
                                                    lesson,
                                                    creatorName,
                                                    creatorEmailForPublish(currentAccount),
                                                    photos.map(GalleryPublishPhoto::webpBytes)
                                                )
                                                isSubmitted = true
                                                onPublishSuccess()
                                                message = null
                                            } catch (exception: CancellationException) {
                                                throw exception
                                            } catch (exception: Exception) {
                                                message = NetworkErrors.messageFor(
                                                    exception,
                                                    publishSubmitFailedMessage
                                                )
                                                isBusy = false
                                            }
                                        }
                                    },
                                    onDismissedWithoutReward = {
                                        isBusy = false
                                        message = noRewardMessage
                                    },
                                    onFailed = { adError ->
                                        isBusy = false
                                        message = String.format(
                                            java.util.Locale.getDefault(),
                                            rewardedAdFailedPattern,
                                            adError.code
                                        )
                                    }
                                )
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (exception: Exception) {
                                isBusy = false
                                message = NetworkErrors.messageFor(
                                    exception,
                                    preparePublishFailedMessage
                                )
                            }
                        }
                    },
                    enabled = !isBusy && !isPreparingInitialImages && processingCount == 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KolamButtonYellow,
                        contentColor = KolamButtonText
                    )
                ) {
                    Text(
                        stringResource(
                            if (isPreparingInitialImages) R.string.preparing_photos
                            else R.string.watch_ad_submit
                        )
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, KolamButtonYellow),
                    colors = kolamStandardButtonColors()
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
        if (isBusy && !isSubmitted) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(KolamElevated)
                        .border(
                            width = 1.dp,
                            color = publishGold,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(30.dp),
                        color = publishGold,
                        strokeWidth = 3.dp
                    )
                    Text(
                        text = message ?: stringResource(R.string.publishing),
                        modifier = Modifier.weight(1f),
                        color = KolamText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private data class PublishConfettiPiece(
    val x: Float,
    val phase: Float,
    val speed: Float,
    val widthDp: Float,
    val heightDp: Float,
    val rotation: Float,
    val spin: Float,
    val color: Color,
    val isRibbon: Boolean
)

@Composable
internal fun PublishConfetti(modifier: Modifier = Modifier) {
    val colors = remember {
        listOf(
            Color(0xFFFF5A5F),
            Color(0xFFFFC928),
            Color(0xFF35C6A2),
            Color(0xFF4D9DE0),
            Color(0xFFB56CE2),
            Color(0xFFFF8C42)
        )
    }
    val pieces = remember {
        val random = Random(731)
        List(48) { index ->
            PublishConfettiPiece(
                x = random.nextFloat(),
                phase = (index + random.nextFloat()) / 48f,
                speed = (1 + random.nextInt(3)).toFloat(),
                widthDp = 4f + random.nextFloat() * 5f,
                heightDp = 4f + random.nextFloat() * 7f,
                rotation = random.nextFloat() * 360f,
                spin = random.nextInt(-2, 3) * 360f,
                color = colors[random.nextInt(colors.size)],
                isRibbon = random.nextBoolean()
            )
        }
    }
    val transition = rememberInfiniteTransition(label = "publish confetti")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 44000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "confetti fall progress"
    )

    Canvas(modifier = modifier) {
        pieces.forEach { piece ->
            drawPublishConfettiPiece(piece, progress)
        }
    }
}

private fun DrawScope.drawPublishConfettiPiece(
    piece: PublishConfettiPiece,
    progress: Float
) {
    val width = piece.widthDp.dp.toPx()
    val height = piece.heightDp.dp.toPx()
    val travel = size.height + height * 2f
    val fall = (piece.phase + progress * piece.speed) % 1f
    val center = Offset(
        x = size.width * piece.x +
            sin((progress + piece.phase) * (2f * PI.toFloat())) * size.width * 0.018f,
        y = fall * travel - height
    )

    rotate(
        degrees = piece.rotation + progress * piece.spin,
        pivot = center
    ) {
        if (piece.isRibbon) {
            val path = Path().apply {
                moveTo(center.x - width / 2f, center.y)
                cubicTo(
                    center.x - width / 4f,
                    center.y - height,
                    center.x + width / 4f,
                    center.y + height,
                    center.x + width / 2f,
                    center.y
                )
            }
            drawPath(
                path = path,
                color = piece.color,
                style = Stroke(width = maxOf(1.5f, height * 0.55f), cap = StrokeCap.Round)
            )
        } else {
            drawRoundRect(
                color = piece.color,
                topLeft = Offset(center.x - width / 2f, center.y - height / 2f),
                size = Size(width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.16f)
            )
        }
    }
}

@Composable
private fun PublishPhotoPreview(
    photo: GalleryPublishPhoto,
    index: Int,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier
            .size(124.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KolamElevated)
            .padding(8.dp)
    ) {
        Image(
            bitmap = photo.preview,
            contentDescription = stringResource(R.string.selected_kolam_photo, index + 1),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
        )
        Button(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 4.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = KolamButtonYellow,
                contentColor = KolamButtonText
            )
        ) {
            Text(stringResource(R.string.remove))
        }
    }
}

@Composable
private fun ConvertingPhotoPreview() {
    Column(
        modifier = Modifier
            .size(124.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KolamElevated)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = publishGold,
            strokeWidth = 3.dp
        )
        Text(
            text = stringResource(R.string.converting_image),
            color = KolamText,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun publishTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = KolamText,
    unfocusedTextColor = KolamText,
    disabledTextColor = KolamMutedText,
    focusedContainerColor = KolamElevated,
    unfocusedContainerColor = KolamElevated,
    disabledContainerColor = KolamSurface,
    focusedLabelColor = publishGold,
    unfocusedLabelColor = KolamMutedText,
    disabledLabelColor = KolamMutedText,
    focusedPlaceholderColor = KolamMutedText,
    unfocusedPlaceholderColor = KolamMutedText,
    disabledPlaceholderColor = KolamMutedText,
    focusedBorderColor = publishGold,
    unfocusedBorderColor = KolamBorder,
    disabledBorderColor = KolamBorder,
    cursorColor = publishGold
)

@Composable
private fun publishEmailTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = KolamMutedText,
    unfocusedTextColor = KolamMutedText,
    focusedContainerColor = KolamSurface,
    unfocusedContainerColor = KolamSurface,
    focusedLabelColor = KolamMutedText,
    unfocusedLabelColor = KolamMutedText,
    focusedPlaceholderColor = KolamMutedText,
    unfocusedPlaceholderColor = KolamMutedText,
    focusedBorderColor = KolamBorder,
    unfocusedBorderColor = KolamBorder,
    cursorColor = KolamBronze
)

private fun showPublishRewardedAd(
    activity: Activity,
    onRewardEarned: () -> Unit,
    onDismissedWithoutReward: () -> Unit,
    onFailed: (AdError) -> Unit
) {
    RewardedAd.load(
        activity,
        BuildConfig.PUBLISH_REWARDED_AD_UNIT_ID,
        AdRequest.Builder().build(),
        object : RewardedAdLoadCallback() {
            override fun onAdFailedToLoad(error: LoadAdError) {
                onFailed(error)
            }

            override fun onAdLoaded(rewardedAd: RewardedAd) {
                var rewardEarned = false
                var handled = false
                rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        if (!handled && !rewardEarned) {
                            handled = true
                            onDismissedWithoutReward()
                        }
                    }

                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        if (!handled && !rewardEarned) {
                            handled = true
                            onFailed(error)
                        }
                    }
                }
                rewardedAd.show(activity) { _: RewardItem ->
                    if (!rewardEarned) {
                        rewardEarned = true
                        handled = true
                        onRewardEarned()
                    }
                }
            }
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
