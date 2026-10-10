package com.kolammaster.app

import com.kolammaster.app.auth.SupabaseAccount
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryPublishRepositoryTest {
    @Test
    fun createsSignedR2UploadAndSubmitsMetadataUsingBackendJsonContract() = runBlocking {
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/create-r2-upload-url") -> ContactHttpResponse(
                    200,
                    uploadUrlsResponse()
                )
                request.method == "PUT" -> ContactHttpResponse(200, "")
                request.url.endsWith("/gallery-publish") -> ContactHttpResponse(
                    200,
                    """{"success":true,"submissionId":"$SUBMISSION_ID","status":"SUBMITTED"}"""
                )
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        }

        val result = repository(transport).submit(
            account,
            lesson,
            " Creator ",
            " artist@example.com ",
            listOf(losslessWebp, lossyWebp)
        )

        assertEquals(SUBMISSION_ID, result)
        assertEquals(4, transport.requests.size)

        val create = transport.requests[0]
        assertEquals("POST", create.method)
        assertEquals("$SUPABASE_URL/functions/v1/create-r2-upload-url", create.url)
        assertEquals("application/json", create.contentType)
        assertEquals("Bearer access-token", create.headers["Authorization"])
        assertEquals(BuildConfig.SUPABASE_PUBLISHABLE_KEY, create.headers["apikey"])
        val createBody = JSONObject(create.body!!.toString(Charsets.UTF_8))
        assertEquals(
            setOf("purpose", "contentType", "imageCount"),
            createBody.keysAsSet()
        )
        assertEquals("gallery", createBody.getString("purpose"))
        assertEquals("image/webp", createBody.getString("contentType"))
        assertEquals(2, createBody.getInt("imageCount"))

        val firstPut = transport.requests[1]
        val secondPut = transport.requests[2]
        assertEquals("PUT", firstPut.method)
        assertEquals("https://r2.example/upload-1?signature=one", firstPut.url)
        assertEquals(mapOf("Content-Type" to "image/webp"), firstPut.headers)
        assertArrayEquals(losslessWebp, firstPut.body)
        assertEquals("https://r2.example/upload-2?signature=two", secondPut.url)
        assertArrayEquals(lossyWebp, secondPut.body)
        assertFalse(firstPut.headers.containsKey("Authorization"))

        val submit = transport.requests[3]
        assertEquals("$SUPABASE_URL/functions/v1/gallery-publish", submit.url)
        assertEquals("application/json", submit.contentType)
        assertEquals("Bearer access-token", submit.headers["Authorization"])
        val payload = JSONObject(submit.body!!.toString(Charsets.UTF_8))
        assertEquals(SUBMISSION_ID, payload.getString("submissionId"))
        assertEquals("Creator", payload.getString("creatorName"))
        assertEquals("artist@example.com", payload.getString("creatorEmail"))
        assertEquals(lesson.lessonId, payload.getString("lessonId"))
        assertEquals(lesson.lessonName, payload.getString("lessonName"))
        assertEquals(lesson.category, payload.getString("category"))
        assertEquals(lesson.difficulty, payload.getString("difficulty"))
        assertEquals(
            listOf(
                JSONObject()
                    .put("imageId", IMAGE_ID_1)
                    .put("imageKey", imageKey(IMAGE_ID_1)),
                JSONObject()
                    .put("imageId", IMAGE_ID_2)
                    .put("imageKey", imageKey(IMAGE_ID_2))
            ).map(JSONObject::toString),
            payload.getJSONArray("images").toObjectStrings()
        )
        assertFalse(payload.has("authorizationNonce"))
    }

    @Test
    fun uploadFailureCleansUpDraftAndNeverSubmitsMetadata() = runBlocking {
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/create-r2-upload-url") ->
                    ContactHttpResponse(200, uploadUrlsResponse())
                request.method == "PUT" -> ContactHttpResponse(403, "")
                request.url.endsWith("/gallery-publish") -> {
                    val body = JSONObject(request.body!!.toString(Charsets.UTF_8))
                    assertEquals("cleanup", body.getString("action"))
                    ContactHttpResponse(200, """{"success":true,"deleted":true}""")
                }
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        }

        val failure = try {
            repository(transport).submit(
                account,
                lesson,
                "Creator",
                null,
                listOf(losslessWebp, lossyWebp)
            )
            null
        } catch (exception: GalleryPublishException) {
            exception
        }

        assertTrue(failure?.message?.contains("Photo 1 of 2 could not be uploaded.") == true)
        assertEquals(3, transport.requests.size)
        assertEquals("PUT", transport.requests[1].method)
        assertEquals("$SUPABASE_URL/functions/v1/gallery-publish", transport.requests[2].url)
        val cleanup = JSONObject(transport.requests[2].body!!.toString(Charsets.UTF_8))
        assertEquals(setOf("action", "submissionId", "imageIds"), cleanup.keysAsSet())
        assertEquals(2, cleanup.getJSONArray("imageIds").length())
    }

    @Test
    fun uploadReferencesForAnotherUserAreRejectedBeforeAnyR2Put() = runBlocking {
        val response = JSONObject(uploadUrlsResponse(imageCount = 1))
        val urls = response.getJSONArray("uploadUrls")
        urls.getJSONObject(0).put(
            "objectKey",
            "gallery/submitted/another-user/$SUBMISSION_ID/$IMAGE_ID_1.webp"
        )
        val transport = FakeHttpTransport {
            ContactHttpResponse(200, response.toString())
        }

        val failure = try {
            repository(transport).submit(
                account,
                lesson,
                "Creator",
                null,
                listOf(losslessWebp)
            )
            null
        } catch (exception: GalleryPublishException) {
            exception
        }

        assertTrue(failure?.message?.contains("another account") == true)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun webpValidationAcceptsLosslessAndLossyAndEnforcesStrictLimit() {
        assertTrue(losslessWebp.isLosslessWebpPayload())
        assertTrue(losslessWebp.isUploadableGalleryPublishImage())
        assertTrue(lossyWebp.isValidWebpPayload())
        assertTrue(lossyWebp.isUploadableGalleryPublishImage())
        assertFalse(byteArrayOf(1, 2, 3).isUploadableGalleryPublishImage())
        assertFalse(
            createWebpImage("VP8L", MAX_GALLERY_PUBLISH_IMAGE_BYTES - 20)
                .isUploadableGalleryPublishImage()
        )
    }

    @Test
    fun missingCreatorEmailIsOmitted() = runBlocking {
        val transport = FakeHttpTransport { request ->
            when {
                request.url.endsWith("/create-r2-upload-url") ->
                    ContactHttpResponse(200, uploadUrlsResponse(imageCount = 1))
                request.method == "PUT" -> ContactHttpResponse(200, "")
                else -> ContactHttpResponse(
                    200,
                    """{"success":true,"submissionId":"$SUBMISSION_ID","status":"SUBMITTED"}"""
                )
            }
        }

        repository(transport).submit(account, lesson, "Creator", " ", listOf(losslessWebp))

        val payload = JSONObject(transport.requests.last().body!!.toString(Charsets.UTF_8))
        assertFalse(payload.has("creatorEmail"))
    }

    private fun repository(transport: FakeHttpTransport) = GalleryPublishRepository(
        http = transport,
        auth = TestAuthDataSource(),
        supabaseUrl = SUPABASE_URL
    )

    private class TestAuthDataSource : GalleryPublishAuthDataSource {
        override suspend fun currentAccount() = account
        override suspend fun accessTokenFor(userId: String): String {
            assertEquals(account.id, userId)
            return "access-token"
        }
    }

    private data class RecordedRequest(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val contentType: String?
    )

    private class FakeHttpTransport(
        private val respond: (RecordedRequest) -> ContactHttpResponse
    ) : ContactHttpTransport {
        val requests = mutableListOf<RecordedRequest>()

        override fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            contentType: String?
        ): ContactHttpResponse {
            val request = RecordedRequest(method, url, headers, body, contentType)
            requests += request
            return respond(request)
        }
    }

    private fun uploadUrlsResponse(imageCount: Int = 2): String {
        val ids = listOf(IMAGE_ID_1, IMAGE_ID_2).take(imageCount)
        return JSONObject()
            .put("success", true)
            .put("submissionId", SUBMISSION_ID)
            .put(
                "uploadUrls",
                JSONArray().apply {
                    ids.forEachIndexed { index, imageId ->
                        put(
                            JSONObject()
                                .put("imageId", imageId)
                                .put("objectKey", imageKey(imageId))
                                .put("uploadUrl", "https://r2.example/upload-${index + 1}?signature=${if (index == 0) "one" else "two"}")
                                .put("imageUrl", "https://public.example/$imageId.webp")
                        )
                    }
                }
            )
            .toString()
    }

    private fun imageKey(imageId: String) =
        "gallery/submitted/${account.id}/$SUBMISSION_ID/$imageId.webp"

    private fun JSONArray.toObjectStrings(): List<String> =
        (0 until length()).map { getJSONObject(it).toString() }

    private fun JSONObject.keysAsSet(): Set<String> = keys().asSequence().toSet()

    private companion object {
        const val SUPABASE_URL = "https://supabase.example"
        const val SUBMISSION_ID = "9c44f5a1-f3d2-4d11-9fc1-552b53a1a001"
        const val IMAGE_ID_1 = "8c44f5a1-f3d2-4d11-9fc1-552b53a1a001"
        const val IMAGE_ID_2 = "7c44f5a1-f3d2-4d11-9fc1-552b53a1a001"
        val losslessWebp = createWebpImage("VP8L", 5).apply {
            this[20] = 0x2f
        }
        val lossyWebp = createWebpImage("VP8 ", 10).apply {
            this[23] = 0x9d.toByte()
            this[24] = 0x01
            this[25] = 0x2a
            this[26] = 1
            this[28] = 1
        }
        val account = SupabaseAccount(
            id = "35037633-4d2d-4d11-9fc1-552b53a1a001",
            isGuest = false,
            name = "Creator",
            email = "creator@example.com",
            avatarUrl = null
        )
        val lesson = PublishLessonDetails(
            lessonId = "lesson-1",
            lessonName = "Kolam lesson",
            category = "Beginner",
            difficulty = "Easy"
        )

        fun createWebpImage(chunkType: String, payloadSize: Int): ByteArray {
            val data = ByteArray(payloadSize)
            val padding = data.size and 1
            val riffSize = 4 + 8 + data.size + padding
            return byteArrayOf(
                'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
                riffSize.toByte(), 0, 0, 0,
                'W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte(),
                chunkType[0].code.toByte(), chunkType[1].code.toByte(),
                chunkType[2].code.toByte(), chunkType[3].code.toByte(),
                data.size.toByte(), 0, 0, 0,
                *data,
                *ByteArray(padding)
            )
        }
    }
}
