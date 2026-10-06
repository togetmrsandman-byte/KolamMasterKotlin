package com.kolammaster.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ContactNewConversationDestination(
    onCreate: suspend (phone: String, subject: String, initialMessage: String) -> Unit
) {
    var phone by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var errorTitle by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

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
                    phone = it
                    validationError = null
                },
                enabled = !isSubmitting,
                label = { Text("Phone (optional)", color = Color(0xFFE8E8E8)) },
                singleLine = true,
                keyboardOptions = contactPhoneKeyboardOptions(),
                colors = contactTextFieldColors(),
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
                        !isValidContactPhone(phone) ->
                            "Please enter a valid phone number or leave it blank."
                        else -> null
                    }
                    if (validationError != null) return@Button

                    isSubmitting = true
                    scope.launch {
                        try {
                            onCreate(phone.trim(), trimmedSubject, trimmedMessage)
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

internal fun contactPhoneKeyboardOptions() = KeyboardOptions(keyboardType = KeyboardType.Phone)

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
