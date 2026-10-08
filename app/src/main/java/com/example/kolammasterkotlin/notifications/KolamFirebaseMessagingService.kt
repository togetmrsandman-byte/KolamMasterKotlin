package com.kolammaster.app.notifications

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.kolammaster.app.AnnouncementRepository
import com.kolammaster.app.auth.SupabaseGuestAuth
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
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
                val appForeground = isApplicationForeground()
                val handled = handleSupportPush(
                    context = this,
                    payload = payload,
                    appForeground = appForeground
                ) {
                    postPushNotification(
                        payload = payload,
                        notificationId = payload.messageId ?: message.messageId
                            ?: payload.conversationId
                    )
                }
                if (handled && appForeground) refreshExistingBadgeCount()
            }
            is PushNotificationPayload.Announcement -> {
                Log.i(DIAGNOSTIC_TAG, "Announcement received; announcementId=${payload.announcementId}")
                if (isApplicationForeground()) {
                    ForegroundAnnouncementAlertStore.record(this, payload.announcementId)
                }
                val pendingAnnouncementCount = payload.announcementId?.let {
                    PendingAnnouncementStore.record(this, it)
                } ?: PendingAnnouncementStore.count(this)
                val unreadCount = pendingAnnouncementCount +
                    SupportUnreadStore.unreadMessageCount(this)
                LauncherBadgeHelper.updateExistingNotification(this, unreadCount)
                showNotification(
                    payload = payload.copy(
                        title = payload.title ?: message.notification?.title,
                        body = payload.body ?: message.notification?.body
                    ),
                    notificationId = payload.announcementId ?: message.messageId,
                    unreadCount = unreadCount
                )
                AnnouncementRepository.requestRefresh(this)
                enqueueAnnouncementBadgeRefresh()
                Log.i(DIAGNOSTIC_TAG, "Announcement badge refresh enqueue requested")
            }
        }
    }

    private fun enqueueAnnouncementBadgeRefresh() {
        val request = OneTimeWorkRequestBuilder<AnnouncementBadgeRefreshWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            ANNOUNCEMENT_BADGE_REFRESH_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private fun isApplicationForeground(): Boolean {
        val processState = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processState)
        return processState.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun showNotification(
        payload: PushNotificationPayload,
        notificationId: String?,
        unreadCount: Int,
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
                body = payload.body ?: run {
                    LauncherBadgeHelper.updateExistingNotification(this, unreadCount)
                    return
                }
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
            } else if (payload is PushNotificationPayload.Announcement) {
                payload.announcementId?.let { putExtra("announcementId", it) }
            }
        }

        val requestCode = notificationId?.hashCode() ?: type.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, SupportNotificationChannel.CHANNEL_ID)
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
        try {
            LauncherBadgeHelper.postActualNotification(this, builder, unreadCount)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Notification permission is not granted.", exception)
        }
    }

    private fun postPushNotification(
        payload: PushNotificationPayload,
        notificationId: String?,
        refreshAnnouncements: Boolean = false,
        enqueueBadgeRefresh: Boolean = false
    ) {
        serviceScope.launch {
            val repository = AnnouncementRepository(this@KolamFirebaseMessagingService)
            var announcements = emptyList<com.kolammaster.app.Announcement>()
            var currentAccount: com.kolammaster.app.auth.SupabaseAccount? = null
            try {
                currentAccount = SupabaseGuestAuth.currentAccount()
                if (currentAccount != null) {
                    SupportUnreadStore.useAccount(
                        this@KolamFirebaseMessagingService,
                        currentAccount.id
                    )
                    announcements = repository.loadCached(currentAccount)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Could not load cached announcements for the badge count.", exception)
            }
            var announcementUnreadCount = currentAccount?.let {
                PendingAnnouncementStore.reconcile(
                    this@KolamFirebaseMessagingService,
                    announcements.count { announcement -> !announcement.isRead },
                    announcements.map(com.kolammaster.app.Announcement::id).toSet()
                )
            } ?: PendingAnnouncementStore.count(this@KolamFirebaseMessagingService)
            var unreadCount = announcementUnreadCount +
                SupportUnreadStore.unreadMessageCount(this@KolamFirebaseMessagingService)
            if (payload is PushNotificationPayload.Announcement) {
                LauncherBadgeHelper.updateExistingNotification(this@KolamFirebaseMessagingService, unreadCount)
            }
            showNotification(payload, notificationId, unreadCount)
            if (enqueueBadgeRefresh) {
                enqueueAnnouncementBadgeRefresh()
                Log.i(DIAGNOSTIC_TAG, "Announcement badge refresh enqueue requested")
            }

            if (refreshAnnouncements && currentAccount != null) {
                try {
                    announcements = repository.refresh()
                    announcementUnreadCount = PendingAnnouncementStore.count(
                        this@KolamFirebaseMessagingService
                    )
                    unreadCount = announcementUnreadCount +
                        SupportUnreadStore.unreadMessageCount(
                            this@KolamFirebaseMessagingService
                        )
                    LauncherBadgeHelper.updateExistingNotification(
                        this@KolamFirebaseMessagingService,
                        unreadCount
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.w(TAG, "Could not refresh announcements for the badge count.", exception)
                }
            }
        }
    }

    private fun refreshExistingBadgeCount() {
        serviceScope.launch {
            try {
                val account = SupabaseGuestAuth.currentAccount()
                account?.let {
                    SupportUnreadStore.useAccount(this@KolamFirebaseMessagingService, it.id)
                }
                val announcements = account?.let {
                    AnnouncementRepository(this@KolamFirebaseMessagingService).loadCached(it)
                }.orEmpty()
                val announcementUnreadCount = account?.let {
                    PendingAnnouncementStore.reconcile(
                        this@KolamFirebaseMessagingService,
                        announcements.count { announcement -> !announcement.isRead },
                        announcements.map(com.kolammaster.app.Announcement::id).toSet()
                    )
                } ?: PendingAnnouncementStore.count(this@KolamFirebaseMessagingService)
                LauncherBadgeHelper.updateExistingNotification(
                    this@KolamFirebaseMessagingService,
                    announcementUnreadCount +
                        SupportUnreadStore.unreadMessageCount(this@KolamFirebaseMessagingService)
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Could not recalculate the launcher badge count.", exception)
            }
        }
    }

    private companion object {
        const val TAG = "PushNotifications"
        const val SUPPORT_TYPE = "support_message"
        const val ANNOUNCEMENT_TYPE = "announcement"
        const val SUPPORT_TITLE = "Kolam Master"
        const val SUPPORT_BODY = "You have a new message from Support."
        const val ANNOUNCEMENT_BADGE_REFRESH_WORK = "announcement_badge_refresh"
        const val DIAGNOSTIC_TAG = "ANNOUNCEMENT_BADGE_DEBUG"
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
