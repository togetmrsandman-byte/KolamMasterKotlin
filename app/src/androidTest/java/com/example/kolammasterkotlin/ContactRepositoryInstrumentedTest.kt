package com.kolammaster.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kolammaster.app.auth.SupabaseAccount
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.UnknownHostException

@RunWith(AndroidJUnit4::class)
class ContactRepositoryInstrumentedTest {
    @Test
    fun mapsContactConversationAndMessageResponses() {
        val conversation = ContactJson.decodeConversation(
            JSONObject(
                """{"id":"conversation-1","user_id":"user-uuid","email":"user@example.com",""" +
                    """"phone":"+15551234567","subject":"Help","status":"OPEN",""" +
                    """"created_at":"2026-10-01T00:00:00Z",""" +
                    """"updated_at":"2026-10-02T00:00:00Z"}"""
            )
        )
        val message = ContactJson.decodeMessage(
            JSONObject(
                """{"id":"message-1","conversation_id":"conversation-1","sender":"USER",""" +
                    """"message":"Hello","image_url":"https://images.test/contact.webp",""" +
                    """"created_at":"2026-10-02T00:00:00Z"}"""
            )
        )

        assertEquals("user-uuid", conversation.userId)
        assertEquals("+15551234567", conversation.phone)
        assertEquals("conversation-1", message.conversationId)
        assertEquals("USER", message.sender)
        assertEquals("https://images.test/contact.webp", message.imageUrl)
    }

