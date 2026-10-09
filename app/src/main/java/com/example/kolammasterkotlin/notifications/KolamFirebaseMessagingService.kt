package com.kolammaster.app.notifications

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.RemoteViews
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
        Log.i(
            FCM_DIAGNOSTIC_TAG,
            "FCM received; notificationPresent=${message.notification != null}, " +
                "data=${message.data}, messageId=${message.messageId}"
        )
        val payload = PushNotificationPayloadParser.parse(message.data) ?: return
        when (payload) {
            is PushNotificationPayload.Support -> {
                logFcmDiagnostic("Support message received")
                val appForeground = isApplicationForeground()
                Log.i(
                    FCM_DIAGNOSTIC_TAG,
                    "Support dispatch; sender=ADMIN, appForeground=$appForeground, " +
                        "conversationId=${payload.conversationId}, messageId=${payload.messageId}"
                )
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
                val notificationPayload = payload.copy(
                    title = payload.title ?: message.notification?.title,
                    body = payload.body ?: message.notification?.body
                )
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
                val announcementId = notificationPayload.announcementId
                if (notificationPayload.title.isNullOrBlank() || notificationPayload.body.isNullOrBlank()) {
                    if (announcementId.isNullOrBlank()) {
                        Log.e(
                            FCM_DIAGNOSTIC_TAG,
                            "Announcement content is missing and no announcementId was received."
                        )
                        return
                    }
                    postAnnouncementNotification(
                        payload = notificationPayload,
                        notificationId = announcementId,
                        unreadCount = unreadCount
                    )
                } else {
                    Log.i(
                        FCM_DIAGNOSTIC_TAG,
                        "Announcement calling showNotification; type=announcement, " +
                            "announcementId=$announcementId, " +
                            "title=${notificationPayload.title}, body=${notificationPayload.body}"
                    )
                    showNotification(
                        payload = notificationPayload,
                        notificationId = announcementId ?: message.messageId,
                        unreadCount = unreadCount
                    )
                }
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

    private fun postAnnouncementNotification(
        payload: PushNotificationPayload.Announcement,
        notificationId: String,
        unreadCount: Int
    ) {
        serviceScope.launch {
            val account = try {
                SupabaseGuestAuth.currentAccount()
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(FCM_DIAGNOSTIC_TAG, "Could not load the current account for Announcement content.", exception)
                null
            }
            val repository = AnnouncementRepository(applicationContext)
            val cachedAnnouncement = try {
                account?.let { currentAccount ->
                    repository.loadCached(currentAccount)
                        .firstOrNull { it.id == payload.announcementId }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(
                    FCM_DIAGNOSTIC_TAG,
                    "Could not load cached Announcement content; announcementId=${payload.announcementId}",
                    exception
                )
                null
            }
            val cachedContentIsComplete = cachedAnnouncement != null &&
                (!payload.title.isNullOrBlank() || cachedAnnouncement.subject.isNotBlank()) &&
                (!payload.body.isNullOrBlank() || cachedAnnouncement.message.isNotBlank())
            val announcement = if (cachedContentIsComplete) {
                cachedAnnouncement
            } else if (account != null) {
                try {
                    repository.refresh().firstOrNull { it.id == payload.announcementId }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    Log.e(
                        FCM_DIAGNOSTIC_TAG,
                        "Could not refresh Announcement content; announcementId=${payload.announcementId}",
                        exception
                    )
                    null
                }
            } else {
                null
            }
            val resolvedPayload = payload.copy(
                title = payload.title?.takeIf(String::isNotBlank)
                    ?: announcement?.subject?.takeIf(String::isNotBlank),
                body = payload.body?.takeIf(String::isNotBlank)
                    ?: announcement?.message?.takeIf(String::isNotBlank)
            )
            if (resolvedPayload.body.isNullOrBlank()) {
                Log.e(
                    FCM_DIAGNOSTIC_TAG,
                    "Announcement content unavailable; announcementId=${payload.announcementId}"
                )
                return@launch
            }
            val currentUnreadCount = if (announcement != null) {
                PendingAnnouncementStore.count(applicationContext) +
                    SupportUnreadStore.unreadMessageCount(applicationContext)
            } else {
                unreadCount
            }
            Log.i(
                FCM_DIAGNOSTIC_TAG,
                "Announcement calling showNotification; type=announcement, " +
                    "announcementId=${payload.announcementId}, " +
                    "title=${resolvedPayload.title}, body=${resolvedPayload.body}"
            )
            showNotification(resolvedPayload, notificationId, currentUnreadCount)
        }
    }

    private fun isApplicationForeground(): Boolean {
        val processState = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(processState)
        return processState.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    override fun onDestroy() {
        logFcmDiagnostic("Firebase messaging service destroyed; cancelling service scope")
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun logFcmDiagnostic(message: String) {
        Log.i(FCM_DIAGNOSTIC_TAG, "timestampMs=${System.currentTimeMillis()} $message")
    }

    private fun showNotification(
        payload: PushNotificationPayload,
        notificationId: String?,
        unreadCount: Int,
    ) {
        logFcmDiagnostic("showNotification started; type=${payload::class.simpleName}")
        val support = payload as? PushNotificationPayload.Support
        val title: String
        val body: String
        val type: String
        val notificationTag: String
        val notificationIdValue: Int
        when (payload) {
            is PushNotificationPayload.Support -> {
                title = SUPPORT_TITLE
                body = SUPPORT_BODY
                type = SUPPORT_TYPE
                notificationTag = LauncherBadgeHelper.SUPPORT_TAG
                notificationIdValue = LauncherBadgeHelper.SUPPORT_ID
            }
            is PushNotificationPayload.Announcement -> {
                title = payload.title ?: SUPPORT_TITLE
                body = payload.body ?: run {
                    LauncherBadgeHelper.updateExistingNotification(this, unreadCount)
                    return
                }
                type = ANNOUNCEMENT_TYPE
                notificationTag = LauncherBadgeHelper.ANNOUNCEMENT_TAG
                notificationIdValue = LauncherBadgeHelper.ANNOUNCEMENT_ID
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
        val logo = requireNotNull(
            assets.open("kolam-logo-notification.png").use(BitmapFactory::decodeStream)
        ) { "Could not decode the Kolam Master logo." }
        val collapsedContent = RemoteViews(packageName, R.layout.notification_kolam_collapsed).apply {
            setImageViewBitmap(R.id.notification_logo, logo)
            setTextViewText(R.id.notification_title, title)
            setTextViewText(R.id.notification_body, body)
        }
        val expandedContent = RemoteViews(packageName, R.layout.notification_kolam_expanded).apply {
            setImageViewBitmap(R.id.notification_logo, logo)
            setTextViewText(R.id.notification_title, title)
            setTextViewText(R.id.notification_body, body)
        }
        val builder = NotificationCompat.Builder(this, SupportNotificationChannel.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kolam_experiment)
            .setLargeIcon(logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedContent)
            .setCustomBigContentView(expandedContent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                if (support != null) NotificationCompat.CATEGORY_MESSAGE
                else NotificationCompat.CATEGORY_STATUS
            )
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        Log.i(
            FCM_DIAGNOSTIC_TAG,
            "Posting custom notification; notificationId=$notificationId, " +
                "bodyBlank=${body.isBlank()}, collapsedRemoteViewsCreated=true, " +
                "expandedRemoteViewsCreated=true"
        )
        try {
            logFcmDiagnostic("Posting notification through LauncherBadgeHelper")
            LauncherBadgeHelper.postActualNotification(
                this,
                builder,
                unreadCount,
                notificationTag,
                notificationIdValue
            )
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
        if (payload is PushNotificationPayload.Support) {
            val localUnreadCount = PendingAnnouncementStore.count(this) +
                SupportUnreadStore.unreadMessageCount(this)
            LauncherBadgeHelper.updateExistingNotification(this, localUnreadCount)
            logFcmDiagnostic("About to call showNotification using locally persisted unread state")
            showNotification(payload, notificationId, localUnreadCount)
            logFcmDiagnostic("showNotification returned")
        }
        logFcmDiagnostic("About to launch postPushNotification coroutine")
        serviceScope.launch {
            logFcmDiagnostic("postPushNotification coroutine started")
            try {
                val repository = AnnouncementRepository(this@KolamFirebaseMessagingService)
                var announcements = emptyList<com.kolammaster.app.Announcement>()
                var currentAccount: com.kolammaster.app.auth.SupabaseAccount? = null
                try {
                    logFcmDiagnostic("Calling SupabaseGuestAuth.currentAccount()")
                    currentAccount = SupabaseGuestAuth.currentAccount()
                    logFcmDiagnostic(
                        "SupabaseGuestAuth.currentAccount() returned; " +
                            "accountFound=${currentAccount != null}"
                    )
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
                LauncherBadgeHelper.updateExistingNotification(
                    this@KolamFirebaseMessagingService,
                    unreadCount
                )
                if (payload !is PushNotificationPayload.Support) {
                    logFcmDiagnostic("About to call showNotification")
                    showNotification(payload, notificationId, unreadCount)
                    logFcmDiagnostic("showNotification returned")
                }
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
            } catch (exception: CancellationException) {
                logFcmDiagnostic("postPushNotification coroutine cancelled")
                throw exception
            }
        }
        logFcmDiagnostic("postPushNotification coroutine launch returned")
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
        const val FCM_DIAGNOSTIC_TAG = "KolamFCM"
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
    if (!isNewMessage) {
        Log.i(
            "KolamFCM",
            "Support duplicate ignored; conversationId=${payload.conversationId}, " +
                "messageId=${payload.messageId}"
        )
        return false
    }

    if (isActiveConversation && !payload.conversationId.isNullOrBlank()) {
        SupportUnreadStore.notifyActiveConversationRefresh(context, payload.conversationId)
    }
    if (appForeground) {
        SupportUnreadStore.recordForegroundAlert(
            context = context,
            conversationId = payload.conversationId,
            messageId = payload.messageId
        )
    }
    Log.i(
        "KolamFCM",
        "Support calling showNotification; appForeground=$appForeground, " +
            "conversationId=${payload.conversationId}, messageId=${payload.messageId}"
    )
    showBackgroundNotification()
    return true
}
