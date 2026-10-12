package com.kolammaster.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialAssetSetupTest {
    @Test
    fun downloadsInitialLessonThumbnailsWithoutLoadingCommunityImages() = runBlocking {
        val harness = Harness()
        val progress = mutableListOf<AssetSetupProgress>()

        assertTrue(harness.setup.run { progress += it })

        assertEquals(60, harness.cachedThumbnails.size)
        assertEquals(60, progress.last().total)
        assertEquals(60, progress.last().completed)
        assertTrue(harness.isComplete)
        assertEquals(listOf("lessons", "complete"), harness.events)
    }

    @Test
    fun completedSetupDoesNotLoadOrDownloadAssetsAgain() = runBlocking {
        val harness = Harness().apply { isComplete = true }

        assertTrue(harness.setup.run {})

        assertTrue(harness.events.isEmpty())
        assertTrue(harness.cachedThumbnails.isEmpty())
    }

    @Test
    fun failedImageDownloadDoesNotBlockOnboardingAndIsReported() = runBlocking {
        val harness = Harness().apply { failLastThumbnailOnce = true }
        val progress = mutableListOf<AssetSetupProgress>()

        assertTrue(harness.setup.run { progress += it })
        assertTrue(harness.isComplete)
        assertEquals(59, harness.cachedThumbnails.size)
        assertEquals(AssetSetupMessage.CompleteWithMissingImages, progress.last().message)
    }

    private class Harness {
        var isComplete = false
        var failLastThumbnailOnce = false
        var thumbnailDownloadCount = 0
        val cachedThumbnails = mutableSetOf<String>()
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
