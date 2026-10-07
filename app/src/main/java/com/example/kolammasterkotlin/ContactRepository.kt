package com.kolammaster.app

import com.kolammaster.app.auth.SupabaseAccount
import com.kolammaster.app.auth.SupabaseGuestAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

internal class ContactSignInRequiredException :
    IllegalStateException("Sign in with Google to use Contact Us.")

internal class ContactConversationNotFoundException(conversationId: String) :
    IllegalArgumentException("Contact conversation '$conversationId' was not found.")

internal class ContactConversationClosedException :
    IllegalStateException("This conversation is closed.")

internal data class ContactHttpResponse(val statusCode: Int, val body: String)

internal interface ContactHttpTransport {
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray? = null,
        contentType: String? = null
    ): ContactHttpResponse
}

internal interface ContactAuthDataSource {
    suspend fun currentAccount(): SupabaseAccount?
    suspend fun accessTokenFor(userId: String): String
}

private object SupabaseContactAuthDataSource : ContactAuthDataSource {
    override suspend fun currentAccount(): SupabaseAccount? =
        SupabaseGuestAuth.currentAccount()

    override suspend fun accessTokenFor(userId: String): String =
        SupabaseGuestAuth.accessTokenFor(userId)
}

internal class ContactRepository(
    private val auth: ContactAuthDataSource = SupabaseContactAuthDataSource,
    private val http: ContactHttpTransport = UrlConnectionContactHttpTransport,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL
) {
    suspend fun getCurrentUserContactConversations(): List<ContactConversation> =
        withContext(Dispatchers.IO) {
            val account = requireSignedInAccount()
            val token = auth.accessTokenFor(account.id)
            val query = "user_id=eq.${encode(account.id)}" +
                "&select=$CONVERSATION_LIST_COLUMNS" +
                "&contact_messages.order=created_at.desc&contact_messages.limit=1" +
                "&order=updated_at.desc&limit=$CONVERSATION_LIMIT"
            parseArray(request("GET", "$supabaseUrl/rest/v1/contact_conversations?$query", token))
                .toContactConversations()
        }

    suspend fun getContactConversation(conversationId: String): ContactConversation =
        withContext(Dispatchers.IO) {
            require(conversationId.isNotBlank()) { "Conversation ID must not be empty." }
            val account = requireSignedInAccount()
            val token = auth.accessTokenFor(account.id)
            val conversationQuery = "id=eq.${encode(conversationId)}" +
                "&user_id=eq.${encode(account.id)}&select=$CONVERSATION_COLUMNS&limit=1"
            val rows = parseArray(
                request(
                    "GET",
                    "$supabaseUrl/rest/v1/contact_conversations?$conversationQuery",
                    token
                )
            )
            val conversationRow = rows.optJSONObject(0)
                ?: throw ContactConversationNotFoundException(conversationId)
            ContactJson.decodeConversation(conversationRow)
        }

    suspend fun getContactConversationIdForMessage(messageId: String): String =
        withContext(Dispatchers.IO) {
            require(messageId.isNotBlank()) { "Message ID must not be empty." }
            val account = requireSignedInAccount()
            val token = auth.accessTokenFor(account.id)
            val messageQuery = "id=eq.${encode(messageId)}&select=conversation_id&limit=1"
            val messageRow = parseArray(
                request(
                    "GET",
                    "$supabaseUrl/rest/v1/contact_messages?$messageQuery",
                    token
                )
            ).optJSONObject(0) ?: throw ContactDataException(
                "The support notification message was not found."
            )
            val conversationId = messageRow.optString("conversation_id")
                .takeIf(String::isNotBlank)
                ?: throw ContactDataException(
                    "The support notification message has no conversation ID."
                )
            val ownershipQuery = "id=eq.${encode(conversationId)}" +
                "&user_id=eq.${encode(account.id)}&select=id&limit=1"
            if (parseArray(
                    request(
                        "GET",
                        "$supabaseUrl/rest/v1/contact_conversations?$ownershipQuery",
                        token
                    )
                ).length() == 0
            ) {
                throw ContactConversationNotFoundException(conversationId)
            }
            conversationId
        }

    suspend fun getContactMessages(conversationId: String): List<ContactMessage> =
        withContext(Dispatchers.IO) {
            require(conversationId.isNotBlank()) { "Conversation ID must not be empty." }
            val account = requireSignedInAccount()
            val token = auth.accessTokenFor(account.id)
            val ownershipQuery = "id=eq.${encode(conversationId)}" +
                "&user_id=eq.${encode(account.id)}&select=id&limit=1"
            val ownedConversations = parseArray(
                request(
                    "GET",
                    "$supabaseUrl/rest/v1/contact_conversations?$ownershipQuery",
                    token
                )
            )
            if (ownedConversations.length() == 0) {
                throw ContactConversationNotFoundException(conversationId)
            }

            val query = "conversation_id=eq.${encode(conversationId)}" +
                "&select=$MESSAGE_COLUMNS&order=created_at.asc"
            parseArray(
                request("GET", "$supabaseUrl/rest/v1/contact_messages?$query", token)
            ).toContactMessages()
        }

    suspend fun createContactConversation(
        email: String?,
        phone: String,
        subject: String,
        initialMessage: String
    ): ContactConversation = withContext(Dispatchers.IO) {
        val trimmedSubject = subject.trim()
        val trimmedMessage = initialMessage.trim()
        require(trimmedSubject.isNotEmpty()) { "Contact subject must not be empty." }
        require(trimmedMessage.isNotEmpty()) { "Initial contact message must not be empty." }
        require(isValidContactPhoneForSubmission(phone)) {
            "Contact phone number is invalid."
        }

        val account = requireSignedInAccount()
        val token = auth.accessTokenFor(account.id)
        val body = ContactJson.encodeConversationInsert(
            userId = account.id,
            email = email?.trim().orEmpty(),
            phone = phone.trim(),
            subject = trimmedSubject
        )
        val query = "select=$CONVERSATION_COLUMNS"
        val created = parseArray(
            request(
                "POST",
                "$supabaseUrl/rest/v1/contact_conversations?$query",
                token,
                body,
                prefer = "return=representation"
            )
        )
        val conversation = created.optJSONObject(0)
            ?: throw ContactDataException("Supabase did not return the created conversation.")
        val decodedConversation = ContactJson.decodeConversation(conversation)
        if (decodedConversation.userId != account.id) {
            throw ContactDataException("Supabase returned a conversation for another user.")
        }

        request(
            "POST",
            "$supabaseUrl/rest/v1/contact_messages",
            token,
            ContactJson.encodeInitialMessage(decodedConversation.id, trimmedMessage),
            prefer = "return=minimal"
        )
        decodedConversation
    }

    suspend fun sendContactMessage(
        conversationId: String,
        message: String,
        imageUrl: String?
    ): ContactMessage = withContext(Dispatchers.IO) {
        require(conversationId.isNotBlank()) { "Conversation ID must not be empty." }
        val trimmedMessage = message.trim()
        val normalizedImageUrl = imageUrl?.trim()?.takeIf(String::isNotEmpty)
        require(trimmedMessage.isNotEmpty() || normalizedImageUrl != null) {
            "A contact message must contain text or an image."
        }

        val account = requireSignedInAccount()
        val token = auth.accessTokenFor(account.id)
        verifyConversationOpen(account.id, conversationId, token)
        val response = request(
            "POST",
            "$supabaseUrl/rest/v1/contact_messages?select=$MESSAGE_COLUMNS",
            token,
            ContactJson.encodeUserMessage(conversationId, trimmedMessage, normalizedImageUrl),
            prefer = "return=representation"
        )
        val row = parseArray(response).optJSONObject(0)
            ?: throw ContactDataException("Supabase did not return the created contact message.")
        ContactJson.decodeMessage(row)
    }

    suspend fun uploadContactImage(imageBytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            require(imageBytes.isNotEmpty()) { "Contact image must not be empty." }
            requireSignedInAccount()
            val boundary = "ContactImage-${UUID.randomUUID()}"
            val multipartBody = multipartImageBody(boundary, imageBytes)
            val response = http.execute(
                method = "POST",
                url = "$WORKER_BASE_URL/chat/upload-image",
                headers = mapOf(
                    "Accept" to "application/json"
                ),
                body = multipartBody,
                contentType = "multipart/form-data; boundary=$boundary"
            )
            if (response.statusCode !in 200..299) {
                throw HttpStatusFailureException(
                    response.statusCode,
                    "Contact image upload failed with HTTP ${response.statusCode}" +
                        response.body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                )
            }
            val result = parseObject(response.body)
            if (result.opt("success") != true) {
                throw ContactDataException("Contact image upload was not successful.")
            }
            (result.opt("imageUrl") as? String)?.takeIf(String::isNotBlank)
                ?: throw ContactDataException("Contact image upload returned no image URL.")
        }

    private suspend fun verifyConversationOpen(
        userId: String,
        conversationId: String,
        accessToken: String
    ) {
        val query = "id=eq.${encode(conversationId)}" +
            "&user_id=eq.${encode(userId)}&select=id,status&limit=1"
        val row = parseArray(
            request(
                "GET",
                "$supabaseUrl/rest/v1/contact_conversations?$query",
                accessToken
            )
        ).optJSONObject(0) ?: throw ContactConversationNotFoundException(conversationId)
        if (!row.optString("status").equals(OPEN_STATUS, ignoreCase = true)) {
            throw ContactConversationClosedException()
        }
    }

    private suspend fun requireSignedInAccount(): SupabaseAccount {
        val account = auth.currentAccount()
        if (account == null || account.isGuest) throw ContactSignInRequiredException()
        if (account.id.isBlank()) {
            throw IllegalStateException("The authenticated Supabase account has no user ID.")
        }
        return account
    }

    private fun request(
        method: String,
        url: String,
        accessToken: String,
        jsonBody: String? = null,
        prefer: String? = null
    ): String {
        val headers = buildMap {
            put("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            put("Authorization", "Bearer $accessToken")
            put("Accept", "application/json")
            if (jsonBody != null) put("Content-Type", "application/json")
            prefer?.let { put("Prefer", it) }
        }
        val response = http.execute(
            method = method,
            url = url,
            headers = headers,
            body = jsonBody?.toByteArray(Charsets.UTF_8),
            contentType = jsonBody?.let { "application/json" }
        )
        if (response.statusCode !in 200..299) {
            throw HttpStatusFailureException(
                response.statusCode,
                "Supabase Contact Us request failed with HTTP ${response.statusCode}" +
                    response.body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
            )
        }
        return response.body
    }

    private fun parseArray(body: String): JSONArray = try {
        JSONArray(body)
    } catch (exception: JSONException) {
        throw ContactDataException("Supabase returned malformed Contact Us rows.", exception)
    }

    private fun parseObject(body: String): JSONObject = try {
        JSONObject(body)
    } catch (exception: JSONException) {
        throw ContactDataException("Contact image service returned malformed data.", exception)
    }

    private fun multipartImageBody(boundary: String, imageBytes: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            write(
                (
                    "Content-Disposition: form-data; name=\"image\"; " +
                        "filename=\"contact-us-image.webp\"\r\n"
                    ).toByteArray(Charsets.UTF_8)
            )
            write("Content-Type: image/webp\r\n\r\n".toByteArray(Charsets.UTF_8))
            write(imageBytes)
            write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }.toByteArray()

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun JSONArray.toContactConversations(): List<ContactConversation> =
        (0 until length()).map { index ->
            val item = optJSONObject(index)
                ?: throw ContactDataException(
                    "Supabase Contact Us conversation at index $index is not an object."
                )
            ContactJson.decodeConversation(item)
        }

    private fun JSONArray.toContactMessages(): List<ContactMessage> =
        (0 until length()).map { index ->
            val item = optJSONObject(index)
                ?: throw ContactDataException(
                    "Supabase Contact Us message at index $index is not an object."
                )
            ContactJson.decodeMessage(item)
        }

    private companion object {
        const val CONVERSATION_COLUMNS =
            "id,user_id,email,phone,subject,status,created_at,updated_at"
        const val CONVERSATION_LIST_COLUMNS =
            "$CONVERSATION_COLUMNS,contact_messages(created_at)"
        const val MESSAGE_COLUMNS =
            "id,conversation_id,sender,message,image_url,created_at"
        const val CONVERSATION_LIMIT = 10
        const val OPEN_STATUS = "OPEN"
        const val WORKER_BASE_URL =
            "https://kolam-master-backend.togetmrsandman.workers.dev"
    }
}

internal suspend fun createContactConversationForAccount(
    repository: ContactRepository,
    account: SupabaseAccount?,
    subject: String,
    initialMessage: String,
    phone: String = ""
): ContactConversation {
    val authenticatedAccount = account?.takeUnless { it.isGuest }
        ?: throw ContactSignInRequiredException()
    return repository.createContactConversation(
        email = authenticatedAccount.email,
        phone = phone,
        subject = subject.trim(),
        initialMessage = initialMessage.trim()
    )
}

internal object UrlConnectionContactHttpTransport : ContactHttpTransport {
    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?
    ): ContactHttpResponse {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Contact Us endpoint is not an HTTP connection.")
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.outputStream.use { it.write(body) }
            }

            val statusCode = connection.responseCode
            val responseBody = (if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return ContactHttpResponse(statusCode, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MILLIS = 15_000
    private const val READ_TIMEOUT_MILLIS = 20_000
}
