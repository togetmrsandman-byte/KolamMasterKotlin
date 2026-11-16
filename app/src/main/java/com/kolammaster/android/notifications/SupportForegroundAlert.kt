package com.kolammaster.android.notifications

import androidx.compose.runtime.Composable
import com.kolammaster.android.currentAppString

@Composable
internal fun SupportForegroundAlertDialog(
    alert: SupportUnreadStore.ForegroundAlert,
    languageIdentifier: String,
    onOpen: () -> Unit,
    onLater: () -> Unit
) {
    ForegroundAlertDialog(
        title = currentAppString("support_alert_title"),
        message = currentAppString("announcement_alert_message"),
        languageIdentifier = languageIdentifier,
        onOpen = onOpen,
        onLater = onLater
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
