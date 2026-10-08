package com.kolammaster.app

import android.content.Context
import android.util.Log
import com.kolammaster.app.auth.SupabaseAccount
import com.kolammaster.app.auth.SupabaseGuestAuth
import com.kolammaster.app.notifications.LauncherBadgeHelper
import com.kolammaster.app.notifications.PendingAnnouncementStore
import com.kolammaster.app.notifications.SupportUnreadStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Instant

internal data class Announcement(
    val id: String,
    val subject: String,
    val message: String,
    val imageUrl: String?,
    val createdAt: String,
    val readAt: String?
) {
    val isRead: Boolean get() = readAt != null
}

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun filterVisibleAnnouncements(
    announcements: List<Announcement>,
    accountCreatedAt: String?
): List<Announcement> {
    val accountCreatedInstant = accountCreatedAt?.let(::parseAnnouncementInstant)
        ?: return emptyList()
    return announcements
        .filter { UUID_PATTERN.matches(it.id) }
        .filter { announcement ->
            parseAnnouncementInstant(announcement.createdAt)?.let {
                it > accountCreatedInstant
            } == true
        }
        .sortedByDescending { parseAnnouncementInstant(it.createdAt) }
        .distinctBy(Announcement::id)
        .take(MAX_ANNOUNCEMENTS)
}

internal fun announcementIdsForMarkAll(announcements: List<Announcement>): List<String> =
    announcements.map(Announcement::id)

internal fun announcementContentCacheKey(userId: String): String =
    "kolam-master-announcement-content-cache:$userId"

@OptIn(kotlin.time.ExperimentalTime::class)
private fun parseAnnouncementInstant(value: String): Instant? =
    try {
        Instant.parse(value)
    } catch (_: IllegalArgumentException) {
        null
    }

