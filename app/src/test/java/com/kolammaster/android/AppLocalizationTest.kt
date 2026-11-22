package com.kolammaster.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppLocalizationTest {
    @Test
    fun dictionaryContainsAllExpectedEntriesForEverySupportedLanguage() {
        assertEquals("English", DEFAULT_LANGUAGE_IDENTIFIER)
        assertEquals(12, supportedLanguageIdentifiers.size)
        assertEquals(
            12,
            supportedLanguageIdentifiers.map(::localeTagForLanguage).toSet().size
        )
        assertEquals("zh-CN", localeTagForLanguage("Chinese (Simplified)"))
        assertEquals(333, TranslationDictionary.stringKeys(DEFAULT_LANGUAGE_IDENTIFIER).size)
        assertEquals(setOf("app_name"), TranslationDictionary.stringKeys(DEFAULT_LANGUAGE_IDENTIFIER) -
            TranslationDictionary.stringKeys("Tamil"))

        val expectedTranslatedKeys =
            TranslationDictionary.stringKeys(DEFAULT_LANGUAGE_IDENTIFIER) - "app_name"
        supportedLanguageIdentifiers.forEach { language ->
            val expectedStringCount = if (language == DEFAULT_LANGUAGE_IDENTIFIER) 333 else 332
            assertEquals(expectedStringCount, TranslationDictionary.stringKeys(language).size)
            if (language != DEFAULT_LANGUAGE_IDENTIFIER) {
                assertEquals(expectedTranslatedKeys, TranslationDictionary.stringKeys(language))
            }
            assertEquals(
                setOf("folder_lesson_count", "watch_ads_unlock_lesson", "image_count"),
                TranslationDictionary.pluralKeys(language)
            )
        }
        assertEquals(3_985, supportedLanguageIdentifiers.sumOf {
            TranslationDictionary.stringKeys(it).size
        })
        assertEquals(36, supportedLanguageIdentifiers.sumOf {
            TranslationDictionary.pluralKeys(it).size
        })
    }

    @Test
    fun missingSelectedLanguageEntriesFallBackOnlyToDictionaryEnglish() {
        assertEquals(
            TranslationDictionary.getString(DEFAULT_LANGUAGE_IDENTIFIER, "app_name"),
            TranslationDictionary.getStringOrEnglish("Tamil", "app_name")
        )
        assertEquals(
            TranslationDictionary.getString(DEFAULT_LANGUAGE_IDENTIFIER, "home"),
            TranslationDictionary.getStringOrEnglish("unsupported", "home")
        )
    }

    @Test
    fun communityMemberIsTranslatedForEverySupportedLanguage() {
        assertEquals(
            "Community member",
            TranslationDictionary.getString(DEFAULT_LANGUAGE_IDENTIFIER, "community_member")
        )
        supportedLanguageIdentifiers.forEach { language ->
            assertTrue(
                "$language is missing community_member",
                TranslationDictionary.getStringOrEnglish(language, "community_member").isNotBlank()
            )
        }
    }

    @Test
    fun formattedStringsUseDictionaryPatterns() {
        assertEquals(
            "42 KB image attached",
            formatAppString("English", "image_attached", arrayOf(42))
        )
        assertEquals(
            "42 KB படம் இணைக்கப்பட்டது",
            formatAppString("Tamil", "image_attached", arrayOf(42))
        )
    }

    @Test
    fun dictionaryPluralLookupUsesTranslatedOneAndOtherForms() {
        assertEquals(
            "1 lesson",
            formatAppPlural("English", "folder_lesson_count", 1, emptyArray())
        )
        assertEquals(
            "2 lessons",
            formatAppPlural("English", "folder_lesson_count", 2, emptyArray())
        )
        assertEquals(
            "1 件のレッスン",
            formatAppPlural("Japanese", "folder_lesson_count", 1, emptyArray())
        )
        assertEquals(
            "3 / 12 张图片",
            formatAppPlural("Chinese (Simplified)", "image_count", 3, arrayOf(3, 12))
        )
    }

    @Test
    fun formattingPlaceholdersArePreservedForAllTranslationsAndPlurals() {
        val placeholderPattern = Regex("""%(\d+\$)?[a-zA-Z]""")
        val englishKeys = TranslationDictionary.stringKeys(DEFAULT_LANGUAGE_IDENTIFIER)
        supportedLanguageIdentifiers.forEach { language ->
            englishKeys.forEach { key ->
                val english = TranslationDictionary.getString(DEFAULT_LANGUAGE_IDENTIFIER, key)
                    ?: error("Missing English string $key")
                val translated = TranslationDictionary.getString(language, key) ?: return@forEach
                assertEquals(
                    "$language/$key",
                    placeholderPattern.findAll(english).map { it.value }.toList().sorted(),
                    placeholderPattern.findAll(translated).map { it.value }.toList().sorted()
                )
            }

            TranslationDictionary.pluralKeys(language).forEach { key ->
                val englishPlural = TranslationDictionary.getPlural(DEFAULT_LANGUAGE_IDENTIFIER, key)
                    ?: error("Missing English plural $key")
                val translatedPlural = TranslationDictionary.getPlural(language, key)
                    ?: error("Missing $language plural $key")
                translatedPlural.forEach { (quantity, pattern) ->
                    val englishPattern = englishPlural[quantity] ?: englishPlural.getValue("other")
                    assertEquals(
                        "$language/$key/$quantity",
                        placeholderPattern.findAll(englishPattern).map { it.value }.toList().sorted(),
                        placeholderPattern.findAll(pattern).map { it.value }.toList().sorted()
                    )
                }
            }
        }
    }

    @Test
    fun dictionaryContainsNoReplacementCharactersAndJapaneseSentenceIsCorrected() {
        supportedLanguageIdentifiers.forEach { language ->
            TranslationDictionary.stringKeys(language).forEach { key ->
                assertFalse(
                    "$language/$key contains U+FFFD",
                    TranslationDictionary.getString(language, key).orEmpty().contains('\uFFFD')
                )
            }
            TranslationDictionary.pluralKeys(language).forEach { key ->
                TranslationDictionary.getPlural(language, key).orEmpty().forEach { (quantity, value) ->
                    assertFalse("$language/$key/$quantity contains U+FFFD", '\uFFFD' in value)
                }
            }
        }

        val japaneseMessage = TranslationDictionary.getString("Japanese", "delete_account_message")
        assertTrue(japaneseMessage.orEmpty().contains("\u300c\u304a\u554f\u3044\u5408\u308f\u305b\u300d\u306e\u4f1a\u8a71"))
        assertNotEquals(
            TranslationDictionary.getString(DEFAULT_LANGUAGE_IDENTIFIER, "delete_account_message"),
            japaneseMessage
        )
    }

    @Test
    fun dictionaryDoesNotDependOnAndroidTranslationResourcesOrFallbacks() {
        val resourceRoot = File("src/main/res")
        assertTrue(resourceRoot.isDirectory)
        assertFalse(resourceRoot.walkTopDown().any { it.name == "strings.xml" })
        assertTrue(TranslationDictionary.getString("Tamil", "home").orEmpty().isNotBlank())
        assertTrue(TranslationDictionary.getPlural("Tamil", "image_count").orEmpty().isNotEmpty())

        val source = File(
            "src/main/java/com/kolammaster/android/TranslationDictionary.kt"
        ).readText()
        assertFalse(source.contains("context.getString("))
        assertFalse(source.contains("getQuantityString("))
        assertFalse(source.contains("R.string"))
        assertFalse(source.contains("R.plurals"))
    }

    @Test
    fun countryPickerNamesUseTheSelectedLocale() {
        val india = ContactCountryCallingCode("India", "IN", "+91")
        assertNotEquals("India", india.localizedName(java.util.Locale.forLanguageTag("ta")))
        assertNotEquals("India", india.localizedName(java.util.Locale.forLanguageTag("hi")))
    }
}
