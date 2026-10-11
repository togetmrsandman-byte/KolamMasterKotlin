package com.kolammaster.app

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LessonUnlockRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var userId: String
    private lateinit var repository: LessonUnlockRepository

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        userId = "visible-count-test-${UUID.randomUUID()}"
        repository = LessonUnlockRepository(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(
            "kolam-master-local-lesson-unlocks",
            Context.MODE_PRIVATE
        ).edit().remove("visible-count:$userId").commit()
    }

    @Test
    fun visibleLessonCountStartsAtSixtyAndSurvivesRepositoryRecreation() {
        assertEquals(60, repository.loadLocalVisibleLessonCount(userId))
        assertEquals(80, repository.saveLocalVisibleLessonCount(userId, 80))

        val recreatedRepository = LessonUnlockRepository(context)

        assertEquals(80, recreatedRepository.loadLocalVisibleLessonCount(userId))
    }

    @Test
    fun staleLocalVisibleLessonCountDoesNotReduceSavedCount() {
        assertEquals(100, repository.saveLocalVisibleLessonCount(userId, 100))

        assertEquals(100, repository.saveLocalVisibleLessonCount(userId, 80))
        assertEquals(100, repository.loadLocalVisibleLessonCount(userId))
    }
}
