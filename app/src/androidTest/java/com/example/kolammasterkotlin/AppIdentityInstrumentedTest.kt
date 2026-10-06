package com.kolammaster.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class AppIdentityInstrumentedTest {
    @Test
    fun androidApplicationLabelIsKolamMaster() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals(
            "Kolam Master",
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        )
    }
}
