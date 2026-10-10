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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface CommunityImageState {
    data object Loading : CommunityImageState
    data class Loaded(val bitmap: ImageBitmap) : CommunityImageState
    data object Failed : CommunityImageState
}

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
        }
    }

    when {
        isLoading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        errorMessage != null -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(errorMessage, color = Color.White, fontSize = 16.sp)
            Button(onClick = onRefresh, modifier = Modifier.padding(top = 16.dp)) {
                Text("Try again")
            }
        }
        submissions.isEmpty() -> Column(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("No approved kolams yet.", color = Color.White, fontSize = 16.sp)
            Button(onClick = onRefresh, modifier = Modifier.padding(top = 16.dp)) {
                Text("Refresh")
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
                    Text("Refresh community")
                }
            }
            items(galleryItems, key = { it.image.id }) { item ->
                CommunityImageCard(
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

    selectedImage?.let { selection ->
        Dialog(
            onDismissRequest = { selectedImage = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF101418))
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF171D22)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = selection.bitmap,
                        contentDescription = "Selected community kolam",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Text(
                    text = "Submitted by: ${selection.item.creatorName}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                selection.item.lessonId?.let { id ->
                    Text(
                        text = "Lesson ID: $id",
                        color = Color.White.copy(alpha = 0.78f),
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
                        color = Color(0xFFFFB4AB),
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
                                        "The lesson catalogue is still loading. Please try again shortly."
                                    } else {
                                        "The lesson associated with this kolam could not be found."
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
                        Text("Open Lesson")
                    }
                    OutlinedButton(
                        onClick = {
                            selectedImage = null
                            selectedLessonError = null
                        },
                        modifier = Modifier.weight(1f),
                        border = BorderStroke(1.dp, Color(0xFFFFC928)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Cancel", color = Color(0xFFFFC928))
                    }
                }
            }
        }
    }

    if (selectedImage == null) {
        lessonOpenError?.let { message ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = onDismissLessonOpenError,
                title = { Text("Lesson unavailable") },
                text = { Text(message) },
                confirmButton = {
                    Button(onClick = onDismissLessonOpenError) { Text("OK") }
                }
            )
        }
    }
}

@Composable
private fun CommunityImageCard(
    creatorName: String,
    country: String?,
    lessonId: String?,
    image: CommunitySubmissionImage,
    onImageLoaded: (ImageBitmap) -> Unit
) {
    val imageState by produceState<CommunityImageState>(
        initialValue = CommunityImageState.Loading,
        image.imageUrl
    ) {
        value = withContext(Dispatchers.IO) { loadThumbnail(image.imageUrl) }
            ?.let(CommunityImageState::Loaded)
            ?: CommunityImageState.Failed
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                when (val state = imageState) {
                    CommunityImageState.Loading -> CircularProgressIndicator(color = Color.White)
                    CommunityImageState.Failed -> Text(
                        "Image could not be loaded.",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp)
                    )
                    is CommunityImageState.Loaded -> Image(
                        bitmap = state.bitmap,
                        contentDescription = "Kolam shared by $creatorName",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onImageLoaded(state.bitmap) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Submitted by: $creatorName",
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            lessonId?.let { id ->
                Text(
                    text = "Lesson ID: $id",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 3.dp),
                    color = Color.White.copy(alpha = 0.82f),
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
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
