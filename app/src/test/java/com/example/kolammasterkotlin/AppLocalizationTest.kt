package com.kolammaster.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class AppLocalizationTest {
    @Test
    fun allTwelveSupportedLanguageIdentifiersHaveLocaleMappings() {
        assertEquals("English", DEFAULT_LANGUAGE_IDENTIFIER)
        assertEquals(
            listOf(
                "English",
                "Tamil",
                "Hindi",
                "Kannada",
                "Telugu",
                "Malayalam",
                "Thai",
                "Chinese (Simplified)",
                "Vietnamese",
                "Malay",
                "Japanese",
                "Korean"
            ),
            supportedLanguages.map { it.first }
        )
        assertEquals(supportedLanguageIdentifiers, supportedLanguages.map { it.first })
        assertEquals(12, supportedLanguageIdentifiers.map(::localeTagForLanguage).toSet().size)
        assertEquals("en", localeTagForLanguage("unknown"))
        assertEquals("en", localeTagForLanguage("English"))
        assertEquals("zh-CN", localeTagForLanguage("Chinese (Simplified)"))
    }

    @Test
    fun importantInterfaceResourcesExistForEverySupportedLanguage() {
        val resourceRoot = File("src/main/res")
        val defaultKeys = resourceKeys(File(resourceRoot, "values/strings.xml")) -
            "app_name"
        val languageDirectories = mapOf(
            "Tamil" to "values-ta",
            "Hindi" to "values-hi",
            "Kannada" to "values-kn",
            "Telugu" to "values-te",
            "Malayalam" to "values-ml",
            "Thai" to "values-th",
            "Chinese (Simplified)" to "values-zh-rCN",
            "Vietnamese" to "values-vi",
            "Malay" to "values-ms",
            "Japanese" to "values-ja",
            "Korean" to "values-ko"
        )

        assertEquals(supportedLanguageIdentifiers.drop(1).toSet(), languageDirectories.keys)
        languageDirectories.forEach { (language, directory) ->
            val translatedKeys = resourceKeys(
                File(resourceRoot, "$directory/strings.xml")
            )
            assertEquals(
                "Missing keys for $language",
                emptySet<String>(),
                defaultKeys - translatedKeys
            )
        }
    }

    @Test
    fun onboardingAndHistoryCopyIsLocalizedInEveryCatalog() {
        val resourceRoot = File("src/main/res")
        val directories = mapOf(
            "English" to "values",
            "Tamil" to "values-ta",
            "Hindi" to "values-hi",
            "Kannada" to "values-kn",
            "Telugu" to "values-te",
            "Malayalam" to "values-ml",
            "Thai" to "values-th",
            "Chinese (Simplified)" to "values-zh-rCN",
            "Vietnamese" to "values-vi",
            "Malay" to "values-ms",
            "Japanese" to "values-ja",
            "Korean" to "values-ko"
        )
        val keys = setOf(
            "onboarding_kolam_history",
            "onboarding_begin",
            "history_title",
            "history_intro",
            "history_sikku_pulli_title",
            "history_sikku_origin_location",
            "history_sikku_origin_story",
            "history_ancient_dot_grids",
            "history_loop_belief",
            "history_sikku_description",
            "history_pulli_description",
            "history_rangoli_title",
            "history_rangoli_origin_location",
            "history_rangoli_origin_story",
            "history_sangam_title",
            "history_sangam_description",
            "history_festival_title",
            "history_festival_description",
            "history_modern_science_title",
            "history_modern_science_discovery",
            "history_modern_science_languages",
            "history_modern_science_research",
            "history_brahma_timing_title",
            "history_brahma_timing_description",
            "history_brahma_cultural_belief",
            "history_brahma_social_signal",
            "history_photo_collection"
        )
        val preservedLessonTypeNames = setOf("history_sikku_pulli_title", "history_rangoli_title")
        val english = resourceValues(File(resourceRoot, "values/strings.xml"))
        directories.forEach { (language, directory) ->
            val localized = resourceValues(File(resourceRoot, "$directory/strings.xml"))
            keys.forEach { key ->
                assertTrue("$language is missing $key", key in localized)
                if (language != "English" && key !in preservedLessonTypeNames) {
                    assertNotEquals("$language/$key is still English", english.getValue(key), localized.getValue(key))
                }
            }
        }
    }

    @Test
    fun requestedLandingProgressCompletionAndCategoryCopyIsLocalized() {
        val resourceRoot = File("src/main/res")
        val directories = mapOf(
            "English" to "values",
            "Tamil" to "values-ta",
            "Hindi" to "values-hi",
            "Kannada" to "values-kn",
            "Telugu" to "values-te",
            "Malayalam" to "values-ml",
            "Thai" to "values-th",
            "Chinese (Simplified)" to "values-zh-rCN",
            "Vietnamese" to "values-vi",
            "Malay" to "values-ms",
            "Japanese" to "values-ja",
            "Korean" to "values-ko"
        )
        val keys = setOf(
            "learn",
            "my_folders",
            "community",
            "history_title",
            "preparing_learn_thumbnails",
            "downloading_learn_thumbnails",
            "signing_in",
            "continuing_as_guest",
            "loading_lesson",
            "downloading_lesson",
            "congratulations",
            "your_kolam_is_complete",
            "share_kolam_community",
            "continue_finished_kolam",
            "proceed",
            "cancel",
            "announcement_alert_title",
            "announcement_alert_message",
            "support_alert_title",
            "open",
            "later",
            "history_sikku_pulli_title",
            "history_rangoli_title",
            "new_conversation_description",
            "dots_order_heading"
        )
        val categoryHeadings = setOf(
            "history_sikku_pulli_title",
            "history_rangoli_title"
        )
        val english = resourceValues(File(resourceRoot, "values/strings.xml"))

        directories.forEach { (language, directory) ->
            val localized = resourceValues(File(resourceRoot, "$directory/strings.xml"))
            keys.forEach { key ->
                assertTrue("$language is missing $key", key in localized)
                assertTrue("$language/$key is blank", localized.getValue(key).isNotBlank())
                if (language != "English" && key !in categoryHeadings) {
                    assertNotEquals(
                        "$language/$key is still English",
                        english.getValue(key),
                        localized.getValue(key)
                    )
                }
            }
        }
    }

    @Test
    fun nonEnglishLocalesAreDistinctAndEnglishRemainsTheFallback() {
        assertNotEquals(localeTagForLanguage("English"), localeTagForLanguage("Tamil"))
        assertNotEquals(localeTagForLanguage("English"), localeTagForLanguage("Japanese"))
        assertEquals("en", localeTagForLanguage("invalid identifier"))
    }

    @Test
    fun parameterizedUiMessagesFormatForEverySupportedLanguage() {
        val resourceRoot = File("src/main/res")
        val localizedDirectories = mapOf(
            "English" to "values",
            "Tamil" to "values-ta",
            "Hindi" to "values-hi",
            "Kannada" to "values-kn",
            "Telugu" to "values-te",
            "Malayalam" to "values-ml",
            "Thai" to "values-th",
            "Chinese (Simplified)" to "values-zh-rCN",
            "Vietnamese" to "values-vi",
            "Malay" to "values-ms",
            "Japanese" to "values-ja",
            "Korean" to "values-ko"
        )
        val formatArguments = mapOf(
            "filter_count" to listOf<Any>(4),
            "image_attached" to listOf<Any>(42),
            "delete_folder_message" to listOf<Any>("Favorites"),
            "speed_label" to listOf<Any>("1.5×"),
            "step_progress" to listOf<Any>(2, 5),
            "community_shared_by" to listOf<Any>("Asha"),
            "community_submitted_by" to listOf<Any>("Asha"),
            "community_lesson_id" to listOf<Any>("lesson-1"),
            "community_showing_last_loaded" to listOf<Any>("Network error"),
            "thumbnail_for" to listOf<Any>("lesson-1"),
            "saved_lesson_accessibility" to listOf<Any>("Lesson name"),
            "save_lesson_accessibility" to listOf<Any>("Lesson name"),
            "lesson_unlock_ads_completed" to listOf<Any>(1, 2),
            "lesson_playback_steps" to listOf<Any>(4),
            "lesson_error_detail" to listOf<Any>("technical detail")
        )
        val englishValues = resourceValues(File(resourceRoot, "values/strings.xml"))

        localizedDirectories.forEach { (language, directory) ->
            val localizedValues = resourceValues(
                File(resourceRoot, "$directory/strings.xml")
            )
            formatArguments.forEach { (key, arguments) ->
                val englishPattern = englishValues.getValue(key)
                val pattern = localizedValues.getValue(key)
                val placeholders = Regex("""%(\d+\$)?[a-zA-Z]""")
                    .findAll(englishPattern)
                    .map { it.value }
                    .sorted()
                    .toList()
                assertEquals(language, placeholders, Regex("""%(\d+\$)?[a-zA-Z]""")
                    .findAll(pattern)
                    .map { it.value }
                    .sorted()
                    .toList())
                val formatted = String.format(
                    java.util.Locale.forLanguageTag(localeTagForLanguage(language)),
                    pattern,
                    *arguments.toTypedArray()
                )
                assertTrue("$language/$key remained unformatted", formatted != pattern)
                assertTrue("$language/$key retained a format token", '%' !in formatted)
            }
        }

    }

    @Test
    fun pluralResourcesAreAvailableForEverySupportedLanguage() {
        val resourceRoot = File("src/main/res")
        val localizedDirectories = mapOf(
            "English" to "values",
            "Tamil" to "values-ta",
            "Hindi" to "values-hi",
            "Kannada" to "values-kn",
            "Telugu" to "values-te",
            "Malayalam" to "values-ml",
            "Thai" to "values-th",
            "Chinese (Simplified)" to "values-zh-rCN",
            "Vietnamese" to "values-vi",
            "Malay" to "values-ms",
            "Japanese" to "values-ja",
            "Korean" to "values-ko"
        )
        val defaultPlurals = pluralValues(File(resourceRoot, "values/strings.xml"))
        assertTrue(defaultPlurals.isNotEmpty())

        localizedDirectories.forEach { (language, directory) ->
            val localePlurals = pluralValues(File(resourceRoot, "$directory/strings.xml"))
            assertEquals(
                "Missing plural resources for $language",
                defaultPlurals.keys,
                localePlurals.keys
            )
            defaultPlurals.forEach { (name, defaultItems) ->
                val translatedItems = localePlurals.getValue(name)
                assertTrue("$language/$name missing other quantity", "other" in translatedItems)
                translatedItems.forEach { (quantity, pattern) ->
                    val defaultPattern = defaultItems[quantity]
                        ?: defaultItems.getValue("other")
                    assertEquals(
                        "$language/$name/$quantity placeholders",
                        Regex("""%(\d+\$)?[a-zA-Z]""")
                            .findAll(defaultPattern)
                            .map { it.value }
                            .toList(),
                        Regex("""%(\d+\$)?[a-zA-Z]""")
                            .findAll(pattern)
                            .map { it.value }
                            .toList()
                    )
                    val formatted = String.format(
                        java.util.Locale.forLanguageTag(localeTagForLanguage(language)),
                        pattern,
                        3,
                        12
                    )
                    assertTrue("$language/$name did not format", '%' !in formatted)
                }
            }
        }
    }

    private fun resourceKeys(file: File): Set<String> {
        assertTrue("Missing resource file: ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.documentElement.childNodes
        return buildSet {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                node.attributes?.getNamedItem("name")?.nodeValue?.let(::add)
            }
        }
    }

    private fun resourceValues(file: File): Map<String, String> {
        assertTrue("Missing resource file: ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(file)
        val nodes = document.documentElement.childNodes
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE) continue
                val name = node.attributes?.getNamedItem("name")?.nodeValue
                if (name != null && node.nodeName == "string") {
                    put(name, node.textContent)
                }

            }
        }
    }

    private fun pluralValues(file: File): Map<String, Map<String, String>> {
        assertTrue("Missing resource file: ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val nodes = factory.newDocumentBuilder().parse(file).documentElement.childNodes
        return buildMap {
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                if (node.nodeType != org.w3c.dom.Node.ELEMENT_NODE ||
                    node.nodeName != "plurals"
                ) continue
                val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
                val items = node.childNodes
                put(name, buildMap {
                    for (itemIndex in 0 until items.length) {
                        val item = items.item(itemIndex)
                        if (item.nodeType != org.w3c.dom.Node.ELEMENT_NODE ||
                            item.nodeName != "item"
                        ) continue
                        val quantity = item.attributes.getNamedItem("quantity")?.nodeValue
                            ?: continue
                        put(quantity, item.textContent)
                    }
                })
            }
        }
    }
}
