package com.kolammaster.app

import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactNewConversationScreenTest {
    @Test
    fun phoneFieldUsesAndroidPhoneKeyboardType() {
        assertEquals(KeyboardType.Phone, contactPhoneKeyboardOptions().keyboardType)
    }

    @Test
    fun indiaIsTheDefaultCountry() {
        assertEquals("India", defaultContactCountryCallingCode.countryName)
        assertEquals("+91", defaultContactCountryCallingCode.callingCode)
        assertEquals("\uD83C\uDDEE\uD83C\uDDF3", defaultContactCountryCallingCode.flagEmoji())
    }

    @Test
    fun constructsInternationalPhoneUsingIndiaCallingCode() {
        assertEquals(
            "+919876543210",
            internationalContactPhone(defaultContactCountryCallingCode, "9876543210")
        )
    }

    @Test
    fun rejectsEmptyOrNonDigitLocalPhoneNumbers() {
        assertFalse(isValidLocalContactPhone(""))
        assertFalse(isValidLocalContactPhone("98765a3210"))
        assertFalse(isValidLocalContactPhone("987 6543210"))
        assertTrue(isValidLocalContactPhone("123"))
        assertEquals("9876543210", digitsOnlyLocalContactPhoneInput("98ab76543210"))
    }

    @Test
    fun selectedCountryChangesInternationalCallingCode() {
        val unitedStates = contactCountryCallingCodes.first { it.countryCode == "US" }

        assertEquals("+19876543210", internationalContactPhone(unitedStates, "9876543210"))
    }

    @Test
    fun submissionAcceptsBasicInternationalDigitsWithoutStrictLengthChecks() {
        assertTrue(isValidContactPhoneForSubmission("+911"))
        assertFalse(isValidContactPhoneForSubmission("+91abc"))
    }

    @Test
    fun acceptsImageMimeTypesAndRejectsNonImages() {
        assertTrue(isSupportedContactImageMimeType("image/jpeg"))
        assertTrue(isSupportedContactImageMimeType("image/png"))
        assertFalse(isSupportedContactImageMimeType("video/mp4"))
        assertFalse(isSupportedContactImageMimeType("application/pdf"))
    }
}
