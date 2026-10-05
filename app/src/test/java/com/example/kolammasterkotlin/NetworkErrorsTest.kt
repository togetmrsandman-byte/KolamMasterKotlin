package com.kolammaster.app

import io.github.jan.supabase.exceptions.HttpRequestException as SupabaseHttpRequestException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.client.call.HttpClientCall
import io.ktor.client.statement.HttpResponse
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.util.date.GMTDate
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class NetworkErrorsTest {
    @Test
    fun recognizesJavaTransportFailuresAndNestedCauses() {
        assertTrue(NetworkErrors.isNetworkFailure(UnknownHostException()))
        assertTrue(NetworkErrors.isNetworkFailure(ConnectException()))
        assertTrue(NetworkErrors.isNetworkFailure(SocketException()))
        assertTrue(NetworkErrors.isNetworkFailure(SocketTimeoutException()))
        assertTrue(
            NetworkErrors.isNetworkFailure(
                IllegalStateException("request failed", ConnectException())
            )
        )
    }

    @Test
    fun recognizesDnsFailureTextAndNestedKtorHttpWrapper() {
        val unknownHost = UnknownHostException(
            "no address associated with the host name"
        )
        assertTrue(NetworkErrors.isNetworkFailure(unknownHost))
        assertTrue(
            NetworkErrors.isNetworkFailure(
                IllegalStateException("auth operation failed", unknownHost)
            )
        )

        val ktorWrappedFailure = ResponseException(TestHttpResponse(401), "request failed")
            .apply { initCause(unknownHost) }
        assertTrue(NetworkErrors.isNetworkFailure(ktorWrappedFailure))
        assertEquals(
            NetworkErrors.DISPLAY_TEXT,
            NetworkErrors.messageFor(ktorWrappedFailure, "Authentication failed.")
        )
    }

    @Test
    fun recognizesSupabaseNetworkRequestExceptionWithoutClassifyingGenericIoErrors() {
        val request = HttpRequestBuilder()
        assertTrue(
            NetworkErrors.isNetworkFailure(
                SupabaseHttpRequestException(
                    "no address associated with the host name",
                    request
                )
            )
        )
        assertEquals(
            NetworkErrors.MESSAGE,
            NetworkErrors.googleSignInNetworkMessage(
                SupabaseHttpRequestException(
                    "no address associated with the host name",
                    request
                )
            )
        )
        assertEquals("No internet", NetworkErrors.GOOGLE_SIGN_IN_TITLE)
        assertTrue(
            NetworkErrors.isNetworkFailure(
                SupabaseHttpRequestException(
                    "Unable to resolve host \"supabase.example\"",
                    request
                )
            )
        )
        listOf(
            "connection refused",
            "connection reset",
            "network is unreachable",
            "connect timed out",
            "socket timeout"
        ).forEach { transportMessage ->
            assertTrue(
                transportMessage,
                NetworkErrors.isNetworkFailure(
                    SupabaseHttpRequestException(transportMessage, request)
                )
            )
        }
        assertFalse(
            NetworkErrors.isNetworkFailure(
                SupabaseHttpRequestException("request failed", request)
            )
        )
        assertEquals(
            null,
            NetworkErrors.googleSignInNetworkMessage(
                SupabaseHttpRequestException("request failed", request)
            )
        )
        assertFalse(NetworkErrors.isNetworkFailure(IOException("HTTP request failed")))
    }

    @Test
    fun recognizesKtorTransportAndRequestTimeoutFailures() {
        assertTrue(
            NetworkErrors.isNetworkFailure(
                io.ktor.client.network.sockets.ConnectTimeoutException("connect timed out")
            )
        )
        assertTrue(
            NetworkErrors.isNetworkFailure(
                io.ktor.client.network.sockets.SocketTimeoutException("read timed out")
            )
        )
        assertTrue(
            NetworkErrors.isNetworkFailure(
                HttpRequestTimeoutException("request timed out", null)
            )
        )
    }

    @Test
    fun recognizesGoogleNetworkStatusButNotAccountCancellationOrAuthRejection() {
        assertTrue(
            NetworkErrors.isNetworkFailure(
                ApiException(Status(CommonStatusCodes.NETWORK_ERROR))
            )
        )
        assertFalse(
            NetworkErrors.isNetworkFailure(
                ApiException(Status(GoogleSignInStatusCodes.SIGN_IN_CANCELLED))
            )
        )
        assertFalse(NetworkErrors.isNetworkFailure(IllegalStateException("auth rejected")))
    }

    @Test
    fun doesNotClassifyHttpStatusesOrResponseExceptionsAsNetwork() {
        listOf(400, 401, 403, 404, 409, 500).forEach { status ->
            assertFalse(
                "HTTP $status must not be considered connectivity failure",
                NetworkErrors.isNetworkFailure(
                    HttpStatusFailureException(status, "HTTP $status")
                )
            )
        }
        listOf(400, 401, 403, 404, 409, 500).forEach { status ->
            assertFalse(
                "Ktor HTTP $status must not be considered connectivity failure",
                NetworkErrors.isNetworkFailure(
                    ResponseException(TestHttpResponse(status), "HTTP $status")
                )
            )
        }
    }

    @Test
    fun doesNotClassifyLocalDataAuthCancellationOrProgrammingErrorsAsNetwork() {
        assertFalse(NetworkErrors.isNetworkFailure(FileNotFoundException("missing local file")))
        assertFalse(NetworkErrors.isNetworkFailure(IOException("local file write failed")))
        assertFalse(NetworkErrors.isNetworkFailure(JSONException("invalid JSON")))
        assertFalse(NetworkErrors.isNetworkFailure(IllegalArgumentException("invalid data")))
        assertFalse(NetworkErrors.isNetworkFailure(IllegalStateException("auth rejected")))
        assertFalse(NetworkErrors.isNetworkFailure(RuntimeException("programming error")))
        assertFalse(NetworkErrors.isNetworkFailure(java.util.concurrent.CancellationException()))
    }

    @Test
    fun onlyNetworkErrorsUseTheSharedUserFacingText() {
        assertEquals(
            NetworkErrors.DISPLAY_TEXT,
            NetworkErrors.messageFor(SocketTimeoutException("technical detail"), "fallback")
        )
        assertEquals(
            "No internet",
            NetworkErrors.TITLE
        )
        assertEquals(
            "Please check your internet connection and try again.",
            NetworkErrors.MESSAGE
        )
        assertEquals(
            "HTTP 401",
            NetworkErrors.messageFor(HttpStatusFailureException(401, "HTTP 401"), "fallback")
        )
    }

    @Test
    fun downloadTransportErrorsAreFriendlyButHttpAndLocalIoRemainNonNetwork() {
        assertEquals(
            NetworkErrors.DISPLAY_TEXT,
            NetworkErrors.messageFor(SocketTimeoutException("read timeout"), "download failed")
        )
        assertEquals(
            "HTTP 404",
            NetworkErrors.messageFor(
                HttpStatusFailureException(404, "HTTP 404"),
                "download failed"
            )
        )
        assertEquals(
            "Missing local file",
            NetworkErrors.messageFor(FileNotFoundException("Missing local file"), "download failed")
        )
    }

    @Test
    fun boundedSignInOperationReportsItsOwnTimeoutAsNetworkButPreservesCancellation() =
        runBlocking {
            val timeout = try {
                runBoundedNetworkOperation(20) { delay(1_000) }
                null
            } catch (exception: NetworkOperationTimeoutException) {
                exception
            }
            assertTrue(timeout != null)
            assertTrue(NetworkErrors.isNetworkFailure(timeout!!))

            val cancellation = try {
                withTimeout(20) {
                    runBoundedNetworkOperation(5_000) { delay(1_000) }
                }
                null
            } catch (exception: CancellationException) {
                exception
            }
            assertTrue(cancellation != null)
            assertFalse(NetworkErrors.isNetworkFailure(cancellation!!))
        }

    @OptIn(InternalAPI::class)
    private class TestHttpResponse(statusCode: Int) : HttpResponse() {
        override val call: HttpClientCall
            get() = error("Call is not needed for classifier tests.")
        override val status = HttpStatusCode(statusCode, "Test")
        override val version: HttpProtocolVersion
            get() = error("Version is not needed for classifier tests.")
        override val requestTime: GMTDate
            get() = error("Request time is not needed for classifier tests.")
        override val responseTime: GMTDate
            get() = error("Response time is not needed for classifier tests.")
        override val rawContent: ByteReadChannel
            get() = error("Response body is not needed for classifier tests.")
        override val headers: Headers = Headers.Empty
        override val coroutineContext: CoroutineContext = EmptyCoroutineContext

        override fun toString(): String = "TestHttpResponse[$status]"
    }
}
