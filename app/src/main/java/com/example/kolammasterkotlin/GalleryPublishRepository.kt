package com.kolammaster.app

import com.kolammaster.app.auth.SupabaseAccount
import com.kolammaster.app.auth.SupabaseGuestAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal data class PublishLessonDetails(
    val lessonId: String,
    val lessonName: String,
    val category: String?,
    val difficulty: String?
)

internal fun creatorEmailForPublish(account: SupabaseAccount?): String? =
    account?.email?.trim()?.takeIf(String::isNotEmpty)

internal interface GalleryPublishAuthDataSource {
    suspend fun currentAccount(): SupabaseAccount?
    suspend fun accessTokenFor(userId: String): String
}

private object SupabaseGalleryPublishAuthDataSource : GalleryPublishAuthDataSource {
    override suspend fun currentAccount(): SupabaseAccount? =
        SupabaseGuestAuth.currentAccount()

    override suspend fun accessTokenFor(userId: String): String =
        SupabaseGuestAuth.accessTokenFor(userId)
}

internal class GalleryPublishRepository(
    private val http: ContactHttpTransport = UrlConnectionGalleryPublishHttpTransport,
    private val auth: GalleryPublishAuthDataSource =
        SupabaseGalleryPublishAuthDataSource,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL
) {
    suspend fun submit(
        account: SupabaseAccount,
        lesson: PublishLessonDetails,
        creatorName: String,
        creatorEmail: String?,
        images: List<ByteArray>
    ): String = withContext(Dispatchers.IO) {
        require(!account.isGuest) { "Sign in with Google to publish." }
        require(creatorName.isNotBlank()) {
            "Creator name is required."
        }
        require(lesson.lessonId.isNotBlank() && lesson.lessonName.isNotBlank()) {
            "Lesson details are missing."
        }
        require(!lesson.category.isNullOrBlank() && !lesson.difficulty.isNullOrBlank()) {
            "Required lesson category or difficulty is unavailable."
        }
        require(images.isNotEmpty() && images.size <= MAX_IMAGE_COUNT) {
            "Choose between one and $MAX_IMAGE_COUNT images."
        }
        require(images.all(ByteArray::isUploadableGalleryPublishImage)) {
            "Each image must be a valid WebP file smaller than 10 MiB."
        }

        val token = authenticatedToken(account)
        val headers = authorizationHeaders(token)
        val uploadResponse = http.execute(
            method = "POST",
            url = "$supabaseUrl/functions/v1/create-r2-upload-url",
            headers = headers,
            body = JSONObject()
                .put("purpose", "gallery")
                .put("contentType", IMAGE_CONTENT_TYPE)
                .put("imageCount", images.size)
                .toString()
                .toByteArray(Charsets.UTF_8),
            contentType = "application/json"
        )
        val uploadBody = parseResponse(
            uploadResponse.body,
            "The photo upload could not be prepared."
        )
        if (uploadResponse.statusCode !in 200..299 || uploadBody.opt("success") != true) {
            throw GalleryPublishException(
                responseError(
                    uploadBody,
                    uploadResponse.statusCode,
                    "The photo upload could not be prepared."
                )
            )
        }
        val submissionId = uploadBody.optString("submissionId")
            .takeIf(::isUuid)
            ?: throw GalleryPublishException("The upload service returned an invalid submission.")
        val uploadUrls = uploadBody.optJSONArray("uploadUrls")
            ?.takeIf { it.length() == images.size }
            ?: throw GalleryPublishException("The upload service returned invalid photo references.")
        val imageIds = mutableSetOf<String>()
        val references = (0 until uploadUrls.length()).map { index ->
            val upload = uploadUrls.optJSONObject(index)
                ?: throw GalleryPublishException("The upload service returned invalid photo references.")
            val imageId = upload.optString("imageId").takeIf(::isUuid)
                ?: throw GalleryPublishException("The upload service returned an invalid photo reference.")
            if (!imageIds.add(imageId)) {
                throw GalleryPublishException("The upload service returned duplicate photo references.")
            }
            val objectKey = upload.optString("objectKey")
            val expectedKey = "gallery/submitted/${account.id}/$submissionId/$imageId.webp"
            val uploadUrl = upload.optString("uploadUrl").takeIf(::isHttpsUrl)
                ?: throw GalleryPublishException("The upload service returned an invalid photo URL.")
            if (objectKey != expectedKey) {
                throw GalleryPublishException(
                    "The upload service returned a photo reference for another account."
                )
            }
            GalleryPublishImageReference(imageId, objectKey, uploadUrl)
        }

        references.forEachIndexed { index, reference ->
            try {
                val response = http.execute(
                    method = "PUT",
                    url = reference.uploadUrl,
                    headers = mapOf("Content-Type" to IMAGE_CONTENT_TYPE),
                    body = images[index],
                    contentType = null
                )
                if (response.statusCode !in 200..299) {
                    throw GalleryPublishUploadException(response.statusCode)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                val cleanupFailure = try {
                    cleanupDraft(headers, submissionId, references.map { it.imageId })
                    false
                } catch (cleanupException: Exception) {
                    if (cleanupException is CancellationException) throw cleanupException
                    true
                }
                val cleanupMessage = if (cleanupFailure) {
                    " Some uploaded files could not be cleaned up; please contact support if the problem continues."
                } else {
                    ""
                }
                val reason = if (exception is GalleryPublishUploadException) {
                    "The upload service rejected the request (HTTP ${exception.statusCode})."
                } else {
                    "Check your connection and try again."
                }
                throw GalleryPublishException(
                    "Photo ${index + 1} of ${images.size} could not be uploaded. $reason$cleanupMessage",
                    exception
                )
            }
        }

        val payload = JSONObject()
            .put("submissionId", submissionId)
            .put("creatorName", creatorName.trim())
            .put("lessonId", lesson.lessonId)
            .put("lessonName", lesson.lessonName)
            .put("category", lesson.category)
            .put("difficulty", lesson.difficulty)
            .put(
                "images",
                JSONArray().apply {
                    references.forEach { reference ->
                        put(
                            JSONObject()
                                .put("imageId", reference.imageId)
                                .put("imageKey", reference.objectKey)
                        )
                    }
                }
            )
        creatorEmail?.trim()?.takeIf(String::isNotEmpty)?.let {
            payload.put("creatorEmail", it)
        }
        val response = http.execute(
            method = "POST",
            url = "$supabaseUrl/functions/v1/gallery-publish",
            headers = headers,
            body = payload.toString().toByteArray(Charsets.UTF_8),
            contentType = "application/json"
        )
        val body = parseResponse(response.body, "The kolam could not be submitted.")
        if (response.statusCode != 200 ||
            body.opt("success") != true ||
            body.optString("status") != "SUBMITTED"
        ) {
            throw GalleryPublishException(
                responseError(
                    body,
                    response.statusCode,
                    "The kolam could not be submitted. Please try again."
                )
            )
        }
        val finalizedId = body.optString("submissionId").takeIf(String::isNotBlank)
            ?: throw GalleryPublishException("The server did not confirm the submission.")
        if (finalizedId != submissionId) {
            throw GalleryPublishException("The server confirmed a different gallery submission.")
        }
        finalizedId
    }

    private fun cleanupDraft(
        headers: Map<String, String>,
        submissionId: String,
        imageIds: List<String>
    ) {
        val response = http.execute(
            method = "POST",
            url = "$supabaseUrl/functions/v1/gallery-publish",
            headers = headers,
            body = JSONObject()
                .put("action", "cleanup")
                .put("submissionId", submissionId)
                .put("imageIds", JSONArray(imageIds))
                .toString()
                .toByteArray(Charsets.UTF_8),
            contentType = "application/json"
        )
        if (response.statusCode !in 200..299 ||
            parseResponse(response.body, "Could not clean up partial photo uploads.")
                .opt("success") != true
        ) {
            throw GalleryPublishException("Could not clean up partial photo uploads.")
        }
    }

    private suspend fun authenticatedToken(account: SupabaseAccount): String {
        require(!account.isGuest) { "Sign in with Google to publish." }
        val current = auth.currentAccount()
        check(current != null && !current.isGuest && current.id == account.id) {
            "Your account changed. Sign in again before publishing."
        }
        return auth.accessTokenFor(account.id)
    }

    private fun authorizationHeaders(token: String) = mapOf(
        "apikey" to BuildConfig.SUPABASE_PUBLISHABLE_KEY,
        "Authorization" to "Bearer $token",
        "Accept" to "application/json"
    )

    private fun parseResponse(body: String, fallback: String): JSONObject = try {
        JSONObject(body)
    } catch (exception: JSONException) {
        throw GalleryPublishException(fallback, exception)
    }

    private fun responseError(body: JSONObject, statusCode: Int, fallback: String): String =
        body.optString("error").takeIf(String::isNotBlank) ?: when (statusCode) {
            401 -> "Your sign-in session is no longer valid. Sign in again."
            403 -> "This publish request is not authorized for your account."
            409 -> "This gallery submission conflicts with an existing submission."
            in 500..599 -> "The publish service is temporarily unavailable. Please try again."
            else -> fallback
        }

    private companion object {
        const val MAX_IMAGE_COUNT = 5
        const val IMAGE_CONTENT_TYPE = "image/webp"
    }

    private fun isHttpsUrl(value: String): Boolean =
        runCatching {
            URL(value).let {
                it.protocol.equals("https", ignoreCase = true) &&
                    it.host.isNotBlank() &&
                    it.userInfo == null
            }
        }.getOrDefault(false)

    private fun isUuid(value: String): Boolean =
        runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }
            .getOrDefault(false)
}

