package de.dave.synchaven

import de.dave.synchaven.data.local.LocalTexts
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** The app's `strings.xml` of a language, read without Android (JVM tests run in the module directory). */
class XmlStrings(language: String) : LocalTexts {
    private val file = File("src/main/res/${if (language == "en") "values" else "values-$language"}/strings.xml")
    private val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    /** Plain strings by name */
    val strings: Map<String, String> = elements("string").associate { it.getAttribute("name") to unescape(it.textContent) }

    /** Plurals by name: quantity → text */
    val plurals: Map<String, Map<String, String>> = elements("plurals").associate { p ->
        val items = p.getElementsByTagName("item")
        p.getAttribute("name") to (0 until items.length).map { items.item(it) as Element }
            .associate { it.getAttribute("quantity") to unescape(it.textContent) }
    }

    override fun get(id: Int, vararg args: Any): String {
        val name = R.string::class.java.fields.first { it.getInt(null) == id }.name
        return String.format(Locale.ROOT, strings.getValue(name), *args)
    }

    private fun elements(tag: String): List<Element> =
        doc.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    private fun unescape(s: String) = s.removeSurrounding("\"").replace("\\'", "'").replace("\\n", "\n").replace("\\\"", "\"")
}