    @Test
    fun loadsConversationsAndFiltersByAuthenticatedSupabaseUuid() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(
                200,
                """[{"id":"c1","user_id":"${auth.account.id}","email":"","phone":"+1",""" +
                    """"subject":"Help","status":"OPEN","created_at":"created",""" +
                    """"updated_at":"updated"}]"""
            )
        )

        val conversations = ContactRepository(auth, transport).getCurrentUserContactConversations()

        assertEquals("c1", conversations.single().id)
        assertEquals(auth.account.id, conversations.single().userId)
        assertEquals(auth.account.id, auth.requestedUserId)
        assertTrue(transport.requests.single().url.contains("user_id=eq.${auth.account.id}"))
        assertTrue(transport.requests.single().url.contains("order=updated_at.desc"))
        assertTrue(transport.requests.single().url.contains("limit=10"))
    }

    @Test
    fun loadsLatestMessageActivityForConversationListRows() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(
                200,
                """[{"id":"c1","user_id":"${auth.account.id}","email":"","phone":"+1",""" +
                    """"subject":"Help","status":"OPEN","created_at":"created",""" +
                    """"updated_at":"updated","contact_messages":[""" +
                    """{"created_at":"2026-10-07T22:00:00Z"}]}]"""
            )
        )

        val conversation = ContactRepository(auth, transport)
            .getCurrentUserContactConversations()
            .single()

        assertEquals("2026-10-07T22:00:00Z", conversation.latestMessageCreatedAt)
        assertTrue(
            transport.requests.single().url.contains("contact_messages.order=created_at.desc")
        )
        assertTrue(transport.requests.single().url.contains("contact_messages.limit=1"))
    }

    @Test
    fun loadsMessagesOnlyAfterVerifyingConversationOwnership() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(200, """[{"id":"c1"}]"""),
            ContactHttpResponse(
                200,
                """[{"id":"m1","conversation_id":"c1","sender":"USER",""" +
                    """"message":"Hello","image_url":null,"created_at":"created"}]"""
            )
        )

        val messages = ContactRepository(auth, transport).getContactMessages("c1")

        assertEquals("Hello", messages.single().message)
        assertEquals(2, transport.requests.size)
        assertTrue(transport.requests[0].url.contains("user_id=eq.${auth.account.id}"))
        assertTrue(transport.requests[1].url.contains("conversation_id=eq.c1"))
        assertTrue(transport.requests[1].url.contains("order=created_at.asc"))
    }

    @Test
    fun createsConversationWithOnlyExpectedFieldsThenInsertsInitialUserMessage() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(
                201,
                """[{"id":"c1","user_id":"${auth.account.id}","email":"user@example.com",""" +
                    """"phone":"+15551234567","subject":"Help","status":"OPEN",""" +
                    """"created_at":"created","updated_at":"updated"}]"""
            ),
            ContactHttpResponse(201, "")
        )

        val created = ContactRepository(auth, transport).createContactConversation(
            email = " user@example.com ",
            phone = " +15551234567 ",
            subject = "  Help  ",
            initialMessage = "  Please help  "
        )
        val conversationPayload = JSONObject(
            transport.requests[0].body!!.toString(Charsets.UTF_8)
        )
        val messagePayload = JSONObject(
            transport.requests[1].body!!.toString(Charsets.UTF_8)
        )

        assertEquals("c1", created.id)
        assertEquals(
            setOf("user_id", "email", "phone", "subject"),
            jsonKeys(conversationPayload)
        )
        assertEquals(auth.account.id, conversationPayload.getString("user_id"))
        assertEquals("user@example.com", conversationPayload.getString("email"))
        assertEquals("+15551234567", conversationPayload.getString("phone"))
        assertEquals("Help", conversationPayload.getString("subject"))
        assertEquals(
            setOf("conversation_id", "sender", "message"),
            jsonKeys(messagePayload)
        )
        assertEquals("USER", messagePayload.getString("sender"))
        assertEquals("Please help", messagePayload.getString("message"))
    }

    @Test
    fun createsAccountConversationWithAuthEmailEmptyPhoneAndTrimmedFormValues() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(
                201,
                """[{"id":"c1","user_id":"${auth.account.id}","email":"user@example.com",""" +
                    """"phone":"","subject":"Help","status":"OPEN",""" +
                    """"created_at":"created","updated_at":"updated"}]"""
            ),
            ContactHttpResponse(201, "")
        )
        val repository = ContactRepository(auth, transport)

        createContactConversationForAccount(
            repository = repository,
            account = auth.account,
            subject = "  Help  ",
            initialMessage = "  Question  "
        )

        val payload = JSONObject(transport.requests[0].body!!.toString(Charsets.UTF_8))
        assertEquals(auth.account.email, payload.getString("email"))
        assertEquals("", payload.getString("phone"))
        assertEquals("Help", payload.getString("subject"))
        assertEquals("Question", JSONObject(
            transport.requests[1].body!!.toString(Charsets.UTF_8)
        ).getString("message"))
    }

    @Test
    fun createsConversationUploadsImageThenStoresImageUrlOnInitialMessage() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(
                201,
                """[{"id":"c1","user_id":"${auth.account.id}","email":"user@example.com",""" +
                    """"phone":"+15551234567","subject":"Help","status":"OPEN",""" +
                    """"created_at":"created","updated_at":"updated"}]"""
            ),
            ContactHttpResponse(
                200,
                """{"success":true,"imageUrl":"https://images.test/first-message.webp"}"""
            ),
            ContactHttpResponse(201, "")
        )

        val created = ContactRepository(auth, transport).createContactConversation(
            email = "user@example.com",
            phone = "+15551234567",
            subject = "Help",
            initialMessage = "See attached",
            initialImageBytes = byteArrayOf(1, 2, 3)
        )

        val initialMessage = JSONObject(
            transport.requests[2].body!!.toString(Charsets.UTF_8)
        )
        assertEquals("c1", created.id)
        assertTrue(transport.requests[0].url.contains("/rest/v1/contact_conversations"))
        assertEquals(
            "https://kolam-master-backend.togetmrsandman.workers.dev/chat/upload-image",
            transport.requests[1].url
        )
        assertEquals("POST", transport.requests[1].method)
        assertTrue(transport.requests[1].contentType!!.startsWith("multipart/form-data;"))
        assertTrue(transport.requests[1].body!!.toString(Charsets.ISO_8859_1)
            .contains("Content-Type: image/webp"))
        assertTrue(transport.requests[2].url.contains("/rest/v1/contact_messages"))
        assertEquals("c1", initialMessage.getString("conversation_id"))
        assertEquals("USER", initialMessage.getString("sender"))
        assertEquals("See attached", initialMessage.getString("message"))
        assertEquals(
            "https://images.test/first-message.webp",
            initialMessage.getString("image_url")
        )
    }

    @Test
    fun verifiesConversationOwnershipAndStoresImageUrlInUserMessage() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(200, """[{"id":"c1","status":"OPEN"}]"""),
            ContactHttpResponse(
                201,
                """[{"id":"m1","conversation_id":"c1","sender":"USER",""" +
                    """"message":"See attached","image_url":"https://images.test/contact.webp",""" +
                    """"created_at":"2026-10-02T00:00:00Z"}]"""
            )
        )

        val sent = ContactRepository(auth, transport).sendContactMessage(
            conversationId = "c1",
            message = "  See attached  ",
            imageUrl = " https://images.test/contact.webp "
        )

        assertTrue(transport.requests[0].url.contains("id=eq.c1"))
        assertTrue(transport.requests[0].url.contains("user_id=eq.${auth.account.id}"))
        val payload = JSONObject(transport.requests[1].body!!.toString(Charsets.UTF_8))
        assertEquals("USER", payload.getString("sender"))
        assertEquals("See attached", payload.getString("message"))
        assertEquals(
            "https://images.test/contact.webp",
            payload.getString("image_url")
        )
        assertEquals("m1", sent.id)
        assertTrue(transport.requests[1].url.contains("select=id,conversation_id,sender,message,image_url,created_at"))
        assertTrue(transport.requests[1].headers["Prefer"] == "return=representation")
    }

    @Test
    fun closedConversationRejectsMessageBeforeInsert() = runBlocking {
        val transport = FakeContactHttp(
            ContactHttpResponse(200, """[{"id":"c1","status":"CLOSED"}]""")
        )
        val error = runCatching {
            ContactRepository(FakeContactAuth(), transport).sendContactMessage(
                conversationId = "c1",
                message = "Still need help",
                imageUrl = null
            )
        }.exceptionOrNull()

        assertTrue(error is ContactConversationClosedException)
        assertEquals(1, transport.requests.size)
        assertEquals("GET", transport.requests.single().method)
    }

    @Test
    fun sendsTrimmedTextWithNullImageAndReturnsCreatedServerRow() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(200, """[{"id":"c1","status":"OPEN"}]"""),
            ContactHttpResponse(
                201,
                """[{"id":"m2","conversation_id":"c1","sender":"USER",""" +
                    """"message":"Need help","image_url":null,"created_at":"created"}]"""
            )
        )

        val message = ContactRepository(auth, transport).sendContactMessage(
            conversationId = "c1",
            message = "  Need help  ",
            imageUrl = null
        )
        val payload = JSONObject(transport.requests[1].body!!.toString(Charsets.UTF_8))

        assertEquals("m2", message.id)
        assertEquals("c1", message.conversationId)
        assertEquals("Need help", payload.getString("message"))
        assertEquals("USER", payload.getString("sender"))
        assertTrue(payload.isNull("image_url"))
    }

    @Test
    fun resolvesSupportMessageOnlyForAnOwnedConversation() = runBlocking {
        val auth = FakeContactAuth()
        val transport = FakeContactHttp(
            ContactHttpResponse(200, """[{"conversation_id":"c1"}]"""),
            ContactHttpResponse(200, """[{"id":"c1"}]""")
        )

        val conversationId = ContactRepository(auth, transport)
            .getContactConversationIdForMessage("admin-message")

        assertEquals("c1", conversationId)
        assertTrue(transport.requests[0].url.contains("id=eq.admin-message"))
        assertTrue(transport.requests[1].url.contains("user_id=eq.${auth.account.id}"))
    }

    @Test
    fun doesNotResolveSupportMessageForAnotherUsersConversation() = runBlocking {
        val transport = FakeContactHttp(
            ContactHttpResponse(200, """[{"conversation_id":"other-conversation"}]"""),
            ContactHttpResponse(200, "[]")
        )

        val error = runCatching {
            ContactRepository(FakeContactAuth(), transport)
                .getContactConversationIdForMessage("foreign-message")
        }.exceptionOrNull()

        assertTrue(error is ContactConversationNotFoundException)
    }

    @Test
    fun uploadsContactImageUsingWorkerMultipartContract() = runBlocking {
        val transport = FakeContactHttp(
            ContactHttpResponse(
                200,
                """{"success":true,"imageUrl":"https://images.test/contact.webp",""" +
                    """"imageKey":"contact/1.webp"}"""
            )
        )
        val imageBytes = "webp-data".toByteArray()

        val imageUrl = ContactRepository(FakeContactAuth(), transport)
            .uploadContactImage(imageBytes)
        val request = transport.requests.single()
        val multipartBody = request.body!!.toString(Charsets.UTF_8)

        assertEquals("https://images.test/contact.webp", imageUrl)
        assertEquals(
            "https://kolam-master-backend.togetmrsandman.workers.dev/chat/upload-image",
            request.url
        )
        assertTrue(request.contentType.orEmpty().startsWith("multipart/form-data; boundary="))
        assertTrue(multipartBody.contains("name=\"image\""))
        assertTrue(multipartBody.contains("filename=\"contact-us-image.webp\""))
        assertTrue(multipartBody.contains("Content-Type: image/webp"))
        assertTrue(multipartBody.contains("webp-data"))
    }

    @Test
    fun rejectsMissingUserAndDoesNotClassifyHttpErrorsAsNetwork() = runBlocking {
        val guestAuth = FakeContactAuth(
            account = SupabaseAccount("guest-uuid", true, "Guest", "", null)
        )
        val signInFailure = runCatching {
            ContactRepository(guestAuth, FakeContactHttp()).getCurrentUserContactConversations()
        }.exceptionOrNull()
        assertTrue(signInFailure is ContactSignInRequiredException)

        val httpError = HttpStatusFailureException(401, "Unauthorized")
        assertFalse(NetworkErrors.isNetworkFailure(httpError))
        assertNull(
            ContactJson.decodeMessage(
                JSONObject(
                    """{"id":"m1","conversation_id":"c1","sender":"USER",""" +
                        """"message":"Hi","image_url":null,"created_at":"created"}"""
                )
            ).imageUrl
        )
        assertTrue(NetworkErrors.isNetworkFailure(UnknownHostException()))
    }

    private fun jsonKeys(value: JSONObject): Set<String> =
        value.keys().asSequence().toSet()

    private class FakeContactAuth(
        val account: SupabaseAccount = SupabaseAccount(
            id = "11111111-2222-4333-8444-555555555555",
            isGuest = false,
            name = "Test User",
            email = "user@example.com",
            avatarUrl = null
        )
    ) : ContactAuthDataSource {
        var requestedUserId: String? = null

        override suspend fun currentAccount(): SupabaseAccount = account

        override suspend fun accessTokenFor(userId: String): String {
            requestedUserId = userId
            assertEquals(account.id, userId)
            return "test-access-token"
        }
    }

    private class FakeContactHttp(vararg responses: ContactHttpResponse) :
        ContactHttpTransport {
        val requests = mutableListOf<FakeRequest>()
        private val queuedResponses = ArrayDeque(responses.toList())

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            contentType: String?
        ): ContactHttpResponse {
            requests += FakeRequest(method, url, headers, body, contentType)
            return queuedResponses.removeFirst()
        }
    }

    private data class FakeRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val contentType: String?
    )
}
