package com.kolammaster.app.notifications

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kolammaster.app.MainActivity
import com.kolammaster.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class KolamFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        SupportNotificationChannel.create(this)
    }

    override fun onNewToken(token: String) {
        serviceScope.launch {
            try {
                PushTokenRepository(applicationContext).registerCurrentToken(token)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Could not register the refreshed push token.", exception)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val payload = PushNotificationPayloadParser.parse(message.data) ?: return
        when (payload) {
            is PushNotificationPayload.Support -> {
                handleSupportPush(
                    context = this,
                    payload = payload,
                    appForeground = isApplicationForeground()
                ) {
                    showNotification(
                        payload = payload,
                        notificationId = payload.messageId ?: message.messageId
                            ?: payload.conversationId
                    )
                }
            }
            is PushNotificationPayload.Announcement ->
                showNotification(payload, notificationId = message.messageId)
        }
    }

    private fun isApplicationForeground(): Boolean {
        val processState = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processState)
        return processState.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showNotification(
        payload: PushNotificationPayload,
        notificationId: String?
    ) {
        val support = payload as? PushNotificationPayload.Support
        val title: String
        val body: String
        val type: String
        when (payload) {
            is PushNotificationPayload.Support -> {
                title = SUPPORT_TITLE
                body = SUPPORT_BODY
                type = SUPPORT_TYPE
            }
            is PushNotificationPayload.Announcement -> {
                title = payload.title ?: SUPPORT_TITLE
                body = payload.body ?: ANNOUNCEMENT_BODY
                type = ANNOUNCEMENT_TYPE
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("type", type)
            if (support != null) {
                putExtra("sender", "ADMIN")
                support.conversationId?.let { putExtra("conversationId", it) }
                support.messageId?.let { putExtra("messageId", it) }
            }
        }

        val requestCode = notificationId?.hashCode() ?: type.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SupportNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_support)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                if (support != null) NotificationCompat.CATEGORY_MESSAGE
                else NotificationCompat.CATEGORY_STATUS
            )
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        val tag = notificationId ?: type
        try {
            NotificationManagerCompat.from(this).notify(tag, 0, notification)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Notification permission is not granted.", exception)
        }
    }

    private companion object {
        const val TAG = "PushNotifications"
        const val SUPPORT_TYPE = "support_message"
        const val ANNOUNCEMENT_TYPE = "announcement"
        const val SUPPORT_TITLE = "Kolam Master"
        const val SUPPORT_BODY = "You have a new message from Support."
        const val ANNOUNCEMENT_BODY = "A new announcement is available."
    }
}

internal fun handleSupportPush(
    context: android.content.Context,
    payload: PushNotificationPayload.Support,
    appForeground: Boolean,
    showBackgroundNotification: () -> Unit
): Boolean {
    val isActiveConversation = SupportUnreadStore.isActiveConversation(payload.conversationId)
    val isNewMessage = SupportUnreadStore.recordSupportMessage(
        context = context,
        conversationId = payload.conversationId,
        messageId = payload.messageId
    )
    if (!isNewMessage) return false

    if (isActiveConversation && !payload.conversationId.isNullOrBlank()) {
        SupportUnreadStore.notifyActiveConversationRefresh(context, payload.conversationId)
    }
    if (appForeground) {
        SupportUnreadStore.recordForegroundAlert(
            context = context,
            conversationId = payload.conversationId,
            messageId = payload.messageId
        )
    } else {
        showBackgroundNotification()
    }
    return true
}
