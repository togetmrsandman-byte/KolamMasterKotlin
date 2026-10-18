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
    private val markComplete: () -> Unit
) {
    suspend fun run(onProgress: suspend (AssetSetupProgress) -> Unit): Boolean {
        if (isComplete()) return true

        onProgress(AssetSetupProgress(0, 0, AssetSetupMessage.PreparingImages))
        markComplete()
        onProgress(AssetSetupProgress(0, 0, AssetSetupMessage.Complete))
        return true
    }

    companion object {
        private const val INITIAL_LESSON_LIMIT = 60
        private const val OPTIONAL_THUMBNAIL_TIMEOUT_MILLIS = 30_000L
        private const val TAG = "InitialAssetSetup"

        suspend fun prefetchAvailableThumbnails(
            lessons: List<LessonCatalogueEntry>,
            cacheLessonThumbnails: suspend (
                List<String>,
                suspend (Int, Int) -> Unit
            ) -> Boolean
        ) {
            val urls = lessons.take(INITIAL_LESSON_LIMIT)
                .mapNotNull { it.firstThumbnailUrl?.takeIf(String::isNotBlank) }
                .distinct()
            if (urls.isEmpty()) return

            val result = withTimeoutOrNull(OPTIONAL_THUMBNAIL_TIMEOUT_MILLIS) {
                try {
                    cacheLessonThumbnails(urls) { _, _ -> }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.w(TAG, "Could not prefetch optional lesson thumbnails.", exception)
                    false
                }
            }
            if (result != true) {
                Log.w(TAG, "Optional thumbnail prefetch was incomplete or timed out.")
            }
        }
    }
}
