package com.kolammaster.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class LessonCatalogueInstrumentedTest {
    @Test
    fun parsesRootArrayPreservingOrderAndNormalizingDifficultyAndType() {
        val lessons = parseCatalogueJson(
            """
            [
              {"lessonName":"First","category":"Beginner","kolamType":"Sikku","thumbnailUrls":["https://example.test/first.webp"]},
              {"name":"Second","category":"Intermediate","lessonType":"rangoli"},
              {"lessonName":""},
              null
            ]
            """.trimIndent()
        )

        assertEquals(listOf("First", "Second"), lessons.map { it.lessonName })
        assertEquals("Easy", lessons.first().normalizedDifficulty)
        assertEquals("Sikku", lessons.first().normalizedType)
        assertEquals("https://example.test/first.webp", lessons.first().firstThumbnailUrl)
        assertEquals("Intermediate", lessons.last().normalizedDifficulty)
        assertEquals("Rangoli", lessons.last().normalizedType)
    }

    @Test
    fun parsesEnvelopedCataloguesAndOptionalMetadata() {
        val lessons = parseCatalogueJson(
            """
            {
              "items": [
                {
                  "id": "pulli-1",
                  "lessonName": "Dots",
                  "category": "Expert",
                  "kolamType": "Pulli",
                  "gridType": "5-3",
                  "dotInformation": "5x3",
                  "keywords": ["dots", "festival"],
                  "totalSteps": 4,
                  "stepCount": 3,
                  "fillSteps": [1, 2],
                  "packageUrls": ["https://example.test/lesson.kmp"],
                  "tinted": true
                }
              ]
            }
            """.trimIndent()
        )

        val lesson = lessons.single()
        assertEquals("pulli-1", lesson.id)
        assertEquals("Expert", lesson.normalizedDifficulty)
        assertEquals("Pulli", lesson.normalizedType)
        assertEquals("5-3", lesson.gridType)
        assertEquals(listOf("dots", "festival"), lesson.keywords)
        assertEquals(listOf(1, 2), lesson.fillSteps)
        assertEquals("true", lesson.tinted)
        assertNull(lesson.firstThumbnailUrl)
    }

    @Test
    fun refreshesCatalogueFromPublicR2Endpoint() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lessons = LessonCatalogueRepository(context).refresh()

        assertFalse(lessons.isEmpty())
        assertFalse(lessons.first().lessonName.isBlank())
        val thumbnailUrl = lessons.first().firstThumbnailUrl
        assertNotNull(thumbnailUrl)
        assertTrue(thumbnailUrl.orEmpty().startsWith("https://"))
        assertNotNull(loadThumbnail(thumbnailUrl.orEmpty()))
        val cachedLessons = LessonCatalogueRepository(context).loadCached()
        assertEquals(lessons.map { it.id }, cachedLessons.map { it.id })
    }
}
