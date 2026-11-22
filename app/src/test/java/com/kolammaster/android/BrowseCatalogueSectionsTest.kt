package com.kolammaster.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.foundation.lazy.grid.LazyGridState

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
    fun browseLockVisibilityUsesFreeStatusAndCurrentUnlockRecords() {
        val free = lesson("free", difficulty = "Easy", type = "Sikku", isFree = true)
        val locked = lesson("locked", difficulty = "Easy", type = "Sikku", isFree = false)

        assertFalse(shouldShowLessonLockIcon(free, emptySet()))
        assertTrue(shouldShowLessonLockIcon(locked, emptySet()))
        assertFalse(shouldShowLessonLockIcon(locked, setOf("locked")))
        assertEquals(CatalogueAccess.LOCKED, lessonWithCatalogueAccess(locked).access)
        assertFalse(lessonWithCatalogueAccess(locked).isFree)
    }

    @Test
    fun folderLockVisibilityTracksUnlocksWithoutChangingFolderReferences() {
        val free = lesson("free", difficulty = "Easy", type = "Sikku", isFree = true)
        val locked = lesson("saved-locked", difficulty = "Easy", type = "Sikku", isFree = false)
        val customFolder = Folder("custom", "Saved Kolams", listOf(locked.id, free.id))

        assertFalse(shouldShowLessonLockIcon(free, emptySet()))
        assertTrue(shouldShowLessonLockIcon(locked, emptySet()))
        assertFalse(shouldShowLessonLockIcon(locked, setOf(locked.id)))
        assertEquals(listOf(locked.id, free.id), customFolder.lessons)
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
    fun returningFromALessonKeepsArrangementAndDoesNotRestartTheFiveHourTimer() {
        val originalEntries = lessonsForNineGroups(
            mapOf("Easy" to 2, "Intermediate" to 2, "Expert" to 2)
        )
        val storage = InMemoryBrowseArrangementStorage()
        val repository = BrowseArrangementRepository(storage) { 17L }
        val initial = repository.arrange(originalEntries, nowMillis = 12_345L)
        val initialSnapshot = storage.snapshot
        val unlockedIds = setOf("Expert-Sikku-1")

        val afterLessonReturn = repository.arrange(
            originalEntries,
            nowMillis = 12_345L + BROWSE_ARRANGEMENT_REFRESH_MILLIS,
            refreshIfDue = false
        )

        assertEquals(initial.map { it.id }, afterLessonReturn.map { it.id })
        assertEquals(initialSnapshot, storage.snapshot)
        assertTrue(canOpenLesson(afterLessonReturn.single { it.id in unlockedIds }, unlockedIds))
    }

    @Test
    fun automaticRefreshAppendsNewIdsAndPreservesSavedOrderSeedAndTimestamp() {
        val originalEntries = lessonsForNineGroups(
            mapOf("Easy" to 2, "Intermediate" to 2, "Expert" to 2)
        )
        val storage = InMemoryBrowseArrangementStorage()
        val repository = BrowseArrangementRepository(storage) { 17L }
        val initial = repository.arrange(originalEntries, nowMillis = 12_345L)
        val initialSnapshot = storage.snapshot
        val additions = listOf(
            lesson("new-one", difficulty = "Easy", type = "Pulli", isFree = true),
            lesson("new-two", difficulty = "Expert", type = "Rangoli", isFree = false)
        )

        val afterAutomaticRefresh = repository.arrange(
            originalEntries + additions,
            nowMillis = 12_345L + BROWSE_ARRANGEMENT_REFRESH_MILLIS * 2,
            refreshIfDue = false
        )

        assertEquals(
            initial.map { it.id } + additions.map { it.id },
            afterAutomaticRefresh.map { it.id }
        )
        assertEquals(
            initialSnapshot?.orderedLessonIds,
            storage.snapshot?.orderedLessonIds?.take(initial.size)
        )
        assertEquals(initialSnapshot?.seed, storage.snapshot?.seed)
        assertEquals(initialSnapshot?.arrangedAtMillis, storage.snapshot?.arrangedAtMillis)
    }

    @Test
    fun manualRefreshWithNewIdsGeneratesOneNewArrangement() {
        val originalEntries = lessonsForNineGroups(
            mapOf("Easy" to 2, "Intermediate" to 2, "Expert" to 2)
        )
        val storage = InMemoryBrowseArrangementStorage()
        val seeds = mutableListOf(17L, 42L)
        var seedCalls = 0
        val repository = BrowseArrangementRepository(storage) {
            seedCalls++
            seeds.removeAt(0)
        }
        repository.arrange(originalEntries, nowMillis = 12_345L)
        val additions = listOf(
            lesson("manual-new", difficulty = "Easy", type = "Pulli", isFree = true)
        )
        val refreshedEntries = originalEntries + additions

        val arranged = repository.arrange(
            refreshedEntries,
            nowMillis = 22_345L,
            refreshIfDue = false,
            rearrangeForNewLessons = hasNewCatalogueLessonIds(
                originalEntries.mapTo(mutableSetOf(), LessonCatalogueEntry::id),
                refreshedEntries
            )
        )

        assertEquals(2, seedCalls)
        assertEquals(42L, storage.snapshot?.seed)
        assertEquals(22_345L, storage.snapshot?.arrangedAtMillis)
        assertEquals(refreshedEntries.size, arranged.size)
        assertEquals(refreshedEntries.map { it.id }.toSet(), arranged.map { it.id }.toSet())
    }

    @Test
    fun manualRefreshWithoutNewIdsPreservesOrderSeedAndTimestamp() {
        val entries = lessonsForNineGroups(
            mapOf("Easy" to 2, "Intermediate" to 2, "Expert" to 2)
        )
        val storage = InMemoryBrowseArrangementStorage()
        val repository = BrowseArrangementRepository(storage) { 17L }
        val initial = repository.arrange(entries, nowMillis = 12_345L)
        val initialSnapshot = storage.snapshot
        val metadataUpdates = entries.map { it.copy(lessonName = "${it.lessonName} revised") }
        val refreshed = mergeCatalogueEntries(entries, metadataUpdates)

        assertFalse(hasNewCatalogueLessonIds(entries.mapTo(mutableSetOf()) { it.id }, refreshed))
        val afterManualRefresh = repository.arrange(
            refreshed,
            nowMillis = 99_999L,
            refreshIfDue = false,
            rearrangeForNewLessons = false
        )

        assertEquals(initial.map { it.id }, afterManualRefresh.map { it.id })
        assertEquals(initialSnapshot, storage.snapshot)
    }

    @Test
    fun unlockingLessonChangesAccessFilterButNotItsBrowsePosition() {
        val entries = lessonsForNineGroups(
            mapOf("Easy" to 3, "Intermediate" to 3, "Expert" to 3)
        )
        val storage = InMemoryBrowseArrangementStorage()
        val repository = BrowseArrangementRepository(storage) { 29L }
        val arrangedBeforeUnlock = repository.arrange(entries, nowMillis = 50_000L)
        val unlockedIds = setOf("Intermediate-Pulli-1")

        val unlockedFilter = filterBrowseCatalogue(
            entries = arrangedBeforeUnlock,
            query = "",
            difficulties = emptySet(),
            types = emptySet(),
            accessFilter = BrowseLessonAccessFilter.UNLOCKED,
            unlockedLessonIds = unlockedIds
        )
        val arrangedAfterUnlock = repository.arrange(
            entries,
            nowMillis = 50_001L,
            refreshIfDue = false
        )

        assertEquals(listOf("Intermediate-Pulli-1"), unlockedFilter.map { it.id })
        assertEquals(
            arrangedBeforeUnlock.map { it.id },
            arrangedAfterUnlock.map { it.id }
        )
        assertEquals(
            arrangedBeforeUnlock.indexOfFirst { it.id in unlockedIds },
            arrangedAfterUnlock.indexOfFirst { it.id in unlockedIds }
        )
    }

    @Test
    fun catalogueRefreshWithoutNewIdsDoesNotRearrangeOrRestartTimer() {
        val entries = lessonsForNineGroups(
            mapOf("Easy" to 2, "Intermediate" to 2, "Expert" to 2)
        )
        val storage = InMemoryBrowseArrangementStorage()
        val seeds = mutableListOf(17L)
        val repository = BrowseArrangementRepository(storage) { seeds.removeAt(0) }
        val initial = repository.arrange(entries, nowMillis = 12_345L)
        val snapshot = storage.snapshot
        val metadataUpdates = entries.map { it.copy(lessonName = "${it.lessonName} updated") }
            .asReversed()
        val refreshed = mergeCatalogueEntries(entries, metadataUpdates)

        val arranged = repository.arrange(
            refreshed,
            nowMillis = 12_346L,
            refreshIfDue = false
        )

        assertEquals(initial.map { it.id }, arranged.map { it.id })
        assertTrue(arranged.all { it.lessonName.endsWith(" updated") })
        assertEquals(snapshot, storage.snapshot)
        assertEquals(17L, storage.snapshot?.seed)
    }

    @Test
    fun manuallyDiscoveredCatalogueBatchTriggersFreshArrangementAndPreservesUnlocks() {
        val existingEntries = lessonsForNineGroups(
            mapOf("Easy" to 2, "Intermediate" to 2, "Expert" to 2)
        )
        val storage = InMemoryBrowseArrangementStorage()
        val seeds = mutableListOf(17L, 42L)
        val repository = BrowseArrangementRepository(storage) { seeds.removeAt(0) }
        repository.arrange(existingEntries, nowMillis = 12_345L)
        val unlockedIds = setOf("Expert-Sikku-1")
        val newBatch = (0 until 20).map { index ->
            lesson("new-$index", difficulty = "Easy", type = "Pulli", isFree = index % 2 == 0)
        }
        val refreshedEntries = mergeCatalogueEntries(existingEntries, newBatch)

        val arranged = repository.arrange(
            refreshedEntries,
            nowMillis = 22_345L,
            refreshIfDue = false,
            rearrangeForNewLessons = true
        )

        assertEquals(42L, storage.snapshot?.seed)
        assertEquals(22_345L, storage.snapshot?.arrangedAtMillis)
        assertEquals(refreshedEntries.map { it.id }.toSet(), arranged.map { it.id }.toSet())
        assertEquals(refreshedEntries.size, arranged.size)
        assertTrue(arranged.map { it.id }.toSet().containsAll(existingEntries.map { it.id }))
        assertTrue(canOpenLesson(arranged.single { it.id in unlockedIds }, unlockedIds))
        assertEquals(
            balancedBrowseArrangement(refreshedEntries, 42L).map { it.id },
            arranged.map { it.id }
        )
    }

    @Test
    fun duplicateLessonIdsDoNotCreateDuplicateArrangementEntries() {
        val first = lesson("duplicate", difficulty = "Easy", type = "Sikku", isFree = true)
        val duplicate = lesson("duplicate", difficulty = "Expert", type = "Rangoli", isFree = false)
        val another = lesson("another", difficulty = "Intermediate", type = "Pulli", isFree = false)
        val storage = InMemoryBrowseArrangementStorage()
        val repository = BrowseArrangementRepository(storage) { 19L }

        val arranged = repository.arrange(
            listOf(first, duplicate, another),
            nowMillis = 1_000L
        )

        assertEquals(2, arranged.size)
        assertEquals(setOf("duplicate", "another"), arranged.map { it.id }.toSet())
        assertEquals(2, arranged.map { it.id }.distinct().size)
        assertEquals(2, storage.snapshot?.orderedLessonIds?.distinct()?.size)
        assertEquals(1, mergeCatalogueEntries(listOf(first, duplicate), listOf(duplicate)).size)
    }

    @Test
    fun browseCatalogueStateRetainsTheSavedGridPosition() {
        val state = LazyGridState(firstVisibleItemIndex = 37, firstVisibleItemScrollOffset = 83)
        val browseState = BrowseCatalogueState(state)

        assertTrue(state === browseState.gridState)
        assertEquals(37, browseState.gridState.firstVisibleItemIndex)
        assertEquals(83, browseState.gridState.firstVisibleItemScrollOffset)
    }

    @Test
    fun allFourAccessFiltersCombineWithTextDifficultyAndType() {
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
            accessFilter = BrowseLessonAccessFilter.FREE,
            unlockedLessonIds = setOf("locked-lotus")
        )
        assertEquals(listOf("free-lotus"), freeResult.map { it.id })

        val lockedResult = filterBrowseCatalogue(
            entries = entries,
            query = "lotus",
            difficulties = setOf("Easy"),
            types = setOf("Sikku"),
            accessFilter = BrowseLessonAccessFilter.LOCKED,
            unlockedLessonIds = setOf("locked-lotus")
        )
        assertTrue(lockedResult.isEmpty())

        val unlockedResult = filterBrowseCatalogue(
            entries = entries,
            query = "lotus",
            difficulties = setOf("Easy"),
            types = setOf("Sikku"),
            accessFilter = BrowseLessonAccessFilter.UNLOCKED,
            unlockedLessonIds = setOf("locked-lotus")
        )
        assertEquals(listOf("locked-lotus"), unlockedResult.map { it.id })
        assertFalse(unlockedResult.single().isFree)
        assertEquals(CatalogueAccess.LOCKED, lessonWithCatalogueAccess(unlockedResult.single()).access)
        assertTrue(canOpenLesson(unlockedResult.single(), setOf("locked-lotus")))

        val allAccess = filterBrowseCatalogue(
            entries,
            query = "",
            difficulties = emptySet(),
            types = emptySet(),
            accessFilter = BrowseLessonAccessFilter.ALL,
            unlockedLessonIds = setOf("locked-lotus")
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
