package com.kolammaster.app

import com.kolammaster.app.auth.SupabaseGuestAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val APPROVED_SUBMISSION_STATUS = "APPROVED"
private const val COMMUNITY_IMAGE_RELATION = "gallery_submission_images"

internal data class CommunitySubmission(
    val id: String,
    val creatorName: String,
    val country: String?,
    val lessonId: String?,
    val lessonName: String?,
    val category: String?,
    val difficulty: String?,
    val submittedAt: String?,
    val images: List<CommunitySubmissionImage>
)

internal data class CommunitySubmissionImage(
    val id: String,
    val imageKey: String?,
    val imageUrl: String,
    val displayOrder: Int
)

internal class CommunityRepository(
    private val http: ContactHttpTransport = UrlConnectionContactHttpTransport
) {
    suspend fun loadApprovedSubmissions(): List<CommunitySubmission> =
        withContext(Dispatchers.IO) {
            val account = SupabaseGuestAuth.currentAccount()
            val authorization = account?.let {
                "Bearer ${SupabaseGuestAuth.accessTokenFor(it.id)}"
            } ?: "Bearer ${BuildConfig.SUPABASE_PUBLISHABLE_KEY}"
            val response = http.execute(
                method = "GET",
                url = "${BuildConfig.SUPABASE_URL}/rest/v1/gallery_submissions?$QUERY",
                headers = mapOf(
                    "apikey" to BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                    "Authorization" to authorization,
                    "Accept" to "application/json"
                )
            )
            if (response.statusCode !in 200..299) {
                throw HttpStatusFailureException(
                    response.statusCode,
                    "Community request failed with HTTP ${response.statusCode}."
                )
            }
            parseApprovedCommunitySubmissions(JSONArray(response.body))
        }

    private companion object {
        const val QUERY =
            "select=id,creator_name,country,lesson_id,lesson_name,category,difficulty,status,submitted_at," +
                "gallery_submission_images!inner(id,image_key,image_url,display_order,status)" +
                "&status=eq.APPROVED" +
                "&gallery_submission_images.status=eq.APPROVED" +
                "&gallery_submission_images.order=display_order.asc" +
                "&order=submitted_at.desc" +
                "&limit=100"
    }
}

internal fun parseApprovedCommunitySubmissions(rows: JSONArray): List<CommunitySubmission> =
    buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (!row.optString("status")
                    .equals(APPROVED_SUBMISSION_STATUS, ignoreCase = true)
            ) continue
            val images = row.getJSONArray(COMMUNITY_IMAGE_RELATION)
                .approvedImages()
            if (images.isEmpty()) continue
            val id = row.optString("id").takeIf(String::isNotBlank) ?: continue
            add(
                CommunitySubmission(
                    id = id,
                    creatorName = row.nullableString("creator_name") ?: "Community member",
                    country = row.nullableString("country"),
                    lessonId = row.nullableString("lesson_id"),
                    lessonName = row.nullableString("lesson_name"),
                    category = row.nullableString("category"),
                    difficulty = row.nullableString("difficulty"),
                    submittedAt = row.nullableString("submitted_at"),
                    images = images
                )
            )
        }
    }

private fun org.json.JSONObject.nullableString(key: String): String? =
    (opt(key) as? String)
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

private fun JSONArray.approvedImages(): List<CommunitySubmissionImage> =
    buildList {
        for (index in 0 until length()) {
            val row = optJSONObject(index) ?: continue
            if (!row.optString("status")
                    .equals(APPROVED_SUBMISSION_STATUS, ignoreCase = true)
            ) continue
            val id = row.optString("id").takeIf(String::isNotBlank) ?: continue
            val imageUrl = row.optString("image_url").takeIf(String::isNotBlank) ?: continue
            add(
                CommunitySubmissionImage(
                    id = id,
                    imageKey = row.optString("image_key").takeIf(String::isNotBlank),
                    imageUrl = imageUrl,
                    displayOrder = row.optInt("display_order")
                )
            )
        }
    }.sortedBy(CommunitySubmissionImage::displayOrder)
