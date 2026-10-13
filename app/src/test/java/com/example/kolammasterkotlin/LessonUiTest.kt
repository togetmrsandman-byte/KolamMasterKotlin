package com.kolammaster.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LessonUiTest {
    @Test
    fun dotsOrderHeadingIsLocalizedWithoutDroppingPackageInformation() {
        val heading = "புள்ளிகளின் வரிசை"

        assertEquals(heading, dotsOrderStatusText(null, heading))
        assertEquals(heading, dotsOrderStatusText("", heading))
        assertEquals(heading, dotsOrderStatusText("Dots Order", heading))
        listOf(":", "：", "-", "–", "—").forEach { delimiter ->
            val packageInfo = "Dots Order${delimiter} 5x3"
            assertEquals(
                "$heading${delimiter} 5x3",
                dotsOrderStatusText(packageInfo, heading)
            )
        }
        assertEquals(
            "$heading\n5x3 Dots Order",
            dotsOrderStatusText("5x3 Dots Order", heading)
        )
        assertEquals(
            "$heading\nDots Order extra",
            dotsOrderStatusText("Dots Order extra", heading)
        )
        assertEquals(
            "$heading\n  Dots Order  ",
            dotsOrderStatusText("  Dots Order  ", heading)
        )
        assertEquals(
            "$heading\n  5x3 pattern  ",
            dotsOrderStatusText("  5x3 pattern  ", heading)
        )
        assertEquals("$heading\n   ", dotsOrderStatusText("   ", heading))
    }

}
