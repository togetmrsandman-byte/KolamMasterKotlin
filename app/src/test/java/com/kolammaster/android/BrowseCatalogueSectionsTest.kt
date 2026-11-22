package com.kolammaster.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseCatalogueSectionsTest {
    @Test
    fun catalogueParsingAndCacheRoundTripPreserveIsFreeAndDefaultMissingValuesToLocked() {
        val entries = parseCatalogueJson(
            """[
                {"id":"free-lesson","lessonName":"Free Lesson","isFree":true,"cacheMarker":"kept"},
                {"id":"locked-lesson","lessonName":"Locked Lesson","isFree":false},
                {"id":"legacy-lesson","lessonName":"Legacy Lesson"}
            ]"""
        )

        assertEquals(listOf(true, false, false), entries.map { it.isFree })
        val cachedEntries = parseCatalogueJson(catalogueCacheJson(entries))
        assertEquals(entries.map { it.id }, cachedEntries.map { it.id })
        assertEquals(listOf(true, false, false), cachedEntries.map { it.isFree })
        assertTrue(catalogueCacheJson(entries).contains("cacheMarker"))
    }

    @Test
    fun catalogueCategoryAndLessonAccessRemainSeparateForPreviouslyUnlockedLessons() {
        val free = lesson("free", difficulty = "Easy", type = "Sikku", isFree = true)
        val locked = lesson("locked", difficulty = "Easy", type = "Sikku", isFree = false)

        assertEquals(CatalogueAccess.FREE, lessonWithCatalogueAccess(free).access)
        assertTrue(canOpenLesson(free, emptySet()))
        assertEquals(CatalogueAccess.LOCKED, lessonWithCatalogueAccess(locked).access)
        assertFalse(canOpenLesson(locked, emptySet()))
        assertTrue(canOpenLesson(locked, setOf("locked")))
        assertEquals(CatalogueAccess.LOCKED, lessonWithCatalogueAccess(locked).access)
        assertFalse(lessonWithCatalogueAccess(locked).isFree)
    }

    @Test
    fun arrangementBalancesAllNineGroupsAndKeepsUnevenExpertGroupsIntact() {
        val groupCounts = mapOf(
            "Easy" to 2,
            "Intermediate" to 3,
            "Expert" to 4
        )
        val entries = lessonsForNineGroups(groupCounts)

        assertEquals(27, entries.size)
        assertEquals(
            setOf("Easy/Sikku", "Easy/Pulli", "Easy/Rangoli",
                "Intermediate/Sikku", "Intermediate/Pulli", "Intermediate/Rangoli",
                "Expert/Sikku", "Expert/Pulli", "Expert/Rangoli"),
            entries.map { "${it.normalizedDifficulty}/${it.normalizedType}" }.toSet()
        )
        assertEquals(
            listOf(4, 4, 4),
            listOf("Sikku", "Pulli", "Rangoli").map { type ->
                entries.count {
                    it.normalizedDifficulty == "Expert" && it.normalizedType == type
                }
            }
        )

        val arranged = balancedBrowseArrangement(entries, seed = 41L)
        assertEquals(entries.map { it.id }.toSet(), arranged.map { it.id }.toSet())
        assertEquals(entries.size, arranged.size)
        assertEquals(entries.size, arranged.map { it.id }.distinct().size)
        assertTrue(maximumRun(arranged) { "${it.normalizedDifficulty}/${it.normalizedType}" } == 1)
        assertTrue(maximumRun(arranged) { it.normalizedDifficulty } <= 2)
        assertTrue(maximumRun(arranged) { it.normalizedType } <= 2)
        assertTrue(maximumRun(arranged) { it.isFree } <= 3)
    }

    @Test
    fun allLessonsIncludingTheTwelveExpertEntriesAreReturnedExactlyOnce() {
        val entries = lessonsForNineGroups(
            mapOf("Easy" to 1, "Intermediate" to 2, "Expert" to 4)
        )
        val arranged = balancedBrowseArrangement(entries, seed = 9L)

        assertEquals(entries.size, arranged.size)
        assertEquals(entries.map { it.id }.toSet(), arranged.map { it.id }.toSet())
        assertEquals(12, arranged.count { it.normalizedDifficulty == "Expert" })
    }

    @Test
    fun arrangementStaysPersistedUntilFiveHoursThenCreatesANewSeededOrder() {
        val entries = lessonsForNineGroups(mapOf("Easy" to 3, "Intermediate" to 3, "Expert" to 3))
        val storage = InMemoryBrowseArrangementStorage()
        val generatedSeeds = mutableListOf(111L, 222L)
        val repository = BrowseArrangementRepository(storage) { generatedSeeds.removeAt(0) }
        val initial = repository.arrange(entries, nowMillis = 1_000L)
        val firstSnapshot = storage.snapshot

        val beforeRefresh = repository.arrange(
            entries,
            nowMillis = 1_000L + BROWSE_ARRANGEMENT_REFRESH_MILLIS - 1
        )
        assertEquals(initial.map { it.id }, beforeRefresh.map { it.id })
        assertEquals(firstSnapshot, storage.snapshot)

        val persistedOrder = BrowseArrangementRepository(storage) {
            error("A persisted arrangement should not generate another seed")
        }.arrange(entries, nowMillis = 1_000L + BROWSE_ARRANGEMENT_REFRESH_MILLIS - 1)
        assertEquals(initial.map { it.id }, persistedOrder.map { it.id })

        val refreshed = repository.arrange(
            entries,
            nowMillis = 1_000L + BROWSE_ARRANGEMENT_REFRESH_MILLIS
        )
        assertEquals(222L, storage.snapshot?.seed)
        assertEquals(1_000L + BROWSE_ARRANGEMENT_REFRESH_MILLIS, storage.snapshot?.arrangedAtMillis)
        assertEquals(balancedBrowseArrangement(entries, 222L).map { it.id }, refreshed.map { it.id })
        assertNotEquals(initial.map { it.id }, refreshed.map { it.id })
    }

    @Test
    fun catalogueRefreshAddsLessonsWithoutReorderingExistingEntriesOrChangingUnlocks() {
        val originalEntries = lessonsForNineGroups(
            mapOf("Easy" to 2, "Intermediate" to 2, "Expert" to 2)
        )
        val storage = InMemoryBrowseArrangementStorage()
        val repository = BrowseArrangementRepository(storage) { 17L }
        val initial = repository.arrange(originalEntries, nowMillis = 12_345L)
        val initialSnapshot = storage.snapshot
        val unlockedIds = setOf("Expert-Sikku-1")
        val refreshedEntries = originalEntries + lesson(
            "new-lesson",
            difficulty = "Easy",
            type = "Pulli",
            isFree = false
        )

        val refreshed = repository.arrange(
            refreshedEntries,
            nowMillis = 12_346L,
            refreshIfDue = false
        )

        assertEquals(refreshedEntries.map { it.id }.toSet(), refreshed.map { it.id }.toSet())
        assertEquals(refreshedEntries.size, refreshed.size)
        assertEquals(
            initial.map { it.id },
            refreshed.map { it.id }.filter { it in originalEntries.map(LessonCatalogueEntry::id).toSet() }
        )
        assertEquals(initialSnapshot?.arrangedAtMillis, storage.snapshot?.arrangedAtMillis)
        val previouslyUnlocked = refreshed.single { it.id in unlockedIds }
        assertEquals(CatalogueAccess.LOCKED, lessonWithCatalogueAccess(previouslyUnlocked).access)
        assertFalse(previouslyUnlocked.isFree)
        assertTrue(canOpenLesson(previouslyUnlocked, unlockedIds))
    }

    @Test
    fun textDifficultyTypeAndAccessFiltersCombineAndLockedIncludesUnlockedLessons() {
        val entries = listOf(
            lesson("free-lotus", "Lotus easy", "Easy", "Sikku", true),
            lesson("locked-lotus", "Lotus locked", "Easy", "Sikku", false),
            lesson("free-other-type", "Lotus rangoli", "Easy", "Rangoli", true),
            lesson("free-other-difficulty", "Lotus expert", "Expert", "Sikku", true),
            lesson("free-other-name", "Mango easy", "Easy", "Sikku", true)
        )

        val freeResult = filterBrowseCatalogue(
            entries = entries,
            query = "lotus",
            difficulties = setOf("Easy"),
            types = setOf("Sikku"),
            accessFilter = BrowseLessonAccessFilter.FREE
        )
        assertEquals(listOf("free-lotus"), freeResult.map { it.id })

        val lockedResult = filterBrowseCatalogue(
            entries = entries,
            query = "lotus",
            difficulties = setOf("Easy"),
            types = setOf("Sikku"),
            accessFilter = BrowseLessonAccessFilter.LOCKED
        )
        assertEquals(listOf("locked-lotus"), lockedResult.map { it.id })
        assertEquals(
            CatalogueAccess.LOCKED,
            lessonWithCatalogueAccess(lockedResult.single()).access
        )
        assertTrue(canOpenLesson(lockedResult.single(), setOf("locked-lotus")))

        val allAccess = filterBrowseCatalogue(
            entries,
            query = "",
            difficulties = emptySet(),
            types = emptySet(),
            accessFilter = BrowseLessonAccessFilter.ALL
        )
        assertEquals(entries.map { it.id }, allAccess.map { it.id })
    }

    @Test
    fun arrangementRefreshTimingDoesNotTreatClockRollbackAsElapsedTime() {
        assertFalse(browseArrangementIsDue(10_000L, 9_999L + BROWSE_ARRANGEMENT_REFRESH_MILLIS))
        assertFalse(browseArrangementIsDue(10_000L, 9_999L))
        assertTrue(
            browseArrangementIsDue(
                arrangedAtMillis = 10_000L,
                nowMillis = 10_000L + BROWSE_ARRANGEMENT_REFRESH_MILLIS
            )
        )
    }

    private fun lessonsForNineGroups(counts: Map<String, Int>): List<LessonCatalogueEntry> =
        buildList {
            listOf("Easy", "Intermediate", "Expert").forEach { difficulty ->
                listOf("Sikku", "Pulli", "Rangoli").forEach { type ->
                    repeat(counts.getValue(difficulty)) { index ->
                        add(
                            lesson(
                                id = "$difficulty-$type-$index",
                                difficulty = difficulty,
                                type = type,
                                isFree = index % 2 == 0
                            )
                        )
                    }
                }
            }
        }

    private fun lesson(
        id: String,
        name: String = id,
        difficulty: String,
        type: String,
        isFree: Boolean
    ): LessonCatalogueEntry = parseCatalogueJson(
        """[{"id":"$id","lessonName":"$name","difficulty":"$difficulty","kolamType":"$type","isFree":$isFree}]"""
    ).single()

    private fun <T> maximumRun(
        entries: List<LessonCatalogueEntry>,
        key: (LessonCatalogueEntry) -> T
    ): Int {
        var maximum = 0
        var current = 0
        var previous: Any? = null
        entries.forEach { entry ->
            val value = key(entry)
            current = if (current > 0 && value == previous) current + 1 else 1
            maximum = maxOf(maximum, current)
            previous = value
        }
        return maximum
    }

    private class InMemoryBrowseArrangementStorage : BrowseArrangementStorage {
        var snapshot: BrowseArrangementSnapshot? = null
            private set

        override fun load(): BrowseArrangementSnapshot? = snapshot

        override fun save(snapshot: BrowseArrangementSnapshot) {
            this.snapshot = snapshot
        }
    }
}
