package com.kolammaster.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialAssetSetupTest {
    @Test
    fun downloadsBothCollectionsBeforeMarkingSetupComplete() = runBlocking {
        val harness = Harness()

        assertTrue(harness.setup.run {})

        assertEquals(60, harness.cachedThumbnails.size)
        assertEquals(2, harness.cachedCommunityImages.size)
        assertTrue(harness.isComplete)
        assertEquals(listOf("lessons", "community", "complete"), harness.events)
    }

    @Test
    fun completedSetupDoesNotLoadOrDownloadAssetsAgain() = runBlocking {
        val harness = Harness().apply { isComplete = true }

        assertTrue(harness.setup.run {})

        assertTrue(harness.events.isEmpty())
        assertTrue(harness.cachedThumbnails.isEmpty())
        assertTrue(harness.cachedCommunityImages.isEmpty())
    }

    @Test
    fun failedImageDownloadDoesNotBlockOnboardingAndIsReported() = runBlocking {
        val harness = Harness().apply { failLastThumbnailOnce = true }
        val progress = mutableListOf<AssetSetupProgress>()

        assertTrue(harness.setup.run { progress += it })
        assertTrue(harness.isComplete)
        assertEquals(59, harness.cachedThumbnails.size)
        assertEquals(2, harness.cachedCommunityImages.size)
        assertEquals("Setup complete; missing images can load later", progress.last().message)
    }

    @Test
    fun emptyCommunityGalleryIsAValidCompletedSetup() = runBlocking {
        val harness = Harness().apply { communityImages = emptyList() }

        assertTrue(harness.setup.run {})

        assertTrue(harness.isComplete)
        assertEquals(60, harness.cachedThumbnails.size)
        assertEquals(0, harness.cachedCommunityImages.size)
    }

    private class Harness {
        var isComplete = false
        var failLastThumbnailOnce = false
        var thumbnailDownloadCount = 0
        var communityImages = listOf(
            CommunitySubmissionImage("image-1", "key-1", "https://example.test/1.png", 1),
            CommunitySubmissionImage("image-2", "key-2", "https://example.test/2.png", 2)
        )
        val cachedThumbnails = mutableSetOf<String>()
        val cachedCommunityImages = mutableSetOf<String>()
        val events = mutableListOf<String>()
        private var failureUsed = false

        val setup = InitialAssetSetup(
            isComplete = { isComplete },
            markComplete = {
                isComplete = true
                events += "complete"
            },
            loadLessons = {
                events += "lessons"
                List(60) { index -> lesson(index) }
            },
            loadCommunityImages = {
                events += "community"
                communityImages
            },
            cacheLessonThumbnails = { urls, reportProgress ->
                var completed = 0
                var successful = true
                urls.forEach { url ->
                    if (url !in cachedThumbnails) {
                        thumbnailDownloadCount++
                        if (failLastThumbnailOnce && !failureUsed && url.endsWith("/59.png")) {
                            failureUsed = true
                            successful = false
                        } else {
                            cachedThumbnails += url
                        }
                    }
                    completed++
                    reportProgress(completed, urls.size)
                }
                successful
            },
            cacheCommunityImages = { images, reportProgress ->
                images.forEachIndexed { index, image ->
                    cachedCommunityImages += image.id
                    reportProgress(index + 1, images.size)
                }
                true
            }
        )

        private fun lesson(index: Int) = LessonCatalogueEntry(
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
            thumbnailUrls = listOf("https://example.test/$index.png"),
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
}
