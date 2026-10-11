package com.kolammaster.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseCatalogueSectionsTest {
    @Test
    fun individuallyUnlockedLessonsMoveAboveBannerWithoutChangingCataloguePositions() {
        val entries = catalogueEntries(100)
        val individuallyUnlockedIds = setOf("lesson-35", "lesson-70")

        val sections = browseCatalogueSections(
            entries = entries,
            visibleCount = 60,
            unlockedLessonIds = individuallyUnlockedIds
        )

        assertEquals((0 until 30).toList(), sections.freeLessons.map { it.catalogueIndex })
        assertEquals(listOf(35, 70), sections.individuallyUnlockedLessons.map { it.catalogueIndex })
        assertEquals(
            (30 until 60).filterNot { "lesson-$it" in individuallyUnlockedIds },
            sections.revealedLessons.map { it.catalogueIndex }
        )
        assertEquals(CatalogueAccess.FREE, sections.individuallyUnlockedLessons[0].lesson.access)
        assertEquals(CatalogueAccess.FREE, sections.individuallyUnlockedLessons[1].lesson.access)
        assertEquals(CatalogueAccess.LOCKED, sections.revealedLessons.first().lesson.access)
        assertTrue(
            (sections.freeLessons + sections.individuallyUnlockedLessons + sections.revealedLessons)
                .map { it.lesson.id }
                .distinct()
                .size ==
                sections.freeLessons.size +
                sections.individuallyUnlockedLessons.size +
                sections.revealedLessons.size
        )
    }

    @Test
    fun revealedBatchesKeepTheirOriginalThirtyAndTwentyEntryBoundaries() {
        val entries = catalogueEntries(100)
        val unlockedIds = setOf("lesson-35", "lesson-70")

        val initial = browseCatalogueSections(entries, 60, unlockedIds)
        val expanded = browseCatalogueSections(entries, 80, unlockedIds)

        assertEquals((30 until 60).toSet(), initial.revealedLessons
            .map { it.catalogueIndex }
            .toSet() + setOf(35))
        assertEquals((60 until 80).toSet(), expanded.revealedLessons
            .filter { it.catalogueIndex >= 60 }
            .map { it.catalogueIndex }
            .toSet() + setOf(70))
        assertEquals("lesson-70", expanded.individuallyUnlockedLessons.last().lesson.id)
        assertEquals(CatalogueAccess.LOCKED, expanded.revealedLessons
            .single { it.catalogueIndex == 71 }
            .lesson.access)
    }

    private fun catalogueEntries(count: Int): List<LessonCatalogueEntry> =
        parseCatalogueJson(
            (0 until count).joinToString(prefix = "[", postfix = "]") { index ->
                """{"id":"lesson-$index","lessonName":"Lesson $index","category":"Beginner"}"""
            }
        )

}
