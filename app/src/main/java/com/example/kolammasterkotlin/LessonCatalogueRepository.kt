package com.kolammaster.app

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal class LessonCatalogueRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    suspend fun loadCached(): List<LessonCatalogueEntry> = withContext(Dispatchers.IO) {
        val cached = preferences.getString(CACHE_KEY, null) ?: return@withContext emptyList()
        try {
            parseCatalogueJson(cached)
        } catch (exception: JSONException) {
            Log.w(TAG, "Ignoring invalid cached lesson catalogue", exception)
            emptyList()
        }
    }

    suspend fun refresh(): List<LessonCatalogueEntry> = withContext(Dispatchers.IO) {
        val response = fetchCatalogue()
        val entries = parseCatalogueJson(response)
        if (entries.isEmpty()) throw IOException("The R2 catalogue contained no valid lessons")
        preferences.edit().putString(CACHE_KEY, catalogueCacheJson(entries)).apply()
        entries
    }

    private fun fetchCatalogue(): String {
        val connection = URL(CATALOGUE_URL).openConnection() as? HttpsURLConnection
            ?: throw IOException("Catalogue endpoint must use HTTPS")
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw HttpStatusFailureException(
                    statusCode = status,
                    message = "Catalogue request failed with HTTP $status"
                )
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "kolam_master_preferences"
        const val CACHE_KEY = "kolam-master-lesson-catalogue"
        const val CATALOGUE_URL =
            "https://pub-b494afb01de94544a1520fedb72bf2b1.r2.dev/catalogue/catalogue.json"
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 20_000
        const val TAG = "LessonCatalogue"
    }
}