private data class GalleryPublishImageReference(
    val imageId: String,
    val objectKey: String,
    val uploadUrl: String
)

private class GalleryPublishUploadException(val statusCode: Int) :
    IOException("R2 upload failed with HTTP $statusCode.")

internal fun ByteArray.isLosslessWebpPayload(): Boolean =
    webpImageEncoding() == WebpImageEncoding.LOSSLESS

internal fun ByteArray.isValidWebpPayload(): Boolean =
    webpImageEncoding() != null

private enum class WebpImageEncoding {
    LOSSLESS,
    LOSSY
}

private fun ByteArray.webpImageEncoding(): WebpImageEncoding? {
    if (size < 20 ||
        this[0] != 'R'.code.toByte() ||
        this[1] != 'I'.code.toByte() ||
        this[2] != 'F'.code.toByte() ||
        this[3] != 'F'.code.toByte() ||
        this[8] != 'W'.code.toByte() ||
        this[9] != 'E'.code.toByte() ||
        this[10] != 'B'.code.toByte() ||
        this[11] != 'P'.code.toByte()
    ) {
        return null
    }

    val riffEnd = 8L + readLittleEndianUInt32(4)
    if (riffEnd != size.toLong() || riffEnd < 12) return null
    var offset = 12L
    var imageEncoding: WebpImageEncoding? = null
    while (offset + WEBP_CHUNK_HEADER_SIZE <= riffEnd) {
        val chunkOffset = offset.toInt()
        val chunkSize = readLittleEndianUInt32(chunkOffset + 4)
        val chunkEnd = offset + WEBP_CHUNK_HEADER_SIZE + chunkSize
        if (chunkEnd > riffEnd) return null
        if (
            this[chunkOffset] == 'V'.code.toByte() &&
            this[chunkOffset + 1] == 'P'.code.toByte() &&
            this[chunkOffset + 2] == '8'.code.toByte() &&
            this[chunkOffset + 3] == 'L'.code.toByte()
        ) {
            if (chunkSize < MIN_LOSSLESS_WEBP_CHUNK_SIZE ||
                this[chunkOffset + WEBP_CHUNK_HEADER_SIZE.toInt()] != 0x2f.toByte()
            ) return null
            val width = 1 +
                (this[chunkOffset + 9].toInt() and 0xFF) +
                ((this[chunkOffset + 10].toInt() and 0x3F) shl 8)
            val height = 1 +
                ((this[chunkOffset + 10].toInt() and 0xFF) shr 6) +
                ((this[chunkOffset + 11].toInt() and 0xFF) shl 2) +
                ((this[chunkOffset + 12].toInt() and 0x0F) shl 10)
            val version = (this[chunkOffset + 12].toInt() and 0xFF) shr 5
            if (width > MAX_LOSSLESS_DIMENSION || height > MAX_LOSSLESS_DIMENSION || version != 0) {
                return null
            }
            if (imageEncoding != null) return null
            imageEncoding = WebpImageEncoding.LOSSLESS
        } else if (
            this[chunkOffset] == 'V'.code.toByte() &&
            this[chunkOffset + 1] == 'P'.code.toByte() &&
            this[chunkOffset + 2] == '8'.code.toByte() &&
            this[chunkOffset + 3] == ' '.code.toByte()
        ) {
            if (chunkSize < MIN_LOSSY_WEBP_CHUNK_SIZE || imageEncoding != null) return null
            if (
                this[chunkOffset + 11] != 0x9d.toByte() ||
                this[chunkOffset + 12] != 0x01.toByte() ||
                this[chunkOffset + 13] != 0x2a.toByte()
            ) return null
            val width = (
                (this[chunkOffset + 14].toInt() and 0xFF) or
                    ((this[chunkOffset + 15].toInt() and 0xFF) shl 8)
                ) and 0x3FFF
            val height = (
                (this[chunkOffset + 16].toInt() and 0xFF) or
                    ((this[chunkOffset + 17].toInt() and 0xFF) shl 8)
                ) and 0x3FFF
            if (width == 0 || height == 0) return null
            imageEncoding = WebpImageEncoding.LOSSY
        }
        offset = chunkEnd + (chunkSize and 1L)
    }
    return imageEncoding.takeIf { offset == riffEnd }
}

internal fun ByteArray.isUploadableGalleryPublishImage(): Boolean =
    size in 1 until MAX_GALLERY_PUBLISH_IMAGE_BYTES && isValidWebpPayload()

private fun ByteArray.readLittleEndianUInt32(offset: Int): Long =
    (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)

private const val WEBP_CHUNK_HEADER_SIZE = 8L
private const val MIN_LOSSLESS_WEBP_CHUNK_SIZE = 5L
private const val MIN_LOSSY_WEBP_CHUNK_SIZE = 10L
private const val MAX_LOSSLESS_DIMENSION = 16_383

internal object UrlConnectionGalleryPublishHttpTransport : ContactHttpTransport {
    override fun execute(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?
    ): ContactHttpResponse {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Gallery publish URL is not an HTTP connection.")
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            headers.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                contentType?.let { connection.setRequestProperty("Content-Type", it) }
                connection.setFixedLengthStreamingMode(body.size)
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

    private const val CONNECT_TIMEOUT_MILLIS = 20_000
    private const val READ_TIMEOUT_MILLIS = 120_000
}

internal class GalleryPublishException(
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
