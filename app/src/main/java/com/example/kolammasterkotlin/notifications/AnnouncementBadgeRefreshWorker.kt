package com.kolammaster.app.notifications

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kolammaster.app.AnnouncementRepository
import com.kolammaster.app.auth.SupabaseGuestAuth
import kotlinx.coroutines.CancellationException

internal class AnnouncementBadgeRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        Log.i(TAG, "Announcement badge refresh worker started")
        return try {
            val account = SupabaseGuestAuth.currentAccount()
            Log.i(TAG, "Current Supabase account available=${account != null}")
            if (account == null) return Result.retry()
            SupportUnreadStore.useAccount(applicationContext, account.id)
            try {
                AnnouncementRepository(applicationContext).refresh().also {
                    Log.i(TAG, "Announcement refresh succeeded")
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.w(TAG, "Announcement refresh failed", exception)
                throw exception
            }
            val announcementUnreadCount = PendingAnnouncementStore.count(applicationContext)
            Log.i(TAG, "Unread announcement count after refresh=$announcementUnreadCount")
            val unreadCount = announcementUnreadCount +
                SupportUnreadStore.unreadMessageCount(applicationContext)
            Log.i(TAG, "Combined unread count=$unreadCount")
            Log.i(TAG, "Calling LauncherBadgeHelper.updateExistingNotification")
            LauncherBadgeHelper.updateExistingNotification(applicationContext, unreadCount)
            Result.success()
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.w(TAG, "Could not refresh announcements for the launcher badge.", exception)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "ANNOUNCEMENT_BADGE_DEBUG"
    }
}
