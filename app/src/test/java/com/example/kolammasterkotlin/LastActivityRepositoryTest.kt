package com.kolammaster.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LastActivityRepositoryTest {
    @Test
    fun callsCurrentUserActivityRpcWithAuthenticatedSessionAndNoClientUserId() = runBlocking {
        val auth = FakeLastActivityAuth()
        val http = FakeLastActivityHttp(200)
        val repository = LastActivityRepository(auth, http, "https://example.supabase.co")

        repository.recordCurrentUserActivity("authenticated-user")

        assertEquals("authenticated-user", auth.requestedUserId)
        assertEquals(
            "https://example.supabase.co/rest/v1/rpc/record_current_user_activity",
            http.requestedUrl
        )
        assertEquals("Bearer session-token", http.requestedHeaders["Authorization"])
        assertEquals("{}", http.requestedBody?.toString(Charsets.UTF_8))
    }

    @Test
    fun reportsRejectedRpcRequest() {
        val repository = LastActivityRepository(
            FakeLastActivityAuth(),
            FakeLastActivityHttp(403),
            "https://example.supabase.co"
        )

        assertThrows(HttpStatusFailureException::class.java) {
            runBlocking {
                repository.recordCurrentUserActivity("authenticated-user")
            }
        }
    }

    private class FakeLastActivityAuth : LastActivityAuthDataSource {
        var requestedUserId: String? = null

        override suspend fun accessTokenFor(userId: String): String {
            requestedUserId = userId
            return "session-token"
        }
    }

    private class FakeLastActivityHttp(
        private val statusCode: Int
    ) : LastActivityHttpTransport {
        var requestedUrl: String? = null
        var requestedHeaders: Map<String, String> = emptyMap()
        var requestedBody: ByteArray? = null

        override fun execute(
            url: String,
            headers: Map<String, String>,
            body: ByteArray
        ): LastActivityHttpResponse {
            requestedUrl = url
            requestedHeaders = headers
            requestedBody = body
            return LastActivityHttpResponse(statusCode)
        }
    }
}
