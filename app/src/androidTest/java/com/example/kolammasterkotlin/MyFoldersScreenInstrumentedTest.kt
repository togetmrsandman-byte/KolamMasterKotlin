package com.kolammaster.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.SocketTimeoutException

@RunWith(AndroidJUnit4::class)
class MyFoldersScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ContactComposeTestActivity>()

    @Test
    fun guestSeesSignInGateAndCanRequestExistingGoogleSignIn() {
        val repository = FakeFolderDataSource()
        var signInRequested = false
        composeRule.setContent {
            MyFoldersDestination(
                accountId = null,
                catalogueEntries = emptyList(),
                onSignIn = { signInRequested = true },
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }

        assertTextExists("Sign in with Google")
        composeRule.onNodeWithText("Sign in with Google").performClick()
        assertTextMissing("Create Folder")
        composeRule.runOnIdle { assertTrue(signInRequested) }
        assertEquals(0, repository.loadCount)
    }

    @Test
    fun signedInAccountLoadsFolderAndShowsResolvedLesson() {
        val repository = FakeFolderDataSource(
            listOf(Folder("folder-id", "Practice", listOf("easy-feet-01")))
        )
        val lessons = parseCatalogueJson(
            """[{"lessonName":"Feet 01","category":"Beginner","kolamType":"Sikku"}]"""
        )
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = lessons,
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }

        composeRule.waitUntil(5_000) { repository.loadCount > 0 }
        assertTextExists("Practice")
        assertTextExists("1 lesson")
        composeRule.onNodeWithText("Practice").performClick()
        assertTextExists("Feet 01")
        assertTextExists("Sikku · Easy")
    }

    @Test
    fun folderListShowsLessonCountForEmptyFolders() {
        val repository = FakeFolderDataSource(
            listOf(Folder("empty-folder", "My First", emptyList()))
        )
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = emptyList(),
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }

        composeRule.waitUntil(5_000) { repository.loadCount > 0 }
        assertTextExists("My First")
        assertTextExists("0 lessons")
    }

    @Test
    fun folderLessonInformationUsesAvailableWidth() {
        val lessons = parseCatalogueJson(
            """[{"id":"gods-lesson","lessonName":"GODS","category":"Beginner","kolamType":"Sikku","totalSteps":4}]"""
        )
        val repository = FakeFolderDataSource(
            listOf(Folder("folder-id", "Practice", listOf("gods-lesson")))
        )
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = lessons,
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }

        composeRule.waitUntil(5_000) { repository.loadCount > 0 }
        composeRule.onNodeWithText("Practice").performClick()

        assertTextExists("GODS")
        assertTextExists("Sikku · Easy · 4 steps")
        assertTextExists("Remove")
    }

    @Test
    fun createAndRenameUpdateFolderWithoutChangingItsIdentityOrLessons() {
        val repository = FakeFolderDataSource(
            listOf(Folder("stable-folder-id", "Old name", listOf("lesson-a")))
        )
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = emptyList(),
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }
        composeRule.waitUntil(5_000) { repository.loadCount > 0 }
        composeRule.onNodeWithText("Create Folder").performClick()
        composeRule.onNodeWithText("Folder name").performTextInput("  New folder  ")
        composeRule.onNodeWithText("Create").performClick()
        composeRule.waitUntil(5_000) { repository.folders.any { it.name == "New folder" } }

        composeRule.onAllNodesWithText("Rename")[0].performClick()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextClearance()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("Renamed folder")
        composeRule.onNodeWithText("Save").performClick()
        composeRule.waitUntil(5_000) {
            repository.folders.any { it.name == "Renamed folder" }
        }

        val renamed = repository.folders.first { it.id == "stable-folder-id" }
        assertEquals("stable-folder-id", renamed.id)
        assertEquals(listOf("lesson-a"), renamed.lessons)
        assertTextExists("Renamed folder")
        assertTextExists("New folder")
    }

    @Test
    fun deletionRequiresConfirmationAndLeavesOtherFoldersAlone() {
        val repository = FakeFolderDataSource(
            listOf(
                Folder("folder-a", "Remove me", listOf("lesson-a")),
                Folder("folder-b", "Keep me", listOf("lesson-b"))
            )
        )
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = emptyList(),
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }
        composeRule.waitUntil(5_000) { repository.loadCount > 0 }

        composeRule.onAllNodesWithText("Delete")[0].performClick()
        assertTextExists("Are you sure you want to delete?")
        assertTextExists(
            "Delete \"Remove me\" and its saved lesson references? " +
                "Lessons will remain in Learn and in any other folders."
        )
        composeRule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("folder-a", "folder-b"), repository.folders.map(Folder::id))
        composeRule.onAllNodesWithText("Delete")[0].performClick()
        val deleteButtons = composeRule.onAllNodesWithText("Delete")
        deleteButtons[deleteButtons.fetchSemanticsNodes().lastIndex].performClick()
        composeRule.waitUntil(5_000) { repository.folders.size == 1 }

        assertEquals(listOf("folder-b"), repository.folders.map(Folder::id))
        assertEquals(listOf("lesson-b"), repository.folders.single().lessons)
        assertTextExists("Keep me")
        assertTextMissing("Remove me")
    }

    @Test
    fun removeButtonRemovesOnlyCurrentFolderMembership() {
        val lessons = parseCatalogueJson(
            """[{"id":"shared-lesson","lessonName":"Shared lesson","category":"Beginner","kolamType":"Sikku"}]"""
        )
        val repository = FakeFolderDataSource(
            listOf(
                Folder("folder-a", "First", listOf("shared-lesson")),
                Folder("folder-b", "Second", listOf("shared-lesson"))
            )
        )
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = lessons,
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }
        composeRule.waitUntil(5_000) { repository.loadCount > 0 }

        composeRule.onNodeWithText("First").performClick()
        assertTextExists("Shared lesson")
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.waitUntil(5_000) { repository.folders.first().lessons.isEmpty() }

        assertEquals(listOf("shared-lesson"), repository.folders.last().lessons)
        assertTextMissing("Shared lesson")
        assertTextExists("This folder is empty.")
    }

    @Test
    fun accountSwitchAndGuestStateNeverKeepPreviousFoldersVisible() {
        val repository = FakeFolderDataSource(listOf(Folder("a", "Account A folder", emptyList())))
        val activeAccount = mutableStateOf<String?>("google-user-a")
        composeRule.setContent {
            MyFoldersDestination(
                accountId = activeAccount.value,
                catalogueEntries = emptyList(),
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }
        composeRule.waitUntil(5_000) { repository.loadCount > 0 }
        assertTextExists("Account A folder")

        composeRule.runOnIdle {
            repository.folders = listOf(Folder("b", "Account B folder", emptyList()))
            activeAccount.value = "google-user-b"
        }
        composeRule.waitUntil(5_000) { repository.loadCount >= 2 }
        assertTextExists("Account B folder")
        assertTextMissing("Account A folder")

        composeRule.runOnIdle { activeAccount.value = null }
        assertTextExists("Sign in with Google")
        assertTextMissing("Account B folder")
        assertTextMissing("Create Folder")
    }

    @Test
    fun guestHeartUsesExistingSignInAndContinuesToSaveFlow() {
        val repository = FakeFolderDataSource(listOf(Folder("folder-1", "Existing", emptyList())))
        val account = mutableStateOf<String?>(null)
        val lessons = parseCatalogueJson(
            """[{"lessonName":"Feet 01","category":"Beginner","kolamType":"Sikku"}]"""
        )
        var signInRequested = false
        composeRule.setContent {
            BrowseLessonScreen(
                catalogue = LessonCatalogueUiState(entries = lessons, isInitialLoading = false),
                unlockedLessonIds = emptySet(),
                accountId = account.value,
                onGoogleSignIn = { signInRequested = true },
                onLessonSelected = {},
                folderRepository = repository
            )
        }

        composeRule.onNodeWithContentDescription("Save Feet 01 to My Folders").performClick()
        composeRule.runOnIdle { assertTrue(signInRequested) }
        composeRule.runOnIdle { account.value = "google-user-a" }
        assertTextExists("Save to My Folders")
        assertTextExists("Existing")
        composeRule.onNodeWithText("Existing").performClick()
        composeRule.waitUntil(5_000) {
            lessons.single().id in repository.folders.single().lessons
        }
        assertTrue(
            composeRule.onAllNodesWithContentDescription("Saved Feet 01 to My Folders")
                .fetchSemanticsNodes().isNotEmpty()
        )
    }

    @Test
    fun saveDialogCreatesFolderAndAddsLessonWithoutAffectingOtherFolders() {
        val lesson = parseCatalogueJson(
            """[{"lessonName":"Feet 01","category":"Beginner","kolamType":"Sikku"}]"""
        ).single()
        val repository = FakeFolderDataSource(
            listOf(Folder("existing-id", "Existing", listOf(lesson.id)))
        )
        var savedFolder: Folder? = null
        composeRule.setContent {
            SaveToMyFoldersDialog(
                accountId = "google-user-a",
                lesson = lesson,
                folderRepository = repository,
                onSaved = { savedFolder = it },
                onDismiss = {}
            )
        }

        assertTextExists("Existing")
        composeRule.onAllNodesWithText("Create New Folder").let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        }
        composeRule.onNodeWithText("Folder name").performTextInput("New folder")
        composeRule.onAllNodesWithText("Create New Folder").let { nodes ->
            nodes[nodes.fetchSemanticsNodes().lastIndex].performClick()
        }
        composeRule.waitUntil(5_000) {
            repository.folders.any { it.name == "New folder" && lesson.id in it.lessons } &&
                savedFolder != null
        }

        assertEquals(listOf(lesson.id), repository.folders.single { it.id == "existing-id" }.lessons)
        assertTrue(savedFolder?.lessons?.contains(lesson.id) == true)
        assertEquals("New folder", savedFolder?.name)
    }

    @Test
    fun savingToAnAlreadyContainingFolderIsIdempotent() {
        val lesson = parseCatalogueJson(
            """[{"lessonName":"Feet 01","category":"Beginner","kolamType":"Sikku"}]"""
        ).single()
        val repository = FakeFolderDataSource(
            listOf(Folder("folder-id", "Already saved", listOf(lesson.id)))
        )
        composeRule.setContent {
            SaveToMyFoldersDialog(
                accountId = "google-user-a",
                lesson = lesson,
                folderRepository = repository,
                onSaved = {},
                onDismiss = {}
            )
        }

        assertTextExists("Already saved")
        composeRule.onNodeWithText("Already saved").performClick()
        composeRule.waitUntil(5_000) {
            repository.folders.single().lessons == listOf(lesson.id)
        }
        assertEquals(listOf(lesson.id), repository.folders.single().lessons)
    }

    @Test
    fun folderLoadNetworkErrorsUseSharedMessage() {
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = emptyList(),
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = FakeFolderDataSource(
                    loadFailure = SocketTimeoutException("socket read timeout")
                )
            )
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(NetworkErrors.TITLE, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTextContains(NetworkErrors.TITLE)
        assertTextContains(NetworkErrors.MESSAGE)
    }

    @Test
    fun folderLoadNonNetworkFailuresKeepTheirDetails() {
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = emptyList(),
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = FakeFolderDataSource(
                    loadFailure = IllegalStateException("permission denied")
                )
            )
        }
        assertTextExists("permission denied")
        assertTextMissing(NetworkErrors.TITLE)
    }

    @Test
    fun folderOperationNetworkErrorsUseSharedMessage() {
        val repository = FakeFolderDataSource(
            operationFailure = SocketTimeoutException("socket read timeout")
        )
        composeRule.setContent {
            MyFoldersDestination(
                accountId = "google-user-a",
                catalogueEntries = emptyList(),
                onSignIn = {},
                onCancel = {},
                isSignInProcessing = false,
                folderRepository = repository
            )
        }
        composeRule.onNodeWithText("Create Folder").performClick()
        composeRule.onNodeWithText("Folder name").performTextInput("New folder")
        composeRule.onNodeWithText("Create").performClick()
        assertTextContains(NetworkErrors.TITLE)
        assertTextContains(NetworkErrors.MESSAGE)
    }

    private fun assertTextExists(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertTextMissing(text: String) {
        composeRule.waitForIdle()
        assertEquals(0, composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size)
    }

    private fun assertTextContains(text: String) {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodes(hasText(text, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakeFolderDataSource(
        initialFolders: List<Folder> = emptyList(),
        private val loadFailure: Exception? = null,
        private val operationFailure: Exception? = null
    ) : FolderDataSource {
        var folders = initialFolders
        var loadCount = 0
            private set

        override suspend fun loadFolders(): List<Folder> {
            loadCount++
            loadFailure?.let { throw it }
            return folders
        }

        override suspend fun createFolder(name: String): Folder {
            operationFailure?.let { throw it }
            val (updated, created) = FolderCollection.create(folders, name)
            folders = updated
            return created
        }

        override suspend fun renameFolder(folderId: String, name: String): Folder {
            operationFailure?.let { throw it }
            folders = FolderCollection.rename(folders, folderId, name)
            return folders.single { it.id == folderId }
        }

        override suspend fun deleteFolder(folderId: String) {
            operationFailure?.let { throw it }
            folders = FolderCollection.delete(folders, folderId)
        }

        override suspend fun addLesson(folderId: String, lessonId: String): Folder {
            operationFailure?.let { throw it }
            folders = FolderCollection.addLesson(folders, folderId, lessonId)
            return folders.single { it.id == folderId }
        }

        override suspend fun removeLesson(folderId: String, lessonId: String): Folder {
            operationFailure?.let { throw it }
            folders = FolderCollection.removeLesson(folders, folderId, lessonId)
            return folders.single { it.id == folderId }
        }
    }
}