private const val MAX_ANNOUNCEMENTS = 15
private val UUID_PATTERN = Regex(
    "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)

internal interface AnnouncementAuthDataSource {
    suspend fun currentAccount(): SupabaseAccount?
    suspend fun accessTokenFor(userId: String): String
}

private object SupabaseAnnouncementAuthDataSource : AnnouncementAuthDataSource {
    override suspend fun currentAccount(): SupabaseAccount? =
        SupabaseGuestAuth.currentAccount()

    override suspend fun accessTokenFor(userId: String): String =
        SupabaseGuestAuth.accessTokenFor(userId)
}

@OptIn(kotlin.time.ExperimentalTime::class)
internal class AnnouncementRepository(
    context: Context,
    private val auth: AnnouncementAuthDataSource = SupabaseAnnouncementAuthDataSource,
    private val http: ContactHttpTransport = UrlConnectionContactHttpTransport,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL
) {
    private val refreshMutex = Mutex()
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun loadCached(account: SupabaseAccount): List<Announcement> {
        val key = cacheKey(account.id)
        val encoded = preferences.getString(key, null) ?: run {
            val legacySnapshot = preferences.getString(legacyCacheKey(account.id), null)
                ?: return emptyList()
            check(preferences.edit().putString(key, legacySnapshot).commit()) {
                "Could not migrate the announcement cache."
            }
            legacySnapshot
        }
        return try {
            val rows = JSONArray(encoded)
            val cached = buildList {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val id = row.optString("id").takeIf(String::isNotBlank) ?: continue
                    val createdAt = row.optString("created_at").takeIf(String::isNotBlank)
                        ?: continue
                    if (parseAnnouncementInstant(createdAt) == null) continue
                    add(
                        Announcement(
                            id = id,
                            subject = row.optString("subject"),
                            message = row.optString("message"),
                            imageUrl = row.optString("image_url")
                                .takeIf(String::isNotBlank),
                            createdAt = createdAt,
                            readAt = row.optString("read_at").takeIf(String::isNotBlank)
                        )
                    )
                }
            }
            filterVisibleAnnouncements(cached, account.createdAt)
        } catch (exception: JSONException) {
            Log.w(TAG, "Ignoring invalid cached announcements.", exception)
            emptyList()
        }
    }

    suspend fun refresh(): List<Announcement> = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            val account = auth.currentAccount()
                ?: throw IllegalStateException("There is no authenticated Supabase account.")
            require(account.id.isNotBlank()) {
                "The authenticated Supabase account has no user ID."
            }
            val accountCreatedAt = account.createdAt
                ?: throw IllegalStateException("The Supabase account has no creation timestamp.")
            if (parseAnnouncementInstant(accountCreatedAt) == null) {
                throw IllegalStateException("The Supabase account creation timestamp is invalid.")
            }
            val accessToken = auth.accessTokenFor(account.id)
            val announcementResponse = request(
                method = "GET",
                path = "/rest/v1/announcements?select=id,subject,message,image_url,created_at" +
                    "&order=created_at.desc",
                accessToken = accessToken
            )
            val records = parseAnnouncements(announcementResponse)
            val visible = filterVisibleAnnouncements(records, accountCreatedAt)

            val readAtByAnnouncementId = if (visible.isEmpty()) {
                emptyMap()
            } else {
                val ids = visible.joinToString(",") { it.id }
                val query = "announcement_id=in.($ids)&user_id=eq.${encode(account.id)}" +
                    "&select=announcement_id,read_at"
                parseReadRows(
                    request(
                        method = "GET",
                        path = "/rest/v1/announcement_reads?$query",
                        accessToken = accessToken
                    )
                )
            }
            val result = visible.map { announcement ->
                announcement.copy(readAt = readAtByAnnouncementId[announcement.id])
            }

            requireSameAccount(account.id)
            saveCache(account.id, result)
            val announcementUnreadCount = result.count { !it.isRead }
            PendingAnnouncementStore.reconcile(
                applicationContext,
                announcementUnreadCount,
                result.map(Announcement::id).toSet()
            )
            SupportUnreadStore.useAccount(applicationContext, account.id)
            LauncherBadgeHelper.updateExistingNotification(
                applicationContext,
                PendingAnnouncementStore.count(applicationContext) +
                    SupportUnreadStore.unreadMessageCount(applicationContext)
            )
            result
        }
    }

    suspend fun markAsRead(announcementId: String): String =
        upsertReadRows(listOf(announcementId))

    suspend fun markAllAsRead(announcementIds: List<String>): String =
        upsertReadRows(announcementIds)

    private suspend fun upsertReadRows(announcementIds: List<String>): String =
        withContext(Dispatchers.IO) {
            val ids = announcementIds
                .filter { UUID_PATTERN.matches(it) }
                .distinct()
            val timestamp = currentTimestamp()
            if (ids.isEmpty()) return@withContext timestamp
            val account = auth.currentAccount()
                ?: throw IllegalStateException("There is no authenticated Supabase account.")
            require(account.id.isNotBlank()) {
                "The authenticated Supabase account has no user ID."
            }
            val accessToken = auth.accessTokenFor(account.id)
            val body = JSONArray().apply {
                ids.forEach { id ->
                    put(
                        JSONObject()
                            .put("announcement_id", id)
                            .put("user_id", account.id)
                            .put("read_at", timestamp)
                    )
                }
            }.toString()
            val response = request(
                method = "POST",
                path = "/rest/v1/announcement_reads?on_conflict=announcement_id,user_id",
                accessToken = accessToken,
                body = body,
                prefer = "resolution=merge-duplicates,return=minimal"
            )
            check(response.statusCode in 200..299) {
                "Supabase announcement read update failed with HTTP ${response.statusCode}."
            }
            requireSameAccount(account.id)
            val cached = loadCached(account)
            val updated = cached.map { item ->
                if (item.id in ids) item.copy(readAt = timestamp) else item
            }
            saveCache(account.id, updated)
            PendingAnnouncementStore.reconcile(
                applicationContext,
                updated.count { !it.isRead },
                updated.map(Announcement::id).toSet(),
                resolvedIds = ids.toSet()
            )
            SupportUnreadStore.useAccount(applicationContext, account.id)
            LauncherBadgeHelper.updateExistingNotification(
                applicationContext,
                PendingAnnouncementStore.count(applicationContext) +
                    SupportUnreadStore.unreadMessageCount(applicationContext)
            )
            timestamp
        }

    private suspend fun requireSameAccount(userId: String) {
        val current = auth.currentAccount()
        check(current != null && current.id == userId) {
            "The authenticated account changed during the announcement request."
        }
    }

    private fun parseAnnouncements(response: ContactHttpResponse): List<Announcement> {
        val rows = JSONArray(response.body)
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").takeIf(String::isNotBlank) ?: continue
                val createdAt = row.optString("created_at").takeIf(String::isNotBlank)
                    ?: continue
                if (parseAnnouncementInstant(createdAt) == null) continue
                add(
                    Announcement(
                        id = id,
                        subject = row.optString("subject"),
                        message = row.optString("message"),
                        imageUrl = row.optString("image_url").takeIf(String::isNotBlank),
                        createdAt = createdAt,
                        readAt = null
                    )
                )
            }
        }
    }

    private fun parseReadRows(response: ContactHttpResponse): Map<String, String?> {
        val rows = JSONArray(response.body)
        return buildMap {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("announcement_id").takeIf(String::isNotBlank)
                    ?: continue
                put(id, row.optString("read_at").takeIf(String::isNotBlank))
            }
        }
    }

    private fun saveCache(userId: String, announcements: List<Announcement>) {
        val snapshot = JSONArray().apply {
            announcements.take(MAX_ANNOUNCEMENTS).forEach { announcement ->
                put(
                    JSONObject()
                        .put("id", announcement.id)
                        .put("subject", announcement.subject)
                        .put("message", announcement.message)
                        .put("image_url", announcement.imageUrl)
                        .put("created_at", announcement.createdAt)
                        .put("read_at", announcement.readAt)
                )
            }
        }
        check(preferences.edit().putString(cacheKey(userId), snapshot.toString()).commit()) {
            "Could not persist the announcement cache."
        }
    }

    private fun request(
        method: String,
        path: String,
        accessToken: String,
        body: String? = null,
        prefer: String? = null
    ): ContactHttpResponse = http.execute(
        method = method,
        url = "$supabaseUrl$path",
        headers = buildMap {
            put("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            put("Authorization", "Bearer $accessToken")
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            prefer?.let { put("Prefer", it) }
        },
        body = body?.toByteArray(Charsets.UTF_8),
        contentType = body?.let { "application/json" }
    ).also { response ->
        check(response.statusCode in 200..299) {
            "Supabase announcement request failed with HTTP ${response.statusCode}."
        }
    }

    private fun currentTimestamp(): String =
        Instant.fromEpochMilliseconds(System.currentTimeMillis()).toString()

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun cacheKey(userId: String): String = announcementContentCacheKey(userId)

    private fun legacyCacheKey(userId: String): String = "snapshot:$userId"

    companion object {
        private const val PREFERENCES_NAME = "kolam_master_announcements"
        private const val TAG = "Announcements"
        const val REFRESH_SIGNAL_KEY = "refresh_signal"

        fun requestRefresh(context: Context) {
            val preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
            )
            preferences.edit()
                .putLong(
                    REFRESH_SIGNAL_KEY,
                    preferences.getLong(REFRESH_SIGNAL_KEY, 0L) + 1L
                )
                .apply()
        }

        fun refreshSignal(context: Context): Long =
            context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE
            ).getLong(REFRESH_SIGNAL_KEY, 0L)

        fun displayDate(value: String): String? {
            val instant = try {
                Instant.parse(value)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).apply {
                timeZone = TimeZone.getDefault()
            }.format(java.util.Date(instant.toEpochMilliseconds()))
        }
    }
}
