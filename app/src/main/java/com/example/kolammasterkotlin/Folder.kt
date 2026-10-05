package com.kolammaster.app

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

internal data class Folder(
    val id: String,
    val name: String,
    val lessons: List<String>
)

internal class FolderDataException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class FolderNotFoundException(folderId: String) :
    IllegalArgumentException("Folder '$folderId' does not exist.")

internal object FolderJson {
    fun decode(value: Any?): List<Folder> {
        if (value == null || value === JSONObject.NULL) return emptyList()
        if (value is String) {
            if (value.isBlank()) return emptyList()
            val parsed = try {
                org.json.JSONTokener(value).nextValue()
            } catch (exception: JSONException) {
                throw FolderDataException("Supabase returned malformed folder JSON.", exception)
            }
            return decode(parsed)
        }
        if (value !is JSONArray) {
            throw FolderDataException("Supabase folder data must be a JSON array.")
        }

        return buildList {
            for (index in 0 until value.length()) {
                val item = value.opt(index) as? JSONObject
                    ?: throw FolderDataException("Folder at index $index is not an object.")
                val id = item.opt("id") as? String
                    ?: throw FolderDataException("Folder at index $index has no string ID.")
                val name = item.opt("name") as? String
                    ?: throw FolderDataException("Folder at index $index has no string name.")
                val lessonArray = item.opt("lessons") as? JSONArray
                    ?: throw FolderDataException(
                        "Folder '$id' has no lessons array."
                    )
                if (id.isBlank() || name.isBlank()) {
                    throw FolderDataException("Folder at index $index has a blank ID or name.")
                }

                val lessons = buildList {
                    for (lessonIndex in 0 until lessonArray.length()) {
                        val lessonId = lessonArray.opt(lessonIndex) as? String
                            ?: throw FolderDataException(
                                "Folder '$id' has a non-string lesson ID."
                            )
                        if (lessonId.isBlank()) {
                            throw FolderDataException(
                                "Folder '$id' has a blank lesson ID."
                            )
                        }
                        add(lessonId)
                    }
                }
                add(Folder(id, name, lessons.distinct()))
            }
        }
    }

    fun encode(folders: List<Folder>): String = JSONArray().apply {
        folders.forEach { folder ->
            put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("lessons", JSONArray(folder.lessons))
            )
        }
    }.toString()
}

internal object FolderCollection {
    fun create(folders: List<Folder>, name: String): Pair<List<Folder>, Folder> {
        require(name.isNotBlank()) { "Folder name must not be empty." }
        var id: String
        do {
            id = UUID.randomUUID().toString()
        } while (folders.any { it.id == id })
        val created = Folder(id = id, name = name, lessons = emptyList())
        return (folders + created) to created
    }

    fun rename(folders: List<Folder>, folderId: String, name: String): List<Folder> {
        require(folderId.isNotBlank()) { "Folder ID must not be empty." }
        require(name.isNotBlank()) { "Folder name must not be empty." }
        requireFolder(folders, folderId)
        return folders.map { folder ->
            if (folder.id == folderId) folder.copy(name = name) else folder
        }
    }

    fun delete(folders: List<Folder>, folderId: String): List<Folder> {
        require(folderId.isNotBlank()) { "Folder ID must not be empty." }
        return folders.filterNot { it.id == folderId }
    }

    fun addLesson(
        folders: List<Folder>,
        folderId: String,
        lessonId: String
    ): List<Folder> {
        require(folderId.isNotBlank()) { "Folder ID must not be empty." }
        require(lessonId.isNotBlank()) { "Lesson ID must not be empty." }
        requireFolder(folders, folderId)
        return folders.map { folder ->
            if (folder.id == folderId && lessonId !in folder.lessons) {
                folder.copy(lessons = folder.lessons + lessonId)
            } else {
                folder
            }
        }
    }

    fun removeLesson(
        folders: List<Folder>,
        folderId: String,
        lessonId: String
    ): List<Folder> {
        require(folderId.isNotBlank()) { "Folder ID must not be empty." }
        require(lessonId.isNotBlank()) { "Lesson ID must not be empty." }
        requireFolder(folders, folderId)
        return folders.map { folder ->
            if (folder.id == folderId) {
                folder.copy(lessons = folder.lessons.filterNot { it == lessonId })
            } else {
                folder
            }
        }
    }

    private fun requireFolder(folders: List<Folder>, folderId: String) {
        if (folders.none { it.id == folderId }) throw FolderNotFoundException(folderId)
    }
}
