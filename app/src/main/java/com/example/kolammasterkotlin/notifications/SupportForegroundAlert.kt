package com.kolammaster.app.notifications

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.kolammaster.app.R

@Composable
internal fun SupportForegroundAlertDialog(
    alert: SupportUnreadStore.ForegroundAlert,
    languageIdentifier: String,
    onOpen: () -> Unit,
    onLater: () -> Unit
) {
    ForegroundAlertDialog(
        title = stringResource(R.string.support_alert_title),
        message = stringResource(R.string.announcement_alert_message),
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
