package com.kolammaster.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

internal enum class CatalogueAccess {
    UNRESOLVED,
    FREE,
    LOCKED
}

internal data class LessonCatalogueEntry(
    val id: String,
    val lessonName: String,
    val category: String?,
    val difficulty: String?,
    val kolamType: String?,
    val lessonType: String?,
    val gridType: String?,
    val tags: List<String>,
    val festival: String?,
    val keywords: List<String>,
    val totalSteps: Int?,
    val stepCount: Int?,
    val dotInformation: String?,
    val thumbnailUrls: List<String>,
    val thumbnailImage: String?,
    val previewImage: String?,
    val dotsImage: String?,
    val tintedImage: String?,
    val tinted: String?,
    val manifest: String?,
    val path: String?,
    val fillSteps: List<Int>,
    val packageUrls: List<String>,
    val packageFile: String?,
    val packageFilename: String?,
    val filename: String?,
    val access: CatalogueAccess = CatalogueAccess.UNRESOLVED,
    internal val sourceJson: String
) {
    val normalizedDifficulty: String?
        get() = when (category?.trim()?.lowercase()) {
            "beginner" -> "Easy"
            "intermediate" -> "Intermediate"
            "expert" -> "Expert"
            else -> null
        }

    val normalizedType: String?
        get() = when {
            lessonType.equals("rangoli", ignoreCase = true) -> "Rangoli"
            kolamType.equals("sikku", ignoreCase = true) -> "Sikku"
            kolamType.equals("pulli", ignoreCase = true) -> "Pulli"
            else -> null
        }

    val firstThumbnailUrl: String?
        get() = thumbnailUrls.firstOrNull()
}

internal fun parseCatalogueJson(json: String): List<LessonCatalogueEntry> {
    val root = JSONTokener(json).nextValue()
    val records = when (root) {
        is JSONArray -> root
        is JSONObject -> sequenceOf("lessons", "catalogue", "items")
            .mapNotNull { root.optJSONArray(it) }
            .firstOrNull()
            ?: throw JSONException("Catalogue object has no lesson array")
        else -> throw JSONException("Catalogue root must be an array or object")
    }

    return buildList {
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val lessonName = record.stringValue("lessonName")
                ?: record.stringValue("name")
                ?: continue
            if (lessonName.isBlank()) continue

            val category = record.stringValue("category")
            val source = record.toString()
            add(
                LessonCatalogueEntry(
                    id = record.stringValue("id")?.takeIf(String::isNotBlank)
                        ?: "$lessonName-$index",
                    lessonName = lessonName,
                    category = category,
                    difficulty = category?.toDifficulty(),
                    kolamType = record.stringValue("kolamType"),
                    lessonType = record.stringValue("lessonType"),
                    gridType = record.stringValue("gridType"),
                    tags = record.stringList("tags"),
                    festival = record.stringValue("festival"),
                    keywords = record.stringList("keywords"),
                    totalSteps = record.intValue("totalSteps"),
                    stepCount = record.intValue("stepCount"),
                    dotInformation = record.stringValue("dotInformation"),
                    thumbnailUrls = record.stringList("thumbnailUrls"),
                    thumbnailImage = record.stringValue("thumbnailImage"),
                    previewImage = record.stringValue("previewImage"),
                    dotsImage = record.stringValue("dotsImage"),
                    tintedImage = record.stringValue("tintedImage"),
                    tinted = record.stringValue("tinted"),
                    manifest = record.jsonValue("manifest"),
                    path = record.jsonValue("path"),
                    fillSteps = record.intList("fillSteps"),
                    packageUrls = record.stringList("packageUrls"),
                    packageFile = record.stringValue("packageFile"),
                    packageFilename = record.stringValue("packageFilename"),
                    filename = record.stringValue("filename"),
                    sourceJson = source
                )
            )
        }
    }
}

internal fun catalogueCacheJson(entries: List<LessonCatalogueEntry>): String =
    JSONArray().apply {
        entries.forEach { put(JSONObject(it.sourceJson)) }
    }.toString()

private fun JSONObject.stringValue(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) ?: return null
    return when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is Number, is Boolean -> value.toString()
        else -> null
    }
}

private fun JSONObject.stringList(key: String): List<String> {
    val value = opt(key)
    return when (value) {
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                val item = value.opt(index)
                if (item is String && item.isNotBlank()) add(item)
            }
        }
        is String -> listOf(value).filter(String::isNotBlank)
        else -> emptyList()
    }
}

private fun JSONObject.intValue(key: String): Int? {
    val value = opt(key)
    return when (value) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }
}

private fun JSONObject.intList(key: String): List<Int> {
    val value = opt(key) as? JSONArray ?: return emptyList()
    return buildList {
        for (index in 0 until value.length()) {
            val item = value.opt(index)
            when (item) {
                is Number -> add(item.toInt())
                is String -> item.toIntOrNull()?.let(::add)
            }
        }
    }
}

private fun JSONObject.jsonValue(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val value = opt(key) ?: return null
    return when (value) {
        is String -> value.takeIf(String::isNotBlank)
        is JSONObject, is JSONArray -> value.toString()
        else -> null
    }
}

private fun String.toDifficulty(): String? = when (trim().lowercase()) {
    "beginner" -> "Easy"
    "intermediate" -> "Intermediate"
    "expert" -> "Expert"
    else -> null
}
