package com.kolammaster.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal data class AssetSetupProgress(
    val completed: Int,
    val total: Int,
    val message: String
)

internal class InitialAssetSetup(
    private val isComplete: () -> Boolean,
    private val markComplete: () -> Unit,
    private val loadLessons: suspend () -> List<LessonCatalogueEntry>,
    private val loadCommunityImages: suspend () -> List<CommunitySubmissionImage>,
    private val cacheLessonThumbnails: suspend (
        List<String>,
        suspend (Int, Int) -> Unit
    ) -> Boolean,
    private val cacheCommunityImages: suspend (
        List<CommunitySubmissionImage>,
        suspend (Int, Int) -> Unit
    ) -> Boolean
) {
    suspend fun run(onProgress: suspend (AssetSetupProgress) -> Unit): Boolean {
        if (isComplete()) return true

        val lessons = loadLessons().take(INITIAL_LESSON_COUNT)
        require(lessons.size == INITIAL_LESSON_COUNT) {
            "The lesson catalogue does not contain the initial $INITIAL_LESSON_COUNT lessons."
        }
        val thumbnailUrls = lessons.map { it.firstThumbnailUrl.orEmpty() }
        require(thumbnailUrls.none(String::isBlank)) {
            "One or more initial lessons do not have a thumbnail URL."
        }
        val uniqueThumbnailUrls = thumbnailUrls.distinct()
        val images = loadCommunityImages().distinctBy {
            listOf(it.id, it.imageKey.orEmpty(), it.imageUrl).joinToString("\u0000")
        }.take(INITIAL_COMMUNITY_IMAGE_LIMIT)
        val total = uniqueThumbnailUrls.size + images.size
        onProgress(AssetSetupProgress(0, total, "Preparing Learn thumbnails"))

        val thumbnailsReady = withTimeoutOrNull(IMAGE_CACHE_TIMEOUT_MILLIS) {
            try {
                cacheLessonThumbnails(uniqueThumbnailUrls) { completed, _ ->
                    onProgress(
                        AssetSetupProgress(
                            completed = completed,
                            total = total,
                            message = "Downloading Learn thumbnails"
                        )
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Some Learn thumbnails could not be cached during onboarding.", exception)
                false
            }
        } ?: false

        val communityReady = withTimeoutOrNull(IMAGE_CACHE_TIMEOUT_MILLIS) {
            try {
                cacheCommunityImages(images) { completed, _ ->
                    onProgress(
                        AssetSetupProgress(
                            completed = uniqueThumbnailUrls.size + completed,
                            total = total,
                            message = "Downloading Community images"
                        )
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Some Community images could not be cached during onboarding.", exception)
                false
            }
        } ?: false

        markComplete()
        onProgress(
            AssetSetupProgress(
                total,
                total,
                if (thumbnailsReady && communityReady) {
                    "Setup complete"
                } else {
                    "Setup complete; missing images can load later"
                }
            )
        )
        return true
    }

    private companion object {
        const val TAG = "InitialAssetSetup"
        const val IMAGE_CACHE_TIMEOUT_MILLIS = 30_000L
        const val INITIAL_LESSON_COUNT = 60
        const val INITIAL_COMMUNITY_IMAGE_LIMIT = 100
    }
}
