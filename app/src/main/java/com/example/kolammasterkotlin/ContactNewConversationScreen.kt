package com.kolammaster.app

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

@Composable
internal fun ContactNewConversationDestination(
    onCreate: suspend (
        phone: String,
        subject: String,
        initialMessage: String,
        imageBytes: ByteArray?
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf("") }
    var selectedCountry by remember { mutableStateOf(defaultContactCountryCallingCode) }
    var isCountryPickerOpen by remember { mutableStateOf(false) }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedAttachment by remember { mutableStateOf<ContactNewConversationImage?>(null) }
    var showAttachmentOptions by remember { mutableStateOf(false) }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }

    fun loadSelectedImage(uri: Uri, deleteAfterRead: Boolean = false) {
        scope.launch {
            try {
                selectedAttachment = withContext(Dispatchers.IO) {
                    context.readContactNewConversationImage(uri)
                }
                errorTitle = null
                errorMessage = null
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                errorTitle = "Contact Us"
                errorMessage = exception.message
                    ?: "The selected image could not be read."
            } finally {
                if (deleteAfterRead) {
                    context.contentResolver.delete(uri, null, null)
                }
            }
        }
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) loadSelectedImage(uri)
    }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { captured ->
        val uri = cameraUri
        cameraUri = null
        if (uri != null) {
            if (captured) {
                loadSelectedImage(uri, deleteAfterRead = true)
            } else {
                context.contentResolver.delete(uri, null, null)
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                val imageDirectory = File(context.cacheDir, "contact-images")
                if (!imageDirectory.exists() && !imageDirectory.mkdirs()) {
                    throw IOException("Could not prepare the camera image file.")
                }
                val imageFile = File.createTempFile("contact-", ".jpg", imageDirectory)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.contact-uploads",
                    imageFile
                )
                cameraUri = uri
                cameraLauncher.launch(uri)
            } catch (exception: Exception) {
                cameraUri?.let { context.contentResolver.delete(it, null, null) }
                cameraUri = null
                errorTitle = "Contact Us"
                errorMessage = exception.message ?: "The camera could not be opened."
            }
        } else {
            errorTitle = "Camera permission"
            errorMessage = "Allow camera access to take a Contact Us photo."
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("New Conversation", color = Color.White, fontSize = 28.sp)
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = digitsOnlyLocalContactPhoneInput(it)
                    validationError = null
                },
                enabled = !isSubmitting,
                label = { Text("Phone number", color = Color(0xFFE8E8E8)) },
                leadingIcon = {
                    Row(
                        modifier = Modifier
                            .clickable(enabled = !isSubmitting) {
                                isCountryPickerOpen = true
                            }
                            .semantics {
                                contentDescription =
                                    "Country: ${selectedCountry.countryName}, ${selectedCountry.callingCode}"
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 48.dp)
                                .padding(start = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(selectedCountry.flagEmoji())
                        }
                        Text(
                            selectedCountry.callingCode,
                            color = Color.White,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("▾", color = Color.White)
                    }
                },
                singleLine = true,
                keyboardOptions = contactPhoneKeyboardOptions(),
                colors = contactTextFieldColors(),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = subject,
                onValueChange = {
                    subject = it
                    validationError = null
                },
                enabled = !isSubmitting,
                label = { Text("Subject", color = Color(0xFFE8E8E8)) },
                singleLine = true,
                colors = contactTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = message,
                onValueChange = {
                    message = it
                    validationError = null
                },
                enabled = !isSubmitting,
                label = { Text("Message", color = Color(0xFFE8E8E8)) },
                minLines = 4,
                colors = contactTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            ContactNewConversationAttachment(
                attachment = selectedAttachment,
                enabled = !isSubmitting,
                onPickImage = {
                    validationError = null
                    showAttachmentOptions = true
                },
                onRemoveImage = {
                    selectedAttachment = null
                    validationError = null
                }
            )
            validationError?.let {
                Text(
                    it,
                    color = Color(0xFFFFB4AB),
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = {
                    if (isSubmitting) return@Button
                    val trimmedSubject = subject.trim()
                    val trimmedMessage = message.trim()
                    validationError = when {
                        trimmedSubject.isEmpty() -> "Please enter a subject."
                        trimmedMessage.isEmpty() -> "Please enter a message."
                        !isValidLocalContactPhone(phone) ->
                            "Please enter a valid phone number using digits only."
                        else -> null
                    }
                    if (validationError != null) return@Button

                    isSubmitting = true
                    scope.launch {
                        try {
                            val imageBytes = selectedAttachment?.bitmap?.let { bitmap ->
                                withContext(Dispatchers.Default) {
                                    bitmap.toContactWebp()
                                }.also { bytes ->
                                    require(bytes.size <= MAX_CONTACT_IMAGE_BYTES) {
                                        "Image must be 10 MB or smaller after WebP conversion."
                                    }
                                }
                            }
                            onCreate(
                                internationalContactPhone(selectedCountry, phone),
                                trimmedSubject,
                                trimmedMessage,
                                imageBytes
                            )
                        } catch (exception: CancellationException) {
                            throw exception
                        } catch (exception: Exception) {
                            if (NetworkErrors.isNetworkFailure(exception)) {
                                errorTitle = NetworkErrors.TITLE
                                errorMessage = NetworkErrors.MESSAGE
                            } else {
                                errorTitle = "Contact Us"
                                errorMessage =
                                    "Could not send your message. Please try again."
                            }
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                enabled = !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Sending...")
                } else {
                    Text("Send")
                }
            }
        }
    }

    if (showAttachmentOptions) {
        ContactImageAttachmentOptionsDialog(
            onCamera = {
                showAttachmentOptions = false
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onGallery = {
                showAttachmentOptions = false
                galleryLauncher.launch("image/*")
            },
            onDismiss = { showAttachmentOptions = false }
        )
    }

    if (isCountryPickerOpen) {
        CountryCallingCodeDialog(
            selectedCountry = selectedCountry,
            onCountrySelected = {
                selectedCountry = it
                isCountryPickerOpen = false
            },
            onDismiss = { isCountryPickerOpen = false }
        )
    }

    if (errorTitle != null && errorMessage != null) {
        AlertDialog(
            onDismissRequest = {
                errorTitle = null
                errorMessage = null
            },
            title = { Text(errorTitle.orEmpty()) },
            text = { Text(errorMessage.orEmpty()) },
            confirmButton = {
                TextButton(
                    onClick = {
                        errorTitle = null
                        errorMessage = null
                    }
                ) { Text("OK") }
            }
        )
    }
}

@Composable
private fun CountryCallingCodeDialog(
    selectedCountry: ContactCountryCallingCode,
    onCountrySelected: (ContactCountryCallingCode) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredCountries = remember(searchQuery) {
        contactCountryCallingCodes.filter {
            it.countryName.contains(searchQuery.trim(), ignoreCase = true) ||
                it.callingCode.contains(searchQuery.trim())
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF34393F))
                .padding(16.dp)
        ) {
            Text("Select country", color = Color.White, fontSize = 20.sp)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search countries", color = Color(0xFFE8E8E8)) },
                singleLine = true,
                colors = contactTextFieldColors(),
                modifier = Modifier.fillMaxWidth()
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            ) {
                items(filteredCountries, key = { it.countryCode }) { country ->
                    TextButton(
                        onClick = { onCountrySelected(country) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = country.flagEmoji(),
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Text(
                                text = country.countryName,
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            Text(country.callingCode, color = Color.White)
                            if (country == selectedCountry) {
                                Text(
                                    "  ✓",
                                    color = Color(0xFFC9A86A),
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Cancel")
            }
        }
    }
}

internal fun contactPhoneKeyboardOptions() = KeyboardOptions(keyboardType = KeyboardType.Phone)

@Composable
internal fun ContactNewConversationAttachment(
    attachment: ContactNewConversationImage?,
    enabled: Boolean,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onPickImage,
            enabled = enabled,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            )
        ) {
            Text("Attach Image")
        }
        attachment?.let {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = it.preview,
                    contentDescription = "Selected image preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = "Image attached (${it.sizeBytes / 1024} KB)",
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRemoveImage,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = Color.White
                    )
                ) {
                    Text("Remove")
                }
            }
        }
    }
}

