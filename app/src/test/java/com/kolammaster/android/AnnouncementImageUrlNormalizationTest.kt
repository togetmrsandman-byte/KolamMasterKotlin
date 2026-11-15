package com.kolammaster.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnouncementImageUrlNormalizationTest {
    @Test
    fun treatsAbsentAndNullLikeValuesAsNoImage() {
        listOf(
            null,
            JSONObject.NULL,
            "",
            "   ",
            "null",
            "NULL",
            " null "
        ).forEach { value ->
            assertNull(normalizeAnnouncementImageUrl(value))
        }
    }

    @Test
    fun preservesLegitimateImageUrl() {
        val url = "https://example.com/announcement.webp"

        assertEquals(url, normalizeAnnouncementImageUrl(url))
    }
}
