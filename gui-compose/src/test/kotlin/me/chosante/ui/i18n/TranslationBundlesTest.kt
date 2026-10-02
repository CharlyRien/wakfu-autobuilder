package me.chosante.ui.i18n

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * CI guard for the `i18n/strings_<lang>.properties` bundles: with [Tr] now just a list of keys (no
 * inline strings), a missing or mistyped translation is no longer a compile error, so this test is
 * the thing that fails the build instead — exactly as a 3-argument enum constructor used to.
 */
class TranslationBundlesTest {
    private fun bundle(lang: Lang): Map<String, String> {
        val path = "/i18n/strings_${lang.resourceSuffix}.properties"
        val stream = requireNotNull(javaClass.getResourceAsStream(path)) { "Missing $path" }
        val props = Properties().apply { stream.bufferedReader(Charsets.UTF_8).use { load(it) } }
        return props.stringPropertyNames().associateWith { props.getProperty(it) }
    }

    // java.util.Formatter tokens used by the GUI (%s, %d, %1$s, %.1f, %%); a literal "50% HP" is not one.
    private val placeholder = Regex("%(\\d+\\$)?(\\.\\d+)?[sdf%]")

    @Test
    fun `every language bundle defines exactly the Tr keys`() {
        val expected = Tr.entries.map { it.name }.toSet()
        Lang.entries.forEach { lang ->
            val strings = bundle(lang)
            assertThat(expected - strings.keys).describedAs("keys missing from $lang").isEmpty()
            assertThat(strings.keys - expected).describedAs("orphan keys in $lang").isEmpty()
            assertThat(strings.filterValues { it.isBlank() }.keys).describedAs("blank values in $lang").isEmpty()
        }
    }

    @Test
    fun `placeholders match the English bundle in every language`() {
        val en = bundle(Lang.EN)
        Lang.entries.forEach { lang ->
            bundle(lang).forEach { (key, value) ->
                assertThat(placeholder.findAll(value).map { it.value }.toList())
                    .describedAs("$lang $key")
                    .isEqualTo(placeholder.findAll(en.getValue(key)).map { it.value }.toList())
            }
        }
    }
}
