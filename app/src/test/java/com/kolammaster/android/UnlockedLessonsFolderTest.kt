package com.kolammaster.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockedLessonsFolderTest {
    @Test
    fun synchronizationCreatesSystemFolderWithPreviouslyUnlockedLessons() {
        val custom = Folder("custom", "My Kolams", listOf("saved-lesson"))

        val (updated, unlockedFolder) = FolderCollection.syncUnlockedLessons(
            listOf(custom),
            setOf("old-unlock", "another-unlock")
        )

        assertEquals(2, updated.size)
        assertEquals(custom, updated.first())
        assertEquals(UNLOCKED_LESSONS_FOLDER_ID, unlockedFolder.id)
        assertEquals(UNLOCKED_LESSONS_FOLDER_NAME, unlockedFolder.name)
        assertTrue(unlockedFolder.isSystem)
        assertEquals(listOf("another-unlock", "old-unlock"), unlockedFolder.lessons)
    }

    @Test
    fun synchronizationAddsNewUnlocksExactlyOnceAndRepairsOnlyTheSystemFolder() {
        val custom = Folder("custom", "Family", listOf("saved"))
        val oldSystemFolder = Folder(
            id = UNLOCKED_LESSONS_FOLDER_ID,
            name = "Renamed",
            lessons = listOf("stale", "already-unlocked"),
            isSystem = true
        )

        val (updated, systemFolder) = FolderCollection.syncUnlockedLessons(
            listOf(custom, oldSystemFolder),
            setOf("already-unlocked", "new-unlock", "new-unlock")
        )

        assertEquals(2, updated.size)
        assertEquals(custom, updated.first())
        assertEquals(listOf("already-unlocked", "new-unlock"), systemFolder.lessons)
        assertEquals(UNLOCKED_LESSONS_FOLDER_NAME, systemFolder.name)
        assertTrue(systemFolder.isSystem)
    }

    @Test
    fun defaultFolderIdentitySurvivesFolderJsonRoundTrip() {
        val folders = listOf(
            Folder("custom", "My Kolams", listOf("saved")),
            Folder(
                UNLOCKED_LESSONS_FOLDER_ID,
                UNLOCKED_LESSONS_FOLDER_NAME,
                listOf("unlocked"),
                isSystem = true
            )
        )

        val roundTripped = FolderJson.decode(FolderJson.encode(folders))

        assertEquals(folders, roundTripped)
        assertTrue(FolderCollection.isSystemFolder(roundTripped.last()))
        assertFalse(FolderCollection.isSystemFolder(roundTripped.first()))
        assertTrue(FolderCollection.isSystemFolder(
            Folder(UNLOCKED_LESSONS_FOLDER_ID, "legacy name", emptyList())
        ))
    }

    @Test
    fun ordinaryFolderOperationsCannotModifyTheSystemFolder() {
        val (_, systemFolder) =
            FolderCollection.syncUnlockedLessons(emptyList(), setOf("unlocked"))
        val folders = listOf(systemFolder)

        assertRejected { FolderCollection.rename(folders, systemFolder.id, "Changed") }
        assertRejected { FolderCollection.delete(folders, systemFolder.id) }
        assertRejected { FolderCollection.addLesson(folders, systemFolder.id, "manual") }
        assertRejected { FolderCollection.removeLesson(folders, systemFolder.id, "unlocked") }
        assertEquals(listOf("unlocked"), systemFolder.lessons)
    }

    @Test
    fun existingCustomFolderOperationsRemainUnchanged() {
        val original = Folder("custom", "My Kolams", listOf("first"))
        val (withSecondFolder, created) = FolderCollection.create(listOf(original), "Festival")
        val renamed = FolderCollection.rename(withSecondFolder, created.id, "Festivals")
        val withLesson = FolderCollection.addLesson(renamed, created.id, "second")
        val withoutLesson = FolderCollection.removeLesson(withLesson, created.id, "second")
        val deleted = FolderCollection.delete(withoutLesson, created.id)

        assertEquals(original, deleted.single())
        assertEquals("Festivals", renamed.single { it.id == created.id }.name)
        assertEquals(listOf("second"), withLesson.single { it.id == created.id }.lessons)
        assertTrue(withoutLesson.single { it.id == created.id }.lessons.isEmpty())
    }

    private fun assertRejected(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected system-folder operation to be rejected.")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }
}
