package com.kolammaster.app

import android.content.Context
import android.util.Log
import com.kolammaster.app.auth.SupabaseGuestAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val APPROVED_SUBMISSION_STATUS = "APPROVED"
private const val COMMUNITY_IMAGE_RELATION = "gallery_submission_images"
private const val COMMUNITY_CACHE_PREFERENCES = "kolam_master_community"
private const val COMMUNITY_CACHE_KEY = "approved_gallery"
private const val COMMUNITY_IMAGE_LIMIT = 100

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
    context: Context,
    private val http: ContactHttpTransport = UrlConnectionContactHttpTransport
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        COMMUNITY_CACHE_PREFERENCES,
        Context.MODE_PRIVATE
    )

    fun loadCachedSubmissions(): List<CommunitySubmission> {
        val cached = preferences.getString(COMMUNITY_CACHE_KEY, null) ?: return emptyList()
        return try {
            parseApprovedCommunitySubmissions(JSONArray(cached))
        } catch (exception: JSONException) {
            Log.e("CommunityGallery", "Ignoring invalid cached gallery metadata.", exception)
            emptyList()
        }
    }

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
            val submissions = parseApprovedCommunitySubmissions(JSONArray(response.body))
            check(
                preferences.edit()
                    .putString(COMMUNITY_CACHE_KEY, submissions.toCachedJson())
                    .commit()
            ) { "Could not persist community gallery metadata." }
            submissions
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

    private fun List<CommunitySubmission>.toCachedJson(): String =
        JSONArray().apply {
            forEach { submission ->
                put(
                    JSONObject()
                        .put("id", submission.id)
                        .put("creator_name", submission.creatorName)
                        .put("country", submission.country)
                        .put("lesson_id", submission.lessonId)
                        .put("lesson_name", submission.lessonName)
                        .put("category", submission.category)
                        .put("difficulty", submission.difficulty)
                        .put("submitted_at", submission.submittedAt)
                        .put(
                            COMMUNITY_IMAGE_RELATION,
                            JSONArray().apply {
                                submission.images.forEach { image ->
                                    put(
                                        JSONObject()
                                            .put("id", image.id)
                                            .put("image_key", image.imageKey)
                                            .put("image_url", image.imageUrl)
                                            .put("display_order", image.displayOrder)
                                            .put("status", APPROVED_SUBMISSION_STATUS)
                                    )
                                }
                            }
                        )
                        .put("status", APPROVED_SUBMISSION_STATUS)
                )
            }
        }.toString()
}

internal fun parseApprovedCommunitySubmissions(rows: JSONArray): List<CommunitySubmission> =
    buildList {
        var imageCount = 0
        for (index in 0 until rows.length()) {
            if (imageCount >= COMMUNITY_IMAGE_LIMIT) break
            val row = rows.optJSONObject(index) ?: continue
            if (!row.optString("status")
                    .equals(APPROVED_SUBMISSION_STATUS, ignoreCase = true)
            ) continue
            val images = row.getJSONArray(COMMUNITY_IMAGE_RELATION)
                .approvedImages()
            if (images.isEmpty()) continue
            val limitedImages = images.take(COMMUNITY_IMAGE_LIMIT - imageCount)
            imageCount += limitedImages.size
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
                    images = limitedImages
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
