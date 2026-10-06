package com.kolammaster.app

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNewConversationScreenTest {
    @Test
    fun phoneFieldUsesAndroidPhoneKeyboardType() {
        assertEquals(KeyboardType.Phone, contactPhoneKeyboardOptions().keyboardType)
    }
}
