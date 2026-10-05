package com.kolammaster.app

import android.content.Context
import android.util.Log
import com.kolammaster.app.auth.SupabaseGuestAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class LessonUnlockRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        LOCAL_PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun loadLocal(userId: String): Set<String> {
        val json = preferences.getString(userKey(userId), null) ?: return emptySet()
        val unlocks = try {
            JSONObject(json)
        } catch (exception: JSONException) {
            Log.e("LessonUnlocks", "Ignoring invalid local lesson unlock data.", exception)
            return emptySet()
        }
        return buildSet {
            val keys = unlocks.keys()
            while (keys.hasNext()) {
                val lessonId = keys.next()
                if (unlocks.optBoolean(lessonId)) add(lessonId)
            }
        }
    }

    fun saveLocal(userId: String, lessonIds: Set<String>) {
        val unlockMap = JSONObject()
        lessonIds.sorted().forEach { lessonId -> unlockMap.put(lessonId, true) }
        check(preferences.edit().putString(userKey(userId), unlockMap.toString()).commit()) {
            "Could not persist lesson unlocks locally."
        }
    }

    suspend fun loadFromSupabase(userId: String): Set<String> =
        withContext(Dispatchers.IO) {
            val accessToken = SupabaseGuestAuth.accessTokenFor(userId)
            val query = "user_id=eq.${encode(userId)}&select=unlocked_lessons&limit=1"
            val response = request(
                method = "GET",
                path = "/rest/v1/user_data?$query",
                accessToken = accessToken
            )
            val rows = JSONArray(response)
            if (rows.length() == 0) emptySet()
            else readUnlockValue(rows.getJSONObject(0).opt("unlocked_lessons"))
        }

    suspend fun saveToSupabase(userId: String, lessonIds: Set<String>) =
        withContext(Dispatchers.IO) {
            val accessToken = SupabaseGuestAuth.accessTokenFor(userId)
            val unlocks = JSONArray().apply {
                lessonIds.sorted().forEach(::put)
            }
            val body = JSONObject().put("unlocked_lessons", unlocks).toString()
            val query = "user_id=eq.${encode(userId)}"
            val updateResponse = request(
                method = "PATCH",
                path = "/rest/v1/user_data?$query",
                accessToken = accessToken,
                body = body,
                prefer = "return=representation"
            )
            if (JSONArray(updateResponse).length() == 0) {
                val insertBody = JSONObject()
                    .put("user_id", userId)
                    .put("unlocked_lessons", unlocks)
                    .toString()
                request(
                    method = "POST",
                    path = "/rest/v1/user_data?on_conflict=user_id",
                    accessToken = accessToken,
                    body = insertBody,
                    prefer = "resolution=merge-duplicates,return=minimal"
                )
            }
        }

    private fun readUnlockValue(value: Any?): Set<String> = when (value) {
        is JSONArray -> buildSet {
            for (index in 0 until value.length()) {
                when (val item = value.opt(index)) {
                    is String -> item.takeIf(String::isNotBlank)?.let(::add)
                    is JSONObject -> item.optString("id")
                        .takeIf(String::isNotBlank)
                        ?.takeIf { item.optBoolean("unlocked", true) }
                        ?.let(::add)
                }
            }
        }
        is JSONObject -> buildSet {
            val keys = value.keys()
            while (keys.hasNext()) {
                val lessonId = keys.next()
                if (value.optBoolean(lessonId)) add(lessonId)
            }
        }
        is String -> try {
            readUnlockValue(JSONArray(value))
        } catch (_: JSONException) {
            readUnlockValue(JSONObject(value))
        }
        else -> emptySet()
    }

    private fun request(
        method: String,
        path: String,
        accessToken: String,
        body: String? = null,
        prefer: String? = null
    ): String {
        val connection = URL("${com.kolammaster.app.BuildConfig.SUPABASE_URL}$path")
            .openConnection() as? HttpURLConnection
            ?: throw IOException("Supabase endpoint is not an HTTP connection.")
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty(
                "apikey",
                com.kolammaster.app.BuildConfig.SUPABASE_PUBLISHABLE_KEY
            )
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            prefer?.let { connection.setRequestProperty("Prefer", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val response = (if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw HttpStatusFailureException(
                    statusCode = status,
                    message = "Supabase lesson unlock request failed with HTTP $status" +
                        response.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun userKey(userId: String): String = "user:$userId"

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val LOCAL_PREFERENCES_NAME = "kolam-master-local-lesson-unlocks"
    }
}
