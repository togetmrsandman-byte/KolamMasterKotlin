package com.kolammaster.app

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.ui.res.stringResource
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
    val contactUsTitle = stringResource(R.string.contact_us)
    val cameraPermissionTitle = stringResource(R.string.contact_camera_permission_title)
    val cameraPermissionMessage = stringResource(R.string.contact_camera_permission_message)
    val selectedImageReadFailed = stringResource(R.string.contact_image_read_failed)
    val cameraOpenFailed = stringResource(R.string.camera_open_failed)
    val subjectRequired = stringResource(R.string.subject_required)
    val messageRequired = stringResource(R.string.message_required)
    val phoneRequired = stringResource(R.string.valid_phone_required)
    val imageSizeLimit = stringResource(R.string.contact_image_size_limit)
    val sendFailed = stringResource(R.string.contact_send_failed)
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
                errorTitle = contactUsTitle
                errorMessage = exception.message
                    ?: selectedImageReadFailed
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
                errorTitle = contactUsTitle
                errorMessage = exception.message ?: cameraOpenFailed
            }
        } else {
            errorTitle = cameraPermissionTitle
            errorMessage = cameraPermissionMessage
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KolamBackground)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.new_conversation), color = KolamGold, fontSize = 28.sp)
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
                readOnly = isSubmitting,
                enabled = true,
                label = { Text(stringResource(R.string.phone_number), color = KolamContainerSecondary) },
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
                            color = KolamText,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("▾", color = KolamText)
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
                readOnly = isSubmitting,
                enabled = true,
                label = { Text(stringResource(R.string.subject), color = KolamContainerSecondary) },
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
                readOnly = isSubmitting,
                enabled = true,
                label = { Text(stringResource(R.string.message), color = KolamContainerSecondary) },
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
                    color = KolamError,
                    textAlign = TextAlign.Center
                )
            }
            Button(
                onClick = {
                    if (isSubmitting) return@Button
                    val trimmedSubject = subject.trim()
                    val trimmedMessage = message.trim()
                    validationError = when {
                        trimmedSubject.isEmpty() -> subjectRequired
                        trimmedMessage.isEmpty() -> messageRequired
                        !isValidLocalContactPhone(phone) ->
                            phoneRequired
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
                                        imageSizeLimit
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
                                errorTitle = contactUsTitle
                                errorMessage = sendFailed
                            }
                        } finally {
                            isSubmitting = false
                        }
                    }
                },
                enabled = !isSubmitting,
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary,
                    disabledContentColor = KolamButtonText
                )
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = KolamButtonText,
                        trackColor = KolamBorder,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.sending))
                } else {
                    Text(stringResource(R.string.send))
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
                    },
                    colors = kolamStandardButtonColors()
                ) { Text(stringResource(R.string.ok)) }
            },
            containerColor = KolamCoolSilver
        )
    }
}

@Composable
internal fun CountryCallingCodeDialog(
    selectedCountry: ContactCountryCallingCode?,
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
                .background(KolamCoolSilver, RoundedCornerShape(12.dp))
                .border(
                    BorderStroke(1.dp, KolamSilverBorder),
                    RoundedCornerShape(12.dp)
                )
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.select_country), color = KolamText, fontSize = 20.sp)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.search_countries), color = KolamContainerSecondary) },
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
                        modifier = Modifier.fillMaxWidth(),
                        colors = kolamStandardButtonColors()
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
                                color = KolamButtonText,
                                modifier = Modifier.weight(1f)
                            )
                            Text(country.callingCode, color = KolamButtonText)
                            if (country == selectedCountry) {
                                Text(
                                    "  ✓",
                                    color = KolamButtonText,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
                colors = kolamStandardButtonColors()
            ) {
                Text(stringResource(R.string.cancel))
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
                contentColor = KolamButtonText,
                disabledContainerColor = MaterialTheme.colorScheme.primary,
                disabledContentColor = KolamButtonText
            )
        ) {
            Text(stringResource(R.string.attach_image))
        }
        attachment?.let {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    bitmap = it.preview,
                    contentDescription = stringResource(R.string.selected_image_preview),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp)
                )
                Text(
                    text = stringResource(R.string.image_attached, it.sizeBytes / 1024),
                    color = KolamText,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRemoveImage,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = KolamButtonText,
                        disabledContainerColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = KolamButtonText
                    )
                ) {
                    Text(stringResource(R.string.remove))
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
        ?: throw IOException(getString(R.string.contact_supported_image_required))
    val bytes = contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            totalBytes += count
            require(totalBytes <= MAX_NEW_CONVERSATION_IMAGE_BYTES) {
                getString(R.string.contact_image_size_limit)
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IOException(getString(R.string.contact_image_read_failed))
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: throw IOException(getString(R.string.contact_supported_image_required))
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
    focusedTextColor = KolamText,
    unfocusedTextColor = KolamText,
    disabledTextColor = KolamContainerSecondary,
    focusedLabelColor = KolamContainerSecondary,
    unfocusedLabelColor = KolamContainerSecondary,
    disabledLabelColor = KolamContainerSecondary,
    cursorColor = KolamBronze,
    focusedBorderColor = KolamBronze,
    unfocusedBorderColor = KolamBorder,
    disabledBorderColor = KolamBorder,
    focusedContainerColor = KolamCoolSilver,
    unfocusedContainerColor = KolamCoolSilver,
    disabledContainerColor = KolamCoolSilver
)
