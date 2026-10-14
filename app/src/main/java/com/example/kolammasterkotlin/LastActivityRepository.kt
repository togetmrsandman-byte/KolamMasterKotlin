package com.kolammaster.app

import com.kolammaster.app.auth.SupabaseGuestAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal interface LastActivityAuthDataSource {
    suspend fun accessTokenFor(userId: String): String
}

private object SupabaseLastActivityAuthDataSource : LastActivityAuthDataSource {
    override suspend fun accessTokenFor(userId: String): String =
        SupabaseGuestAuth.accessTokenFor(userId)
}

internal data class LastActivityHttpResponse(val statusCode: Int)

internal interface LastActivityHttpTransport {
    fun execute(url: String, headers: Map<String, String>, body: ByteArray): LastActivityHttpResponse
}

internal class LastActivityRepository(
    private val auth: LastActivityAuthDataSource = SupabaseLastActivityAuthDataSource,
    private val http: LastActivityHttpTransport = UrlConnectionLastActivityHttpTransport,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL
) {
    suspend fun recordCurrentUserActivity(userId: String) = withContext(Dispatchers.IO) {
        require(userId.isNotBlank()) { "Last Activity requires an authenticated user ID." }
        val accessToken = auth.accessTokenFor(userId)
        val response = http.execute(
            url = "$supabaseUrl/rest/v1/rpc/record_current_user_activity",
            headers = mapOf(
                "apikey" to BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                "Authorization" to "Bearer $accessToken",
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            ),
            body = "{}".toByteArray(Charsets.UTF_8)
        )
        if (response.statusCode !in 200..299) {
            throw HttpStatusFailureException(
                response.statusCode,
                "Supabase Last Activity update failed with HTTP ${response.statusCode}."
            )
        }
    }
}

internal object UrlConnectionLastActivityHttpTransport : LastActivityHttpTransport {
    override fun execute(
        url: String,
        headers: Map<String, String>,
        body: ByteArray
    ): LastActivityHttpResponse {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Last Activity endpoint is not an HTTP connection.")
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.doOutput = true
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(body) }
            return LastActivityHttpResponse(connection.responseCode)
        } finally {
            connection.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 20_000
}
