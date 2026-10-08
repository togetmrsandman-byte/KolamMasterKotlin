package com.kolammaster.app.auth

import com.kolammaster.app.BuildConfig
import com.kolammaster.app.HttpStatusFailureException
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class SupabaseAccount(
    val id: String,
    val isGuest: Boolean,
    val name: String,
    val email: String,
    val avatarUrl: String?,
    val createdAt: String? = null
)

object SupabaseGuestAuth {
    private val authMutex = Mutex()

    private val supabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        ) {
            install(Auth) {
                alwaysAutoRefresh = true
                autoLoadFromStorage = true
                autoSaveToStorage = true
                enableLifecycleCallbacks = true
            }
        }
    }

    suspend fun getOrCreateGuestUserId(): String = authMutex.withLock {
        ensureSessionLocked().user?.id
            ?: error("Supabase session was created without an authenticated user.")
    }

    suspend fun currentAccount(): SupabaseAccount? {
        val auth = supabase.auth
        auth.awaitInitialization()
        return auth.currentUserOrNull()?.let(::accountFor)
    }

    suspend fun accessTokenFor(expectedUserId: String): String {
        val auth = supabase.auth
        auth.awaitInitialization()
        val session = auth.currentSessionOrNull()
            ?: error("There is no authenticated Supabase session.")
        val sessionUserId = session.user?.id
            ?: auth.retrieveUserForCurrentSession().id
        check(sessionUserId == expectedUserId) {
            "The active Supabase account changed before lesson unlock sync."
        }
        return session.accessToken
    }

    suspend fun mergeGuestWithGoogleIdToken(idToken: String): SupabaseAccount =
        authMutex.withLock {
            require(idToken.isNotBlank()) { "Google did not provide an ID token." }

            val auth = supabase.auth
            ensureSessionLocked()
            auth.refreshCurrentSession()
            val guestSession = auth.currentSessionOrNull()
                ?: error("Supabase session disappeared before the Guest merge.")
            val guestUser = guestSession.user ?: auth.retrieveUserForCurrentSession()
            check(accountFor(guestUser).isGuest) {
                "Google sign-in can only be linked from a Guest session."
            }

            val response = postGuestMerge(guestSession.accessToken, idToken)
            when (response.optString("mode")) {
                "linked" -> {
                    check(response.optString("permanentUserId") == guestUser.id) {
                        "Guest merge returned an unexpected account."
                    }
                    auth.retrieveUserForCurrentSession(updateSession = true)
                }
                "merged" -> {
                    val returnedSession = response.optJSONObject("session")
                        ?: error("Guest merge did not return a Supabase session.")
                    val accessToken = returnedSession.optString("access_token")
                    val refreshToken = returnedSession.optString("refresh_token")
                    check(accessToken.isNotBlank() && refreshToken.isNotBlank()) {
                        "Guest merge returned an incomplete Supabase session."
                    }
                    auth.importAuthToken(
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        retrieveUser = true
                    )
                }
                else -> error("Guest merge returned an unsupported response.")
            }

            auth.currentUserOrNull()?.let(::accountFor)
                ?: error("Google sign-in completed without an authenticated user.")
        }

    suspend fun signOutAndCreateGuest() = authMutex.withLock {
        val auth = supabase.auth
        auth.signOut()
        ensureSessionLocked()
    }

    private suspend fun ensureSessionLocked() =
        supabase.auth.let { auth ->
            auth.awaitInitialization()
            auth.currentSessionOrNull()
                ?: run {
                    auth.signInAnonymously()
                    auth.currentSessionOrNull()
                        ?: error("Supabase anonymous sign-in completed without a session.")
                }
        }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun accountFor(user: UserInfo): SupabaseAccount {
        val userMetadata = user.userMetadata
        val hasGoogleIdentity = user.identities?.any { it.provider == "google" } == true
        val provider = user.appMetadata
            ?.get("provider")
            ?.jsonPrimitive
            ?.contentOrNull
        val isGuest = !hasGoogleIdentity && (provider == "anonymous" || user.email.isNullOrBlank())
        val name = userMetadata?.get("full_name")?.jsonPrimitive?.contentOrNull
            ?: userMetadata?.get("name")?.jsonPrimitive?.contentOrNull
            ?: user.email?.substringBefore("@")
            ?: "Kolam Master user"
        return SupabaseAccount(
            id = user.id,
            isGuest = isGuest,
            name = name,
            email = user.email.orEmpty(),
            avatarUrl = userMetadata?.get("avatar_url")?.jsonPrimitive?.contentOrNull,
            createdAt = user.createdAt?.toString()
        )
    }

    private suspend fun postGuestMerge(guestAccessToken: String, googleIdToken: String) =
        withContext(Dispatchers.IO) {
            val connection = URL(
                "${BuildConfig.SUPABASE_URL}/functions/v1/merge-guest-google"
            ).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000
                connection.doOutput = true
                connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                connection.setRequestProperty("Authorization", "Bearer $guestAccessToken")
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(JSONObject().put("googleIdToken", googleIdToken).toString())
                }

                val statusCode = connection.responseCode
                val responseBody = (if (statusCode in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val response = JSONObject(responseBody)
                if (statusCode !in 200..299) {
                    throw HttpStatusFailureException(
                        statusCode = statusCode,
                        message = response.optString("error").ifBlank {
                            "Guest merge request failed with HTTP $statusCode."
                        }
                    )
                }
                if (!response.optBoolean("success")) {
                    throw IOException(
                        response.optString("error").ifBlank {
                            "Guest merge request was rejected."
                        }
                    )
                }
                response
            } finally {
                connection.disconnect()
            }
        }
}
