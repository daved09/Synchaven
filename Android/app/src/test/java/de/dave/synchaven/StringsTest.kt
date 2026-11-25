package de.dave.synchaven

import org.junit.Assert.assertEquals
import org.junit.Test

/** English (default) and German have the same strings with the same placeholders. */
class StringsTest {
    private val en = XmlStrings("en")
    private val de = XmlStrings("de")
    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfx]""")

    private fun placeholders(s: String) = placeholder.findAll(s).map { it.value }.sorted().toList()

    @Test
    fun sameKeys() {
        // Non-translatable strings only exist in the default file
        val translatable = en.strings.keys - setOf("app_name", "recurrence_rrule", "recurrence_rrule_placeholder", "login_server_placeholder")
        assertEquals(translatable.sorted(), de.strings.keys.sorted())
        assertEquals(en.plurals.keys.sorted(), de.plurals.keys.sorted())
    }

    @Test
    fun samePlaceholders() {
        for ((name, text) in de.strings) {
            assertEquals(name, placeholders(en.strings.getValue(name)), placeholders(text))
        }
        for ((name, items) in de.plurals) {
            // Every quantity of a language uses the placeholders of its "other" form (except words for "one")
            val expected = placeholders(en.plurals.getValue(name).getValue("other"))
            assertEquals(name, expected, placeholders(items.getValue("other")))
        }
    }
}