internal data class ContactNewConversationImage(
    val uri: Uri,
    val mimeType: String,
    val sizeBytes: Int,
    val bitmap: Bitmap,
    val preview: androidx.compose.ui.graphics.ImageBitmap
)

internal suspend fun Context.readContactNewConversationImage(
    uri: Uri
): ContactNewConversationImage {
    val mimeType = contentResolver.getType(uri)
        ?.takeIf(::isSupportedContactImageMimeType)
        ?: throw IOException("Choose a supported image file.")
    val bytes = contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            require(totalBytes <= MAX_NEW_CONVERSATION_IMAGE_BYTES) {
                "Image must be 10 MB or smaller."
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException("The selected image could not be read.")
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IOException("Choose a supported image file.")
    return ContactNewConversationImage(
        uri = uri,
        mimeType = mimeType,
        sizeBytes = bytes.size,
        bitmap = bitmap,
        preview = bitmap.asImageBitmap()
    )
}

internal fun isSupportedContactImageMimeType(mimeType: String): Boolean =
    mimeType.startsWith("image/", ignoreCase = true)

private const val MAX_NEW_CONVERSATION_IMAGE_BYTES = 10 * 1024 * 1024

internal fun isValidContactPhone(phone: String): Boolean {
    val trimmed = phone.trim()
    if (trimmed.isEmpty()) return true
    if (!trimmed.matches(Regex("^\\+?[0-9 ().-]+$"))) return false
    val digits = trimmed.filter(Char::isDigit)
    return digits.length in 7..15
}

@Composable
private fun contactTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = Color(0xFFE8E8E8),
    unfocusedLabelColor = Color(0xFFE8E8E8),
    cursorColor = Color.White,
    focusedBorderColor = Color(0xFFC9A86A),
    unfocusedBorderColor = Color(0xFFB8B8B8),
    focusedContainerColor = Color(0xFF34393F),
    unfocusedContainerColor = Color(0xFF34393F)
)
