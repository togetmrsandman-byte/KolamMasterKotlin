package com.kolammaster.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderDataInstrumentedTest {
    @Test
    fun decodesFolderJsonAndSerializesExactFieldNames() {
        val folders = FolderJson.decode(
            JSONArray(
                """[{"id":"folder-1","name":"Favorites","lessons":["easy-feet-01","easy-heart-02"]}]"""
            )
        )

        assertEquals(
            listOf(Folder("folder-1", "Favorites", listOf("easy-feet-01", "easy-heart-02"))),
            folders
        )
        val serializedFolder = JSONArray(FolderJson.encode(folders)).getJSONObject(0)
        val fieldNames = buildSet {
            val keys = serializedFolder.keys()
            while (keys.hasNext()) add(keys.next())
        }
        assertEquals(setOf("id", "name", "lessons"), fieldNames)
        assertEquals("folder-1", serializedFolder.getString("id"))
        assertEquals("Favorites", serializedFolder.getString("name"))
        assertEquals(
            listOf("easy-feet-01", "easy-heart-02"),
            jsonArrayStrings(serializedFolder.getJSONArray("lessons"))
        )
    }

    @Test
    fun createsFoldersWithUniqueIdsAndEmptyMembership() {
        val (firstCollection, first) = FolderCollection.create(emptyList(), "One")
        val (secondCollection, second) = FolderCollection.create(firstCollection, "Two")

        assertNotEquals("One", first.id)
        assertNotEquals(first.id, second.id)
        assertEquals(emptyList<String>(), first.lessons)
        assertEquals(listOf(first, second), secondCollection)
    }

    @Test
    fun renamePreservesIdAndLessons() {
        val original = Folder("stable-id", "Old name", listOf("lesson-a"))

        val renamed = FolderCollection.rename(listOf(original), original.id, "New name").single()

        assertEquals("stable-id", renamed.id)
        assertEquals("New name", renamed.name)
        assertEquals(listOf("lesson-a"), renamed.lessons)
    }

    @Test
    fun addLessonIsIdempotentAndAllowsMembershipInDifferentFolders() {
        val first = Folder("first", "First", emptyList())
        val second = Folder("second", "Second", emptyList())
        val once = FolderCollection.addLesson(listOf(first, second), first.id, "lesson-a")
        val twice = FolderCollection.addLesson(once, first.id, "lesson-a")
        val addedToBoth = FolderCollection.addLesson(twice, second.id, "lesson-a")

        assertEquals(once, twice)
        assertEquals(listOf("lesson-a"), addedToBoth[0].lessons)
        assertEquals(listOf("lesson-a"), addedToBoth[1].lessons)
    }

    @Test
    fun removingLessonOnlyChangesSelectedFolder() {
        val folders = listOf(
            Folder("first", "First", listOf("lesson-a", "lesson-b")),
            Folder("second", "Second", listOf("lesson-a"))
        )

        val updated = FolderCollection.removeLesson(folders, "first", "lesson-a")

        assertEquals(listOf("lesson-b"), updated[0].lessons)
        assertEquals(listOf("lesson-a"), updated[1].lessons)
    }

    @Test
    fun deletingFolderLeavesOtherFoldersAndLessonsUnchanged() {
        val deletedFolder = Folder("delete-me", "Delete me", listOf("lesson-a"))
        val retainedFolder = Folder("keep-me", "Keep me", listOf("lesson-b"))

        val updated = FolderCollection.delete(listOf(deletedFolder, retainedFolder), "delete-me")

        assertEquals(listOf(retainedFolder), updated)
    }

    @Test
    fun nullAndEmptyFolderDataDecodeAsEmptyCollection() {
        assertTrue(FolderJson.decode(null).isEmpty())
        assertTrue(FolderJson.decode(JSONObject.NULL).isEmpty())
        assertTrue(FolderJson.decode(JSONArray()).isEmpty())
        assertTrue(FolderJson.decode("[]").isEmpty())
        assertTrue(FolderJson.decode("null").isEmpty())
    }

    private fun jsonArrayStrings(array: JSONArray): List<String> =
        (0 until array.length()).map(array::getString)
}
