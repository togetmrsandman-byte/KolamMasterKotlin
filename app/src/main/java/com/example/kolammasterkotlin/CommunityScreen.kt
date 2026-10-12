package com.kolammaster.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private sealed interface CommunityImageState {
    data object Loading : CommunityImageState
    data class Loaded(val bitmap: ImageBitmap) : CommunityImageState
    data object Failed : CommunityImageState
}

private const val COMMUNITY_GALLERY_IMAGE_LIMIT = 100

private data class CommunityGalleryItem(
    val creatorName: String,
    val country: String?,
    val lessonId: String?,
    val image: CommunitySubmissionImage
)

private data class SelectedCommunityImage(
    val item: CommunityGalleryItem,
    val bitmap: ImageBitmap
)

@Composable
internal fun CommunityScreen(
    submissions: List<CommunitySubmission>,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    catalogueEntries: List<LessonCatalogueEntry>,
    isCatalogueLoading: Boolean,
    lessonOpenError: String?,
    onOpenLesson: (String) -> Unit,
    onDismissLessonOpenError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val communityCatalogueWaiting = stringResource(R.string.community_catalogue_waiting)
    val communityLessonMissing = stringResource(R.string.community_lesson_missing)
    val context = LocalContext.current.applicationContext
    var selectedImage by remember { mutableStateOf<SelectedCommunityImage?>(null) }
    var selectedLessonError by remember { mutableStateOf<String?>(null) }
    val galleryItems = remember(submissions) {
        submissions.flatMap { submission ->
            submission.images.map { image ->
                CommunityGalleryItem(
                    submission.creatorName,
                    submission.country,
                    submission.lessonId,
                    image
                )
            }
        }.take(COMMUNITY_GALLERY_IMAGE_LIMIT)
    }
    LaunchedEffect(galleryItems, selectedImage?.item?.image, isLoading, errorMessage) {
        val images = galleryItems.map { it.image }
        if (images.isEmpty() && (isLoading || errorMessage != null)) {
            return@LaunchedEffect
        }
        val displayedImage = selectedImage?.item?.image
        CommunityGalleryImageCache.reconcile(context, images, displayedImage)
        CommunityGalleryImageCache.prefetch(context, images)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.community),
                color = KolamGold,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (isLoading && submissions.isNotEmpty()) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(20.dp),
                    color = KolamBronze,
                    strokeWidth = 2.dp
                )
            }
            Button(onClick = onRefresh) {
                Text(stringResource(R.string.refresh))
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                isLoading && submissions.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = KolamBronze)
                }
                errorMessage != null && submissions.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (errorMessage == NetworkErrors.DISPLAY_TEXT) {
                            stringResource(R.string.network_error_message)
                        } else {
                            errorMessage
                        },
                        color = KolamMutedText,
                        fontSize = 16.sp
                    )
                    Button(onClick = onRefresh, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.retry))
                    }
                }
                submissions.isEmpty() -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        stringResource(R.string.no_approved_kolams),
                        color = KolamMutedText,
                        fontSize = 16.sp
                    )
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 4.dp,
                        end = 16.dp,
                        bottom = 20.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (errorMessage != null) {
                        item {
                            Text(
                                text = stringResource(
                                    R.string.community_showing_last_loaded,
                                    if (errorMessage == NetworkErrors.DISPLAY_TEXT) {
                                        stringResource(R.string.network_error_message)
                                    } else {
                                        errorMessage
                                    }
                                ),
                                color = KolamBronze,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                    }
                    items(galleryItems, key = { it.image.id }) { item ->
                        CommunityImageCard(
                            context = context,
                            creatorName = item.creatorName,
                            country = item.country,
                            lessonId = item.lessonId,
                            image = item.image,
                            onImageLoaded = {
                                selectedLessonError = null
                                selectedImage = SelectedCommunityImage(item, it)
                            }
                        )
                    }
                }
            }
        }
    }

    selectedImage?.let { selection ->
        Dialog(
            onDismissRequest = { selectedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(KolamBackground)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(KolamElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = selection.bitmap,
                        contentDescription = stringResource(R.string.selected_community_kolam),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = stringResource(
                        R.string.community_submitted_by,
                        selection.item.creatorName
                    ),
                    color = KolamText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                selection.item.lessonId?.let { id ->
                    Text(
                        text = stringResource(R.string.community_lesson_id, id),
                        color = KolamMutedText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    )
                }
                (selectedLessonError ?: lessonOpenError)?.let { message ->
                    Text(
                        text = message,
                        color = KolamError,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            val lessonId = selection.item.lessonId
                            val lesson = lessonId?.let { id ->
                                catalogueEntries.firstOrNull { it.id == id }
                            }
                            if (lesson == null) {
                                selectedLessonError =
                                    if (isCatalogueLoading) {
                                        communityCatalogueWaiting
                                    } else {
                                        communityLessonMissing
                                    }
                            } else {
                                selectedLessonError = null
                                onDismissLessonOpenError()
                                onOpenLesson(lesson.id)
                                selectedImage = null
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.open_lesson))
                    }
                    OutlinedButton(
                        onClick = {
                            selectedImage = null
                            selectedLessonError = null
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, KolamButtonYellow),
                        shape = RoundedCornerShape(12.dp),
                        colors = kolamStandardButtonColors()
                    ) {
                        Text(stringResource(R.string.cancel), color = KolamButtonText)
                    }
                }
            }
        }
    }

    if (selectedImage == null) {
        lessonOpenError?.let { message ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismissLessonOpenError,
                title = { Text(stringResource(R.string.lesson_unavailable)) },
                text = { Text(message) },
                confirmButton = {
                    Button(onClick = onDismissLessonOpenError) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }
    }
}

@Composable
private fun CommunityImageCard(
    context: android.content.Context,
    creatorName: String,
    country: String?,
    lessonId: String?,
    image: CommunitySubmissionImage,
    onImageLoaded: (ImageBitmap) -> Unit
) {
    var retryAttempt by remember(image.id, image.imageKey, image.imageUrl) {
        mutableIntStateOf(0)
    }
    val imageState by produceState<CommunityImageState>(
        initialValue = CommunityImageState.Loading,
        image.id,
        image.imageKey,
        image.imageUrl,
        retryAttempt
    ) {
        value = CommunityGalleryImageCache.load(context, image)
            ?.let(CommunityImageState::Loaded)
            ?: CommunityImageState.Failed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = KolamSurface)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(KolamElevated),
                contentAlignment = Alignment.Center
            ) {
                when (val state = imageState) {
                    CommunityImageState.Loading -> CircularProgressIndicator(color = KolamBronze)
                    CommunityImageState.Failed -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.image_load_failed),
                            color = KolamMutedText,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        androidx.compose.material3.TextButton(
                            onClick = { retryAttempt++ },
                            colors = kolamStandardButtonColors()
                        ) { Text(stringResource(R.string.retry)) }
                    }
                    is CommunityImageState.Loaded -> Image(
                        bitmap = state.bitmap,
                        contentDescription = stringResource(
                            R.string.community_shared_by,
                            creatorName
                        ),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onImageLoaded(state.bitmap) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.community_submitted_by, creatorName),
                modifier = Modifier.fillMaxWidth(),
                color = KolamText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            lessonId?.let { id ->
                Text(
                    text = stringResource(R.string.community_lesson_id, id),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    color = KolamMutedText,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            country?.let {
                Text(
                    text = it,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    color = KolamMutedText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
