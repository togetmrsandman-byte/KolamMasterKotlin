package com.kolammaster.app.notifications

import android.content.Context
import com.kolammaster.app.BuildConfig
import com.kolammaster.app.ContactHttpResponse
import com.kolammaster.app.ContactHttpTransport
import com.kolammaster.app.UrlConnectionContactHttpTransport
import com.kolammaster.app.auth.SupabaseAccount
import com.kolammaster.app.auth.SupabaseGuestAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal interface PushTokenAuthDataSource {
    suspend fun currentAccount(): SupabaseAccount?
    suspend fun accessTokenFor(userId: String): String
}

private object SupabasePushTokenAuthDataSource : PushTokenAuthDataSource {
    override suspend fun currentAccount(): SupabaseAccount? =
        SupabaseGuestAuth.currentAccount()

    override suspend fun accessTokenFor(userId: String): String =
        SupabaseGuestAuth.accessTokenFor(userId)
}

internal class PushTokenRepository(
    private val context: Context,
    private val auth: PushTokenAuthDataSource = SupabasePushTokenAuthDataSource,
    private val http: ContactHttpTransport = UrlConnectionContactHttpTransport,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL
) {
    suspend fun registerCurrentToken(token: String) = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "Firebase returned an empty registration token." }
        val account = auth.currentAccount()?.takeUnless(SupabaseAccount::isGuest)
            ?: return@withContext
        val accessToken = auth.accessTokenFor(account.id)
        requireSameAuthenticatedAccount(account.id)

        val response = execute(
            method = "POST",
            url = "$supabaseUrl/rest/v1/push_tokens?on_conflict=user_id,token",
            accessToken = accessToken,
            body = buildFcmTokenRow(account.id, token, currentTimestamp()).toJson(),
            prefer = "resolution=merge-duplicates,return=minimal"
        )
        check(response.statusCode in 200..299) {
            "Supabase push token registration failed with HTTP ${response.statusCode}."
        }

        val currentAccount = auth.currentAccount()
        if (currentAccount == null || currentAccount.isGuest || currentAccount.id != account.id) {
            deleteTokenRow(account.id, token, accessToken)
            return@withContext
        }

        val previous = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(ASSOCIATED_TOKEN_KEY, null)
        val previousUserId = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(ASSOCIATED_USER_KEY, null)
        if (previousUserId == account.id && !previous.isNullOrBlank() && previous != token) {
            deleteTokenRow(account.id, previous, accessToken)
        }
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(ASSOCIATED_USER_KEY, account.id)
            .putString(ASSOCIATED_TOKEN_KEY, token)
            .apply()
    }

    suspend fun unregisterCurrentUserToken() = withContext(Dispatchers.IO) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val userId = preferences.getString(ASSOCIATED_USER_KEY, null) ?: return@withContext
        val token = preferences.getString(ASSOCIATED_TOKEN_KEY, null) ?: return@withContext
        val account = auth.currentAccount()
        if (account == null || account.isGuest || account.id != userId) return@withContext
        val accessToken = auth.accessTokenFor(userId)
        requireSameAuthenticatedAccount(userId)
        deleteTokenRow(userId, token, accessToken)
        preferences.edit()
            .remove(ASSOCIATED_USER_KEY)
            .remove(ASSOCIATED_TOKEN_KEY)
            .apply()
    }

    private suspend fun requireSameAuthenticatedAccount(userId: String) {
        val current = auth.currentAccount()
        check(current != null && !current.isGuest && current.id == userId) {
            "The authenticated account changed during push token registration."
        }
    }

    private fun deleteTokenRow(userId: String, token: String, accessToken: String) {
        val query = "user_id=eq.${encode(userId)}&token=eq.${encode(token)}" +
            "&provider=eq.$FCM_PROVIDER"
        val response = execute(
            method = "DELETE",
            url = "$supabaseUrl/rest/v1/push_tokens?$query",
            accessToken = accessToken
        )
        check(response.statusCode in 200..299) {
            "Supabase push token cleanup failed with HTTP ${response.statusCode}."
        }
    }

    private fun execute(
        method: String,
        url: String,
        accessToken: String,
        body: String? = null,
        prefer: String? = null
    ): ContactHttpResponse = http.execute(
        method = method,
        url = url,
        headers = buildMap {
            put("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            put("Authorization", "Bearer $accessToken")
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            prefer?.let { put("Prefer", it) }
        },
        body = body?.toByteArray(Charsets.UTF_8),
        contentType = body?.let { "application/json" }
    )

    private fun currentTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val PREFERENCES_NAME = "kolam_master_fcm_registration"
        const val ASSOCIATED_USER_KEY = "associated_user_id"
        const val ASSOCIATED_TOKEN_KEY = "associated_fcm_token"
        const val FCM_PROVIDER = "fcm"
    }
}

internal data class FcmTokenRow(
    val userId: String,
    val token: String,
    val platform: String,
    val provider: String,
    val updatedAt: String
) {
    fun toJson(): String = JSONObject()
        .put("user_id", userId)
        .put("token", token)
        .put("platform", platform)
        .put("provider", provider)
        .put("updated_at", updatedAt)
        .toString()
}

internal fun buildFcmTokenRow(userId: String, token: String, updatedAt: String) =
    FcmTokenRow(userId, token, "android", "fcm", updatedAt)
