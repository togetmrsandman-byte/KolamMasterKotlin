package com.kolammaster.android

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import kotlin.random.Random

internal data class BrowseArrangementSnapshot(
    val seed: Long,
    val arrangedAtMillis: Long,
    val orderedLessonIds: List<String>
)

internal interface BrowseArrangementStorage {
    fun load(): BrowseArrangementSnapshot?
    fun save(snapshot: BrowseArrangementSnapshot)
}

internal class BrowseArrangementPreferencesStorage(context: Context) : BrowseArrangementStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    override fun load(): BrowseArrangementSnapshot? {
        val orderedIds = preferences.getString(ORDER_KEY, null) ?: return null
        return try {
            val array = JSONArray(orderedIds)
            BrowseArrangementSnapshot(
                seed = preferences.getLong(SEED_KEY, return null),
                arrangedAtMillis = preferences.getLong(ARRANGED_AT_KEY, return null),
                orderedLessonIds = List(array.length()) { index ->
                    array.getString(index)
                }
            )
        } catch (exception: JSONException) {
            Log.w(TAG, "Ignoring invalid saved Browse arrangement.", exception)
            null
        } catch (exception: ClassCastException) {
            Log.w(TAG, "Ignoring invalid saved Browse arrangement values.", exception)
            null
        }
    }

    override fun save(snapshot: BrowseArrangementSnapshot) {
        check(
            preferences.edit()
                .putLong(SEED_KEY, snapshot.seed)
                .putLong(ARRANGED_AT_KEY, snapshot.arrangedAtMillis)
                .putString(ORDER_KEY, JSONArray(snapshot.orderedLessonIds).toString())
                .commit()
        ) { "Could not persist the Browse arrangement." }
    }

    private companion object {
        const val PREFERENCES_NAME = "kolam_master_browse_arrangement"
        const val SEED_KEY = "seed"
        const val ARRANGED_AT_KEY = "arranged-at"
        const val ORDER_KEY = "ordered-lesson-ids"
        const val TAG = "BrowseArrangement"
    }
}

internal class BrowseArrangementRepository(
    private val storage: BrowseArrangementStorage,
    private val seedProvider: () -> Long = { java.security.SecureRandom().nextLong() }
) {
    @Synchronized
    fun arrange(
        entries: List<LessonCatalogueEntry>,
        nowMillis: Long = System.currentTimeMillis(),
        refreshIfDue: Boolean = true,
        rearrangeForNewLessons: Boolean = false
    ): List<LessonCatalogueEntry> {
        if (entries.isEmpty()) return emptyList()

        val uniqueEntries = entries.distinctBy(LessonCatalogueEntry::id)
        val previous = storage.load()
        val shouldRefresh = previous == null ||
            rearrangeForNewLessons ||
            (refreshIfDue && browseArrangementIsDue(previous.arrangedAtMillis, nowMillis))
        if (shouldRefresh) {
            val generatedSeed = seedProvider()
            val seed = if (previous != null && previous.seed == generatedSeed) {
                generatedSeed + 1
            } else {
                generatedSeed
            }
            val ordered = balancedBrowseArrangement(uniqueEntries, seed)
            storage.save(
                BrowseArrangementSnapshot(
                    seed = seed,
                    arrangedAtMillis = nowMillis,
                    orderedLessonIds = ordered.map(LessonCatalogueEntry::id)
                )
            )
            return ordered
        }

        val snapshot = checkNotNull(previous)
        val orderedIds = reconcileOrderedIds(snapshot, uniqueEntries)
        if (orderedIds != snapshot.orderedLessonIds) {
            storage.save(snapshot.copy(orderedLessonIds = orderedIds))
        }
        return entriesInOrder(uniqueEntries, orderedIds)
    }
}

internal const val BROWSE_ARRANGEMENT_REFRESH_MILLIS = 5 * 60 * 60 * 1000L

internal fun browseArrangementIsDue(arrangedAtMillis: Long, nowMillis: Long): Boolean =
    nowMillis >= arrangedAtMillis &&
        nowMillis - arrangedAtMillis >= BROWSE_ARRANGEMENT_REFRESH_MILLIS

internal fun hasNewCatalogueLessonIds(
    existingIds: Set<String>,
    refreshedEntries: List<LessonCatalogueEntry>
): Boolean = refreshedEntries.any { it.id !in existingIds }

internal enum class BrowseLessonAccessFilter {
    ALL,
    FREE,
    LOCKED,
    UNLOCKED
}

