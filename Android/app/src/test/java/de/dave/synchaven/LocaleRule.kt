package de.dave.synchaven

import java.util.Locale
import org.junit.rules.ExternalResource

/** Runs a test with [locale] as the default locale (the app's language), restoring the previous one afterwards. */
class LocaleRule(private val locale: Locale) : ExternalResource() {
    private lateinit var previous: Locale

    override fun before() {
        previous = Locale.getDefault()
        Locale.setDefault(locale)
    }

    override fun after() = Locale.setDefault(previous)
}

/** Runs [block] with [locale] as the default locale. */
fun <T> withLocale(locale: Locale, block: () -> T): T {
    val previous = Locale.getDefault()
    Locale.setDefault(locale)
    try {
        return block()
    } finally {
        Locale.setDefault(previous)
    }
}
