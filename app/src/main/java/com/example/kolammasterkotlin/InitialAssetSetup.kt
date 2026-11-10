package com.kolammaster.app

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal data class AssetSetupProgress(
    val completed: Int,
    val total: Int,
    val message: AssetSetupMessage
)

internal enum class AssetSetupMessage {
    PreparingImages,
    PreparingLearnThumbnails,
    DownloadingLearnThumbnails,
    Complete,
    CompleteWithMissingImages
}

internal class InitialAssetSetup(
    private val isComplete: () -> Boolean,
    private val markComplete: () -> Unit,
    private val loadLessons: suspend () -> List<LessonCatalogueEntry>,
    private val cacheLessonThumbnails: suspend (
        List<String>,
        suspend (Int, Int) -> Unit
    ) -> Boolean
) {
    suspend fun run(onProgress: suspend (AssetSetupProgress) -> Unit): Boolean {
        if (isComplete()) return true

        val lessons = try {
            loadLessons().take(INITIAL_LESSON_COUNT)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "Could not load lesson catalogue during onboarding.", exception)
            emptyList()
        }
        val thumbnailUrls = lessons.map { it.firstThumbnailUrl.orEmpty() }
        val uniqueThumbnailUrls = thumbnailUrls.filter { it.isNotBlank() }.distinct()
        val total = uniqueThumbnailUrls.size
        if (total == 0) {
            markComplete()
            onProgress(AssetSetupProgress(0, 0, AssetSetupMessage.Complete))
            return true
        }
        onProgress(
            AssetSetupProgress(0, total, AssetSetupMessage.PreparingLearnThumbnails)
        )

        val thumbnailsReady = withTimeoutOrNull(IMAGE_CACHE_TIMEOUT_MILLIS) {
            try {
                cacheLessonThumbnails(uniqueThumbnailUrls) { completed, _ ->
                    onProgress(
                        AssetSetupProgress(
                            completed = completed,
                            total = total,
                            message = AssetSetupMessage.DownloadingLearnThumbnails
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

        markComplete()
        onProgress(
            AssetSetupProgress(
                total,
                total,
                if (thumbnailsReady) {
                    AssetSetupMessage.Complete
                } else {
                    AssetSetupMessage.CompleteWithMissingImages
                }
            )
        )
        return true
    }

    private companion object {
        const val TAG = "InitialAssetSetup"
        const val IMAGE_CACHE_TIMEOUT_MILLIS = 30_000L
        const val INITIAL_LESSON_COUNT = 60
    }
}
