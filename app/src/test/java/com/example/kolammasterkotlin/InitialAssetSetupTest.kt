package com.kolammaster.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialAssetSetupTest {
    @Test
    fun completesStartupWithoutWaitingForOptionalCatalogueImages() = runBlocking {
        var isComplete = false
        val progress = mutableListOf<AssetSetupProgress>()
        val setup = InitialAssetSetup(
            isComplete = { isComplete },
            markComplete = { isComplete = true }
        )

        assertTrue(setup.run { progress += it })

        assertTrue(isComplete)
        assertEquals(AssetSetupMessage.Complete, progress.last().message)
        assertEquals(0, progress.last().completed)
        assertEquals(0, progress.last().total)
    }

    @Test
    fun completedSetupDoesNotRepeatInitialization() = runBlocking {
        var markCompleteCalls = 0
        val setup = InitialAssetSetup(
            isComplete = { true },
            markComplete = { markCompleteCalls++ }
        )

        assertTrue(setup.run {})

        assertEquals(0, markCompleteCalls)
    }

    @Test
    fun optionalThumbnailPrefetchUsesAtMostSixtyAvailableLessons() = runBlocking {
        listOf(1, 20, 60, 75).forEach { lessonCount ->
            val requestedUrls = mutableListOf<String>()

            InitialAssetSetup.prefetchAvailableThumbnails(
                lessons = List(lessonCount) { index -> lesson(index) },
                cacheLessonThumbnails = { urls, _ ->
                    requestedUrls += urls
                    true
                }
            )

            assertEquals(minOf(lessonCount, 60), requestedUrls.size)
        }
    }

    @Test
    fun missingThumbnailUrlsDoNotPreventOptionalPrefetch() = runBlocking {
        val requestedUrls = mutableListOf<String>()

        InitialAssetSetup.prefetchAvailableThumbnails(
            lessons = List(20) { index ->
                lesson(
                    index,
                    thumbnailUrl = if (index == 5) null else "https://example.test/$index.png"
                )
            },
            cacheLessonThumbnails = { urls, _ ->
                requestedUrls += urls
                true
            }
        )

        assertEquals(19, requestedUrls.size)
        assertTrue(requestedUrls.none { it.endsWith("/5.png") })
    }

    private fun lesson(index: Int, thumbnailUrl: String? = "https://example.test/$index.png") =
        LessonCatalogueEntry(
            id = "lesson-$index",
            lessonName = "Lesson $index",
            category = null,
            difficulty = null,
            kolamType = null,
            lessonType = null,
            gridType = null,
            tags = emptyList(),
            festival = null,
            keywords = emptyList(),
            totalSteps = null,
            stepCount = null,
            dotInformation = null,
            thumbnailUrls = listOfNotNull(thumbnailUrl),
            thumbnailImage = null,
            previewImage = null,
            dotsImage = null,
            tintedImage = null,
            tinted = null,
            manifest = null,
            path = null,
            fillSteps = emptyList(),
            packageUrls = emptyList(),
            packageFile = null,
            packageFilename = null,
            filename = null,
            sourceJson = "{}"
        )
}
