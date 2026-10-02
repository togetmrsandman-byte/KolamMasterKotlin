package com.example.kolammasterkotlin

import java.io.File
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.kolammasterkotlin", appContext.packageName)
    }

    @Test
    fun bothPackagedLessonsDecryptWithEmbeddedSecret() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packages = listOf(
            "Sikku.kmp" to setOf("preview.webp", "dots.webp", "path.json"),
            "Shivan.kmp" to setOf(
                "preview.webp",
                "artwork.webp",
                "tinted.webp",
                "path.json",
                "step003.webp",
                "step004.webp",
                "step005.webp",
                "Shivan.json"
            )
        )

        packages.forEach { (assetName, expectedEntries) ->
            val destination = File(context.cacheDir, "kmp-test-${System.nanoTime()}")
            try {
                val entries = KmpLessonExtractor.extractAsset(
                    context = context,
                    assetName = assetName,
                    sharedSecret = KmpSecret.value(),
                    destination = destination
                )
                expectedEntries.forEach { entry ->
                    assertTrue("$assetName did not extract $entry", entry in entries)
                }
                if (assetName == "Shivan.kmp") {
                    val lesson = RangoliLesson.load(destination)
                    assertEquals(2, lesson.steps.count { it.structuralLesson != null })
                    assertEquals(3, lesson.steps.count { it.fillBitmap != null })
                    assertEquals((1..5).toList(), lesson.steps.map { it.number })
                    assertFalse(lesson.preview.sameAs(lesson.artwork))
                }
            } finally {
                destination.deleteRecursively()
            }
        }
    }
}