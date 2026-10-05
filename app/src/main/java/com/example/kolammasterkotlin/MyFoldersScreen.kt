package com.kolammaster.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val FolderGold = Color(0xFF8F6F38)
private val FolderSurface = Color.White.copy(alpha = 0.08f)

@Composable
internal fun MyFoldersDestination(
    accountId: String?,
    catalogueEntries: List<LessonCatalogueEntry>,
    onSignIn: () -> Unit,
    onCancel: () -> Unit,
    isSignInProcessing: Boolean,
    selectedFolderIdState: MutableState<String?> = remember(accountId) {
        mutableStateOf(null)
    },
    folderRepository: FolderDataSource = remember { FolderRepository() }
) {
    val selectedFolderId by selectedFolderIdState
    var folders by remember(accountId) { mutableStateOf(emptyList<Folder>()) }
    var isLoading by remember(accountId) { mutableStateOf(accountId != null) }
    var isProcessing by remember(accountId) { mutableStateOf(false) }
    var loadError by remember(accountId) { mutableStateOf<String?>(null) }
    var actionError by remember(accountId) { mutableStateOf<String?>(null) }
    var folderDialog by remember(accountId) { mutableStateOf<FolderDialog?>(null) }
    var deleteFolder by remember(accountId) { mutableStateOf<Folder?>(null) }
    var refreshKey by remember(accountId) { mutableIntStateOf(0) }
    val latestAccountId by rememberUpdatedState(accountId)
    val scope = rememberCoroutineScope()

    LaunchedEffect(accountId, refreshKey) {
        if (accountId == null) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        loadError = null
        try {
            val loaded = folderRepository.loadFolders()
            if (latestAccountId == accountId) folders = loaded
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (latestAccountId == accountId) {
                loadError = NetworkErrors.messageFor(
                    exception,
                    "Could not load folders. Please try again."
                )
            }
        } finally {
            if (latestAccountId == accountId) isLoading = false
        }
    }

    fun updateFolder(updated: Folder) {
        folders = folders.map { if (it.id == updated.id) updated else it }
    }

    fun runFolderAction(action: suspend () -> Unit) {
        if (isProcessing || accountId == null) return
        val actionAccountId = accountId
        isProcessing = true
        actionError = null
        scope.launch {
            try {
                action()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (latestAccountId == actionAccountId) {
                    actionError = NetworkErrors.messageFor(
                        exception,
                        "Folder update failed. Please try again."
                    )
                    folderDialog = null
                    deleteFolder = null
                }
            } finally {
                if (latestAccountId == actionAccountId) isProcessing = false
            }
        }
    }

    LandingArtworkFrame(
        showDecorations = accountId == null,
        horizontalPadding = if (accountId == null) 58.dp else 16.dp
    ) {
        Spacer(Modifier.height(18.dp))
        if (accountId == null) {
            Text(
                "My Folders",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.height(32.dp)
            ) {
                FolderTitleDecoration()
                Text(
                    "My Folders",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                FolderTitleDecoration(mirrored = true)
            }
        }
        if (accountId == null) {
            MyFoldersGuestContent(
                onSignIn = onSignIn,
                onBack = onCancel,
                isSignInProcessing = isSignInProcessing
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(8.dp))
                if (isLoading) {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFFC9A86A))
                        Spacer(Modifier.height(12.dp))
                        Text("Loading folders...", color = Color.White.copy(alpha = 0.8f))
                    }
                } else {
                    loadError?.let { message ->
                        Text(message, color = Color(0xFFFFB4AB), textAlign = TextAlign.Center)
                        TextButton(onClick = { refreshKey++ }) { Text("Retry") }
                    }
                    actionError?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFFFB4AB),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (loadError == null) {
                        if (selectedFolderId == null) {
                            Button(
                                onClick = { folderDialog = FolderDialog.Create },
                                enabled = !isProcessing,
                                colors = ButtonDefaults.buttonColors(containerColor = FolderGold)
                            ) { Text("Create Folder") }
                            Spacer(Modifier.height(8.dp))
                            if (folders.isEmpty()) {
                                EmptyFoldersMessage(Modifier.weight(1f))
                            } else {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(folders, key = Folder::id) { folder ->
                                        FolderListItem(
                                            folder = folder,
                                            enabled = !isProcessing,
                                            onOpen = { selectedFolderIdState.value = folder.id },
                                            onRename = {
                                                folderDialog = FolderDialog.Rename(folder)
                                            },
                                            onDelete = { deleteFolder = folder }
                                        )
                                    }
                                }
                            }
                        } else {
                            val folder = folders.firstOrNull { it.id == selectedFolderId }
                            Text(
                                folder?.name ?: "This folder is no longer available.",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            if (folder == null) {
                                Spacer(Modifier.weight(1f))
                            } else if (folder.lessons.isEmpty()) {
                                EmptyFolderLessonsMessage(Modifier.weight(1f))
                            } else {
                                val lessonsById = remember(catalogueEntries) {
                                    catalogueEntries.associateBy(LessonCatalogueEntry::id)
                                }
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(folder.lessons, key = { it }) { lessonId ->
                                        val lesson = lessonsById[lessonId]
                                        val onRemove = {
                                            if (!isProcessing) {
                                                runFolderAction {
                                                    val updated = folderRepository.removeLesson(
                                                        folder.id,
                                                        lessonId
                                                    )
                                                    if (latestAccountId == accountId) {
                                                        updateFolder(updated)
                                                    }
                                                }
                                            }
                                        }
                                        if (lesson == null) {
                                            UnavailableFolderLessonCard(
                                                lessonId = lessonId,
                                                enabled = !isProcessing,
                                                onRemove = onRemove
                                            )
                                        } else {
                                            FolderLessonCard(
                                                lesson = lesson,
                                                enabled = !isProcessing,
                                                onRemove = onRemove
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    folderDialog?.let { dialog ->
        FolderNameDialog(
            dialog = dialog,
            enabled = !isProcessing,
            onDismiss = { if (!isProcessing) folderDialog = null },
            onConfirm = { name ->
                val trimmedName = name.trim()
                if (trimmedName.isNotEmpty()) {
                    runFolderAction {
                        when (dialog) {
                            FolderDialog.Create -> {
                                val created = folderRepository.createFolder(trimmedName)
                                if (latestAccountId == accountId) folders = folders + created
                            }
                            is FolderDialog.Rename -> {
                                val renamed =
                                    folderRepository.renameFolder(dialog.folder.id, trimmedName)
                                if (latestAccountId == accountId) updateFolder(renamed)
                            }
                        }
                        if (latestAccountId == accountId) folderDialog = null
                    }
                }
            }
        )
    }

    deleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { if (!isProcessing) deleteFolder = null },
            title = { Text("Are you sure you want to delete?") },
            text = {
                Text(
                    "Delete \"${folder.name}\" and its saved lesson references? " +
                        "Lessons will remain in Learn and in any other folders."
                )
            },
            confirmButton = {
                Button(
                    enabled = !isProcessing,
                    onClick = {
                        runFolderAction {
                            folderRepository.deleteFolder(folder.id)
                            if (latestAccountId == accountId) {
                                folders = folders.filterNot { it.id == folder.id }
                                if (selectedFolderId == folder.id) {
                                    selectedFolderIdState.value = null
                                }
                                deleteFolder = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = FolderGold)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isProcessing,
                    onClick = { deleteFolder = null }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun FolderTitleDecoration(mirrored: Boolean = false) {
    Box(
        modifier = Modifier.size(width = 38.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Image(
            bitmap = rememberAssetImage("kolam-border.png"),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .requiredSize(width = 38.dp, height = 92.dp)
                .then(
                    if (mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier
                )
        )
    }
}

@Composable
private fun EmptyFoldersMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No folders yet.", color = Color.White, fontSize = 18.sp)
        Text(
            "Create a folder to organize your lessons.",
            color = Color.White.copy(alpha = 0.72f),
            fontSize = 14.sp
        )
    }
}

@Composable
private fun EmptyFolderLessonsMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("This folder is empty.", color = Color.White, fontSize = 17.sp)
    }
}

@Composable
private fun FolderListItem(
    folder: Folder,
    enabled: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = FolderSurface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onOpen)
                    .padding(vertical = 4.dp)
            ) {
                Text(
                    folder.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${folder.lessons.size} ${
                        if (folder.lessons.size == 1) "lesson" else "lessons"
                    }",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 13.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactFolderActionButton(
                    label = "View",
                    enabled = enabled,
                    onClick = onOpen
                )
                Button(
                    enabled = enabled,
                    onClick = onRename,
                    modifier = Modifier.height(32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp
                    ),
                    colors = ButtonDefaults.buttonColors(containerColor = FolderGold)
                ) { Text("Rename", fontSize = 12.sp) }
                Button(
                    enabled = enabled,
                    onClick = onDelete,
                    modifier = Modifier.height(32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp
                    ),
                    colors = ButtonDefaults.buttonColors(containerColor = FolderGold)
                ) { Text("Delete", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun CompactFolderActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        enabled = enabled,
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 8.dp,
            vertical = 0.dp
        ),
        colors = ButtonDefaults.buttonColors(containerColor = FolderGold)
    ) {
        Text(label, fontSize = 12.sp)
    }
}

@Composable
private fun UnavailableFolderLessonCard(
    lessonId: String,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = FolderSurface)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(112.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Kolam", color = Color.White.copy(alpha = 0.55f))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "Unavailable lesson",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = lessonId,
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                RemoveLessonButton(enabled, onRemove)
            }
        }
    }
}

@Composable
private fun FolderLessonCard(
    lesson: LessonCatalogueEntry,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RemoteLessonThumbnail(
                url = lesson.firstThumbnailUrl,
                modifier = Modifier
                    .size(100.dp)
                    .aspectRatio(1f)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = lesson.lessonName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val metadata = listOfNotNull(
                    lesson.normalizedType,
                    lesson.normalizedDifficulty,
                    lesson.totalSteps?.let { "$it steps" }
                ).joinToString(" · ")
                if (metadata.isNotEmpty()) {
                    Text(
                        text = metadata,
                        color = Color.White.copy(alpha = 0.72f),
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                RemoveLessonButton(enabled, onRemove)
            }
        }
    }
}

@Composable
private fun RemoveLessonButton(enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(36.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 0.dp
        ),
        colors = ButtonDefaults.buttonColors(containerColor = FolderGold)
    ) {
        Text("Remove")
    }
}

private sealed interface FolderDialog {
    data object Create : FolderDialog
    data class Rename(val folder: Folder) : FolderDialog
}

@Composable
private fun FolderNameDialog(
    dialog: FolderDialog,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember(dialog) {
        mutableStateOf((dialog as? FolderDialog.Rename)?.folder?.name.orEmpty())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (dialog is FolderDialog.Create) "Create folder" else "Rename folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true,
                enabled = enabled
            )
        },
        confirmButton = {
            TextButton(
                enabled = enabled && name.isNotBlank(),
                onClick = { onConfirm(name) }
            ) { Text(if (dialog is FolderDialog.Create) "Create" else "Save") }
        },
        dismissButton = {
            TextButton(enabled = enabled, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
internal fun SaveToMyFoldersDialog(
    accountId: String?,
    lesson: LessonCatalogueEntry,
    folderRepository: FolderDataSource,
    onSaved: (Folder) -> Unit,
    onDismiss: () -> Unit
) {
    var folders by remember(accountId) { mutableStateOf(emptyList<Folder>()) }
    var isLoading by remember(accountId) { mutableStateOf(accountId != null) }
    var isProcessing by remember(accountId) { mutableStateOf(false) }
    var error by remember(accountId) { mutableStateOf<String?>(null) }
    var creatingFolder by remember(accountId) { mutableStateOf(false) }
    var folderName by remember(accountId) { mutableStateOf("") }
    val latestAccountId by rememberUpdatedState(accountId)
    val scope = rememberCoroutineScope()

    LaunchedEffect(accountId) {
        if (accountId == null) {
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        error = null
        try {
            val loaded = folderRepository.loadFolders()
            if (latestAccountId == accountId) folders = loaded
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            if (latestAccountId == accountId) {
                error = NetworkErrors.messageFor(
                    exception,
                    "Could not load folders. Please try again."
                )
            }
        } finally {
            if (latestAccountId == accountId) isLoading = false
        }
    }

    fun saveToFolder(folderId: String) {
        val actionAccountId = accountId ?: return
        if (isProcessing) return
        isProcessing = true
        error = null
        scope.launch {
            var created: Folder? = null
            try {
                if (creatingFolder) {
                    val name = folderName.trim()
                    require(name.isNotEmpty()) { "Folder name must not be empty." }
                    created = folderRepository.createFolder(name)
                    folders = folders + created
                }
                val targetId = created?.id ?: folderId
                val updated = folderRepository.addLesson(targetId, lesson.id)
                if (latestAccountId == actionAccountId) onSaved(updated)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                if (latestAccountId == actionAccountId) {
                    error = if (NetworkErrors.isNetworkFailure(exception)) {
                        NetworkErrors.DISPLAY_TEXT
                    } else if (created != null) {
                        "The folder was created, but the lesson could not be added. " +
                            (exception.message ?: "Please try again.")
                    } else {
                        NetworkErrors.messageFor(
                            exception,
                            "Could not save this lesson. Please try again."
                        )
                    }
                }
            } finally {
                if (latestAccountId == actionAccountId) isProcessing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        title = {
            Text(if (creatingFolder) "Create New Folder" else "Save to My Folders")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    lesson.lessonName,
                    color = Color.White.copy(alpha = 0.8f)
                )
                error?.let { Text(it, color = Color(0xFFB3261E)) }
                when {
                    accountId == null -> Text("Sign in with Google to save lessons.")
                    creatingFolder -> OutlinedTextField(
                        value = folderName,
                        onValueChange = { folderName = it },
                        label = { Text("Folder name") },
                        singleLine = true,
                        enabled = !isProcessing
                    )
                    isLoading -> CircularProgressIndicator()
                    folders.isEmpty() -> Text("No folders yet. Create one to save this lesson.")
                    else -> LazyColumn(
                        modifier = Modifier.heightIn(max = 280.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(folders, key = Folder::id) { folder ->
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isProcessing,
                                onClick = { saveToFolder(folder.id) }
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(folder.name, color = Color(0xFF8F6F38))
                                    Text(
                                        if (lesson.id in folder.lessons) "Saved" else
                                            "${folder.lessons.size} lessons",
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (creatingFolder) {
                TextButton(
                    enabled = !isProcessing && folderName.isNotBlank(),
                    onClick = { saveToFolder("") }
                ) { Text(if (isProcessing) "Please wait" else "Create New Folder") }
            } else {
                TextButton(
                    enabled = !isLoading && !isProcessing,
                    onClick = {
                        creatingFolder = true
                        error = null
                    }
                ) { Text("Create New Folder") }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isProcessing,
                onClick = {
                    if (creatingFolder) {
                        creatingFolder = false
                        folderName = ""
                        error = null
                    } else {
                        onDismiss()
                    }
                }
            ) { Text(if (creatingFolder) "Back" else "Cancel") }
        }
    )
}
