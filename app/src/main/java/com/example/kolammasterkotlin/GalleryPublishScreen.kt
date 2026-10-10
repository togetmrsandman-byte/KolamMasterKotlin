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

private val publishGold = Color(0xFFFFC928)
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
            message = "You can add up to $MAX_PUBLISH_IMAGES images."
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
                        ?: "This image could not be converted. Please choose a smaller image and try again."
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
            message = "The captured photo could not be located. Please take it again."
            return@rememberLauncherForActivityResult
        }
        if (captured) {
            addPhotos(listOf(uri)) {
                context.contentResolver.delete(uri, null, null)
            }
        } else {
            message = "Camera capture cancelled."
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
                message = exception.message ?: "The camera could not be opened."
            }
        } else {
            photoPickerError = "Allow camera access to take a photo."
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
            title = { Text("Photo unavailable") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { photoPickerError = null }) { Text("OK") }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            isSubmitted -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xF2293139),
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
                                "Submitted for Review",
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Text(
                                text = "Thank you for submitting your kolam!\n\n" +
                                    "Your kolam is now waiting for admin approval. Once approved, " +
                                    "it will appear in the Community section for everyone to enjoy.\n\n" +
                                    "We’ll notify you when your kolam is approved and ready to view.\n\n" +
                                    "Thank you for being part of our community!",
                                color = Color.White.copy(alpha = 0.92f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
                Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
                    Text("Done")
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
                    "Share your kolam",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    lesson.lessonName,
                    color = Color.White.copy(alpha = 0.75f),
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
                    label = { Text("Creator name") },
                    placeholder = { Text("Enter your name") },
                    singleLine = true,
                    colors = publishTextFieldColors()
                )
            }
            item {
                OutlinedTextField(
                    value = creatorEmail,
                    onValueChange = {},
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Email") },
                    placeholder = { Text("Email unavailable") },
                    readOnly = true,
                    minLines = 1,
                    maxLines = 3,
                    textStyle = TextStyle(color = Color(0xFFB0B6BC)),
                    supportingText = if (creatorEmail.isBlank()) {
                        { Text("Email is unavailable for this account.") }
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
                                    ?: "Camera permission could not be requested."
                            }
                        },
                        enabled = !isBusy && !isPreparingInitialImages &&
                            photos.size + convertingPhotos.size < MAX_PUBLISH_IMAGES,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Take Photo")
                    }
                    Button(
                        onClick = {
                            try {
                                galleryLauncher.launch("image/*")
                            } catch (exception: Exception) {
                                photoPickerError = exception.message
                                    ?: "The gallery could not be opened."
                            }
                        },
                        enabled = !isBusy && !isPreparingInitialImages &&
                            photos.size + convertingPhotos.size < MAX_PUBLISH_IMAGES,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Choose Photo")
                    }
                }
                Text(
                    "Choose up to five images. Each upload must be under 10 MiB.",
                    color = Color.White.copy(alpha = 0.68f),
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
                    "A rewarded ad is required to submit this kolam for admin review.",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp
                )
            }
            item {
                if (!isBusy) {
                    message?.let {
                        Text(
                            it,
                            color = Color(0xFFFFB4AB),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                Button(
                    onClick = {
                        val currentAccount = account
                        if (currentAccount == null || currentAccount.isGuest) {
                            message = "Sign in with Google before publishing."
                            return@Button
                        }
                        if (processingCount > 0) {
                            message = "Please wait for image conversion to finish."
                            return@Button
                        }
                        val missingFields = buildList {
                            if (creatorName.isBlank()) add("enter a creator name")
                            if (photos.none { it.webpBytes.isNotEmpty() }) {
                                add("add at least one readable photo")
                            }
                        }
                        if (missingFields.isNotEmpty()) {
                            message = "Please ${missingFields.joinToString(", ")}."
                            return@Button
                        }
                        if (lesson.category.isNullOrBlank() || lesson.difficulty.isNullOrBlank()) {
                            message = "Required lesson details are unavailable. This kolam cannot be submitted yet."
                            return@Button
                        }
                        isBusy = true
                        message = "Loading rewarded ad..."
                        scope.launch {
                            try {
                                val activity = context.findActivity()
                                    ?: throw IllegalStateException("Unable to show a rewarded ad right now.")
                                showPublishRewardedAd(
                                    activity = activity,
                                    onRewardEarned = {
                                        scope.launch {
                                            try {
                                                message = "Uploading photos and submitting..."
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
                                                    "The kolam could not be submitted. Please try again."
                                                )
                                                isBusy = false
                                            }
                                        }
                                    },
                                    onDismissedWithoutReward = {
                                        isBusy = false
                                        message = "No reward was earned, so the kolam was not submitted."
                                    },
                                    onFailed = { adError ->
                                        isBusy = false
                                        message = "The rewarded ad could not be shown (${adError.code}). Please try again."
                                    }
                                )
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (exception: Exception) {
                                isBusy = false
                                message = NetworkErrors.messageFor(
                                    exception,
                                    "The kolam could not be prepared for submission."
                                )
                            }
                        }
                    },
                    enabled = !isBusy && !isPreparingInitialImages && processingCount == 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = publishGold,
                        contentColor = Color(0xFF392800)
                    )
                ) {
                    Text(
                        if (isPreparingInitialImages) "Preparing photos..."
                        else "Watch Ad and Submit"
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = onBack,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, publishGold),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = publishGold,
                        disabledContentColor = publishGold.copy(alpha = 0.45f)
                    )
                ) {
                    Text("Cancel")
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
                        .background(Color(0xFF293139))
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
                        text = message ?: "Publishing...",
                        modifier = Modifier.weight(1f),
                        color = Color.White,
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
            .background(Color.White.copy(alpha = 0.08f))
            .padding(8.dp)
    ) {
        Image(
            bitmap = photo.preview,
            contentDescription = "Selected kolam photo ${index + 1}",
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
                containerColor = Color(0xFF315A78),
                contentColor = Color.White
            )
        ) {
            Text("Remove")
        }
    }
}

@Composable
private fun ConvertingPhotoPreview() {
    Column(
        modifier = Modifier
            .size(124.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
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
            text = "Converting image…",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun publishTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.55f),
    focusedContainerColor = Color(0xFF293139),
    unfocusedContainerColor = Color(0xFF293139),
    disabledContainerColor = Color(0xFF293139),
    focusedLabelColor = publishGold,
    unfocusedLabelColor = Color.White.copy(alpha = 0.72f),
    disabledLabelColor = Color.White.copy(alpha = 0.5f),
    focusedPlaceholderColor = Color.White.copy(alpha = 0.62f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.62f),
    disabledPlaceholderColor = Color.White.copy(alpha = 0.4f),
    focusedBorderColor = publishGold,
    unfocusedBorderColor = Color.White.copy(alpha = 0.45f),
    disabledBorderColor = Color.White.copy(alpha = 0.25f),
    cursorColor = publishGold
)

@Composable
private fun publishEmailTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color(0xFFB0B6BC),
    unfocusedTextColor = Color(0xFFB0B6BC),
    focusedContainerColor = Color(0xFF293139),
    unfocusedContainerColor = Color(0xFF293139),
    focusedLabelColor = Color(0xFF929AA2),
    unfocusedLabelColor = Color(0xFF929AA2),
    focusedPlaceholderColor = Color(0xFF929AA2),
    unfocusedPlaceholderColor = Color(0xFF929AA2),
    focusedBorderColor = Color(0xFF68717A),
    unfocusedBorderColor = Color(0xFF68717A),
    cursorColor = Color(0xFF929AA2)
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
