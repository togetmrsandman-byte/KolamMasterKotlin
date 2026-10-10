package com.kolammaster.app

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityRepositoryTest {
    @Test
    fun approvedSubmissionUsesItsActualLessonId() {
        val submissions = parseApprovedCommunitySubmissions(
            JSONArray(
                """[{
                    "id":"submission-id",
                    "creator_name":"Kolam artist",
                    "lesson_id":"expert-flower-kolam",
                    "status":"APPROVED",
                    "gallery_submission_images":[{
                        "id":"image-id",
                        "image_url":"https://images.example/kolam.webp",
                        "display_order":0,
                        "status":"APPROVED"
                    }]
                }]"""
            )
        )

        assertEquals(1, submissions.size)
        assertEquals("expert-flower-kolam", submissions.single().lessonId)
    }

    @Test
    fun missingLessonIdRemainsUnavailableInsteadOfBecomingNullText() {
        val submissions = parseApprovedCommunitySubmissions(
            JSONArray(
                """[{
                    "id":"submission-id",
                    "creator_name":"Kolam artist",
                    "lesson_id":null,
                    "status":"APPROVED",
                    "gallery_submission_images":[{
                        "id":"image-id",
                        "image_url":"https://images.example/kolam.webp",
                        "display_order":0,
                        "status":"APPROVED"
                    }]
                }]"""
            )
        )

        assertNull(submissions.single().lessonId)
    }

    @Test
    fun nullLikeOrWhitespaceLessonMetadataIsHidden() {
        val submissions = parseApprovedCommunitySubmissions(
            JSONArray(
                """[
                    {
                        "id":"submission-one",
                        "creator_name":"Kolam artist",
                        "lesson_id":"   ",
                        "country":"NULL",
                        "status":"APPROVED",
                        "gallery_submission_images":[{
                            "id":"image-one",
                            "image_url":"https://images.example/one.webp",
                            "display_order":0,
                            "status":"APPROVED"
                        }]
                    },
                    {
                        "id":"submission-two",
                        "creator_name":"Kolam artist",
                        "lesson_id":"NULL",
                        "country":null,
                        "status":"APPROVED",
                        "gallery_submission_images":[{
                            "id":"image-two",
                            "image_url":"https://images.example/two.webp",
                            "display_order":0,
                            "status":"APPROVED"
                        }]
                    }
                ]"""
            )
        )

        assertEquals(2, submissions.size)
        submissions.forEach { submission ->
            assertNull(submission.lessonId)
            assertNull(submission.country)
        }
    }
}
