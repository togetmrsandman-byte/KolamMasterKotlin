package com.kolammaster.app

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import io.github.jan.supabase.exceptions.HttpRequestException as SupabaseHttpRequestException
import io.github.jan.supabase.exceptions.RestException as SupabaseRestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException as JavaSocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import java.util.Locale

internal class HttpStatusFailureException(
    val statusCode: Int,
    message: String
) : IOException(message)

internal class NetworkOperationTimeoutException(cause: Throwable? = null) :
    Exception("Network operation timed out.", cause)

internal suspend fun <T> runBoundedNetworkOperation(
    timeoutMillis: Long,
    operation: suspend () -> T
): T = try {
    withTimeout(timeoutMillis) { operation() }
} catch (exception: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw NetworkOperationTimeoutException(exception)
}

internal object NetworkErrors {
    const val TITLE = "No internet"
    const val GOOGLE_SIGN_IN_TITLE = TITLE
    const val MESSAGE = "Please check your internet connection and try again."
    const val DISPLAY_TEXT = "$TITLE\n$MESSAGE"

    fun googleSignInNetworkMessage(exception: Throwable): String? =
        MESSAGE.takeIf { isNetworkFailure(exception) }

    fun isNetworkFailure(exception: Throwable): Boolean {
        val causes = generateSequence(exception) { it.cause }.take(MAX_CAUSE_DEPTH).toList()
        if (causes.any { it is HttpStatusFailureException || it is SupabaseRestException }) {
            return false
        }
        if (causes.any { it is ResponseException }) {
            return causes.any(::hasTransportCause)
        }

        if (causes.any(::hasTransportCause)) return true

        return causes
            .filterIsInstance<SupabaseHttpRequestException>()
            .any { requestFailure ->
                val message = requestFailure.message.orEmpty().lowercase(Locale.ROOT)
                DNS_FAILURE_INDICATORS.any(message::contains)
            }
    }

    fun isHttpFailure(exception: Throwable): Boolean =
        generateSequence(exception) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .any { it is HttpStatusFailureException || it is ResponseException }

    fun messageFor(exception: Throwable, fallback: String): String =
        if (isNetworkFailure(exception)) DISPLAY_TEXT
        else exception.message?.takeIf(String::isNotBlank) ?: fallback

    private const val MAX_CAUSE_DEPTH = 16

    private fun hasTransportCause(cause: Throwable): Boolean =
        cause is ApiException && cause.statusCode == CommonStatusCodes.NETWORK_ERROR ||
            cause is UnknownHostException ||
            cause is ConnectException ||
            cause is NoRouteToHostException ||
            cause is SocketException ||
            cause is JavaSocketTimeoutException ||
            cause is UnresolvedAddressException ||
            cause is NetworkOperationTimeoutException ||
            cause is ConnectTimeoutException ||
            cause is SocketTimeoutException ||
            cause is HttpRequestTimeoutException

    private val DNS_FAILURE_INDICATORS = listOf(
        "no address associated with the host name",
        "no address associated with hostname",
        "unable to resolve host",
        "failed to resolve host",
        "unable to resolve",
        "failed to resolve",
        "connection refused",
        "connection reset",
        "network is unreachable",
        "no route to host",
        "unable to connect",
        "could not connect",
        "connection timed out",
        "connect timed out",
        "socket timeout",
        "socket timed out",
        "request timeout",
        "transport failure"
    )
}