internal fun filterBrowseCatalogue(
    entries: List<LessonCatalogueEntry>,
    query: String,
    difficulties: Set<String>,
    types: Set<String>,
    accessFilter: BrowseLessonAccessFilter,
    unlockedLessonIds: Set<String> = emptySet()
): List<LessonCatalogueEntry> = entries.filter { lesson ->
    val matchesSearch = query.isBlank() ||
        lesson.searchableText().contains(query.trim(), ignoreCase = true)
    val matchesDifficulty = difficulties.isEmpty() ||
        lesson.normalizedDifficulty in difficulties
    val matchesType = types.isEmpty() || lesson.normalizedType in types
    val matchesAccess = when (accessFilter) {
        BrowseLessonAccessFilter.ALL -> true
        BrowseLessonAccessFilter.FREE -> lesson.isFree
        BrowseLessonAccessFilter.LOCKED -> !lesson.isFree && lesson.id !in unlockedLessonIds
        BrowseLessonAccessFilter.UNLOCKED -> !lesson.isFree && lesson.id in unlockedLessonIds
    }
    matchesSearch && matchesDifficulty && matchesType && matchesAccess
}

internal fun balancedBrowseArrangement(
    entries: List<LessonCatalogueEntry>,
    seed: Long
): List<LessonCatalogueEntry> {
    val uniqueEntries = entries.distinctBy(LessonCatalogueEntry::id)
    if (uniqueEntries.size < 2) return uniqueEntries

    val grouped = uniqueEntries.groupBy(::browseCategory)
    val remaining = grouped.mapValues { (category, lessons) ->
        lessons.shuffled(Random(seed xor category.hashCode().toLong())).toMutableList()
    }
    val categoryCounts = grouped.mapValues { it.value.size }
    val selectedCounts = mutableMapOf<BrowseCategory, Int>()
    val arranged = ArrayList<LessonCatalogueEntry>(uniqueEntries.size)

    while (arranged.size < uniqueEntries.size) {
        var bestCategory: BrowseCategory? = null
        var bestEntryIndex = -1
        var bestScore = Double.NEGATIVE_INFINITY

        for ((category, candidates) in remaining) {
            if (candidates.isEmpty()) continue
            val expectedCount =
                (arranged.size + 1) * categoryCounts.getValue(category).toDouble() /
                    uniqueEntries.size
            val deficit = expectedCount - selectedCounts.getOrDefault(category, 0)
            for (index in candidates.indices) {
                val candidate = candidates[index]
                val score = deficit * 4.0 -
                    arrangementSpacingPenalty(candidate, arranged) +
                    stableTieNoise(seed, candidate, arranged.size)
                if (score > bestScore) {
                    bestCategory = category
                    bestEntryIndex = index
                    bestScore = score
                }
            }
        }

        val chosenCategory = checkNotNull(bestCategory)
        val chosen = remaining.getValue(chosenCategory).removeAt(bestEntryIndex)
        arranged += chosen
        selectedCounts[chosenCategory] = selectedCounts.getOrDefault(chosenCategory, 0) + 1
    }

    return arranged
}

private data class BrowseCategory(
    val difficulty: String?,
    val type: String?
)

private fun browseCategory(lesson: LessonCatalogueEntry): BrowseCategory =
    BrowseCategory(lesson.normalizedDifficulty, lesson.normalizedType)

private fun arrangementSpacingPenalty(
    candidate: LessonCatalogueEntry,
    arranged: List<LessonCatalogueEntry>
): Double {
    var penalty = 0.0
    for (distance in 1..minOf(4, arranged.size)) {
        val previous = arranged[arranged.lastIndex - distance + 1]
        val distanceWeight = when (distance) {
            1 -> 1.0
            2 -> 0.55
            3 -> 0.25
            else -> 0.1
        }
        if (browseCategory(candidate) == browseCategory(previous)) {
            penalty += 3.2 * distanceWeight
        }
        if (candidate.normalizedDifficulty == previous.normalizedDifficulty) {
            penalty += 1.7 * distanceWeight
        }
        if (candidate.normalizedType == previous.normalizedType) {
            penalty += 1.7 * distanceWeight
        }
        if (candidate.isFree == previous.isFree) {
            penalty += 1.2 * distanceWeight
        }
    }
    return penalty
}

private fun stableTieNoise(seed: Long, lesson: LessonCatalogueEntry, position: Int): Double =
    Random(seed xor lesson.id.hashCode().toLong() xor (position.toLong() * 0x9E3779B9L))
        .nextDouble() * 0.8

private fun reconcileOrderedIds(
    snapshot: BrowseArrangementSnapshot,
    entries: List<LessonCatalogueEntry>
): List<String> {
    val availableIds = entries.mapTo(mutableSetOf(), LessonCatalogueEntry::id)
    val existingIds = snapshot.orderedLessonIds
        .distinct()
        .filter { it in availableIds }
    val includedIds = existingIds.toHashSet()
    return existingIds + entries.asSequence()
        .map(LessonCatalogueEntry::id)
        .filter(includedIds::add)
        .toList()
}

private fun entriesInOrder(
    entries: List<LessonCatalogueEntry>,
    orderedIds: List<String>
): List<LessonCatalogueEntry> {
    val availableById = entries.associateBy(LessonCatalogueEntry::id)
    return orderedIds.distinct().mapNotNull(availableById::get)
}
