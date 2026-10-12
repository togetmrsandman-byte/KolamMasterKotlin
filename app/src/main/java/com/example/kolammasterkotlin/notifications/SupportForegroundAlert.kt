package com.kolammaster.app.notifications

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.kolammaster.app.kolamStandardButtonColors

@Composable
internal fun SupportForegroundAlertDialog(
    alert: SupportUnreadStore.ForegroundAlert,
    onOpen: () -> Unit,
    onLater: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("You have a new message from Support") },
        confirmButton = {
            TextButton(onClick = onOpen, colors = kolamStandardButtonColors()) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, colors = kolamStandardButtonColors()) {
                Text("Later")
            }
        }
    )
}

internal suspend fun <T> sendSupportReplyAndClearUnread(
    context: android.content.Context,
    conversationId: String,
    send: suspend () -> T
): T {
    val result = send()
    SupportUnreadStore.clearConversation(context, conversationId)
    return result
}
