package com.filestech.pass_tech.l10n

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The five languages, held against each other. Nothing here reads the app: it reads the resource
 * files themselves, because that is where a language goes missing.
 *
 * **What this catches that a green build does not.** Android falls back to `values/` for any key a
 * translation lacks, silently: the app runs, the screen is simply in English for that one line. And
 * a format placeholder that disappears in one language does not break the build either — it breaks
 * at run time, in that language only, on the phone of someone who reads it.
 *
 * What it cannot catch is a translation that is wrong, or one applied to four languages out of five
 * when the English was reworded. Counting keys is not reading meaning.
 */
class StringsParityTest {

    private val languages = listOf("fr", "de", "it", "es")

    private class Resources(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val untranslatable: Set<String>,
    )

    private fun read(directory: String): Resources {
        val file = File("src/main/res/$directory/strings.xml")
        assertThat(file.exists()).isTrue()
        val text = file.readText()
        val strings = mutableMapOf<String, String>()
        val untranslatable = mutableSetOf<String>()
        for (match in STRING.findAll(text)) {
            val (name, attributes, value) = match.destructured
            if (attributes.contains("""translatable="false"""")) untranslatable += name else strings[name] = value
        }
        val plurals = PLURALS.findAll(text).associate { match ->
            val (name, body) = match.destructured
            name to ITEM.findAll(body).associate { it.destructured.let { (quantity, value) -> quantity to value } }
        }
        return Resources(strings, plurals, untranslatable)
    }

    private val english = read("values")

    /** `%1$s`, `%2$d`… An order that differs between languages is normal; a missing one is not. */
    private fun placeholders(value: String): Set<String> = PLACEHOLDER.findAll(value).map { it.value }.toSet()

    @TestFactory
    fun `every language says everything the English says, and nothing else`(): List<DynamicTest> =
        languages.map { language ->
            DynamicTest.dynamicTest(language) {
                val translated = read("values-$language")
                assertThat(translated.strings.keys).containsExactlyElementsIn(english.strings.keys)
                assertThat(translated.plurals.keys).containsExactlyElementsIn(english.plurals.keys)
                // A key marked untranslatable belongs in `values/` alone: translating it there would
                // be dead weight, and worse, it would look maintained.
                assertThat(translated.strings.keys.intersect(english.untranslatable)).isEmpty()
            }
        }

    @TestFactory
    fun `every language takes the same arguments as the English`(): List<DynamicTest> =
        languages.map { language ->
            DynamicTest.dynamicTest(language) {
                val translated = read("values-$language")
                english.strings.forEach { (key, value) ->
                    assertThat(placeholders(translated.strings.getValue(key))).isEqualTo(placeholders(value))
                }
                // Over the whole block, not item by item: English writes "1 entry" where French
                // writes "%1$d entrée", and both are right.
                english.plurals.forEach { (key, items) ->
                    val expected = items.values.flatMap(::placeholders).toSet()
                    val actual = translated.plurals.getValue(key).values.flatMap(::placeholders).toSet()
                    assertThat(actual).isEqualTo(expected)
                }
            }
        }

    @TestFactory
    fun `every plural has the forms its language needs`(): List<DynamicTest> =
        languages.map { language ->
            DynamicTest.dynamicTest(language) {
                read("values-$language").plurals.forEach { (key, items) ->
                    assertThat(items.keys).contains("one")
                    assertThat(items.keys).contains("other")
                    assertThat(key).isNotEmpty()
                }
            }
        }

    /**
     * The three places a language lives, which know nothing about one another: the `values-*`
     * directory, the build's locale filter — which REMOVES a directory from the APK without a word
     * — and the list Android 13 reads to offer the language for this app alone.
     */
    @Test
    fun `a language is declared in all three places, or nobody can reach it`() {
        val all = (languages + "en").toSet()
        val gradle = File("build.gradle.kts").readText()
        val filter = requireNotNull(LOCALE_FILTERS.find(gradle)) { "localeFilters not found in build.gradle.kts" }
        assertThat(QUOTED.findAll(filter.groupValues[1]).map { it.groupValues[1] }.toSet()).isEqualTo(all)

        val config = File("src/main/res/xml/locales_config.xml").readText()
        assertThat(LOCALE_NAME.findAll(config).map { it.groupValues[1] }.toSet()).isEqualTo(all)

        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).contains("""android:localeConfig="@xml/locales_config"""")
    }

    private companion object {
        val STRING = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val PLURALS = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        val ITEM = Regex("""<item quantity="([^"]+)">(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)
        val PLACEHOLDER = Regex("""%\d+\$[sd]""")
        val LOCALE_FILTERS = Regex("""localeFilters\s*\+?=\s*listOf\(([^)]*)\)""")
        val QUOTED = Regex(""""([^"]+)"""")
        val LOCALE_NAME = Regex("""<locale android:name="([^"]+)"\s*/>""")
    }
}
