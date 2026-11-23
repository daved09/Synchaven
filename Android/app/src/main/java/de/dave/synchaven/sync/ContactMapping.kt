package de.dave.synchaven.sync

import android.provider.ContactsContract.CommonDataKinds.BaseTypes
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website

// Typen von Telefonnummern, E-Mail-Adressen, Adressen und Websites zwischen API (`mobile`, `home`, `work`,
// `fax`, `pager`, `other` oder eigene Bezeichnung) und ContactsContract. Nur Konstanten – ohne Android testbar.

/** Typ und eigene Bezeichnung für eine Datenzeile in ContactsContract */
data class AndroidType(val type: Int, val label: String? = null)

private fun custom(label: String) = AndroidType(BaseTypes.TYPE_CUSTOM, label)

fun phoneTypeToAndroid(type: String): AndroidType = when (type.trim().lowercase()) {
    "mobile" -> AndroidType(Phone.TYPE_MOBILE)
    "home" -> AndroidType(Phone.TYPE_HOME)
    "work" -> AndroidType(Phone.TYPE_WORK)
    "fax" -> AndroidType(Phone.TYPE_OTHER_FAX)
    "pager" -> AndroidType(Phone.TYPE_PAGER)
    "other", "" -> AndroidType(Phone.TYPE_OTHER)
    else -> custom(type.trim())
}

/** [typeLabel] liefert die Bezeichnung, die Android für Typen ohne Entsprechung in der API anzeigt. */
fun phoneTypeFromAndroid(type: Int, label: String?, typeLabel: (Int) -> String): String = when (type) {
    Phone.TYPE_MOBILE -> "mobile"
    Phone.TYPE_HOME -> "home"
    Phone.TYPE_WORK -> "work"
    Phone.TYPE_FAX_HOME, Phone.TYPE_FAX_WORK, Phone.TYPE_OTHER_FAX -> "fax"
    Phone.TYPE_PAGER, Phone.TYPE_WORK_PAGER -> "pager"
    Phone.TYPE_OTHER -> "other"
    BaseTypes.TYPE_CUSTOM -> label?.trim()?.ifEmpty { null } ?: "other"
    else -> typeLabel(type).ifBlank { "other" }
}

fun emailTypeToAndroid(type: String): AndroidType = when (type.trim().lowercase()) {
    "home" -> AndroidType(Email.TYPE_HOME)
    "work" -> AndroidType(Email.TYPE_WORK)
    "other", "" -> AndroidType(Email.TYPE_OTHER)
    else -> custom(type.trim())
}

fun emailTypeFromAndroid(type: Int, label: String?, typeLabel: (Int) -> String): String = when (type) {
    Email.TYPE_HOME -> "home"
    Email.TYPE_WORK -> "work"
    Email.TYPE_OTHER -> "other"
    BaseTypes.TYPE_CUSTOM -> label?.trim()?.ifEmpty { null } ?: "other"
    else -> typeLabel(type).ifBlank { "other" }
}

fun postalTypeToAndroid(type: String): AndroidType = when (type.trim().lowercase()) {
    "home" -> AndroidType(StructuredPostal.TYPE_HOME)
    "work" -> AndroidType(StructuredPostal.TYPE_WORK)
    "other", "" -> AndroidType(StructuredPostal.TYPE_OTHER)
    else -> custom(type.trim())
}

fun postalTypeFromAndroid(type: Int, label: String?): String = when (type) {
    StructuredPostal.TYPE_HOME -> "home"
    StructuredPostal.TYPE_WORK -> "work"
    BaseTypes.TYPE_CUSTOM -> label?.trim()?.ifEmpty { null } ?: "other"
    else -> "other"
}

fun websiteTypeToAndroid(type: String): AndroidType = when (type.trim().lowercase()) {
    "home" -> AndroidType(Website.TYPE_HOME)
    "work" -> AndroidType(Website.TYPE_WORK)
    "other", "" -> AndroidType(Website.TYPE_OTHER)
    else -> custom(type.trim())
}

fun websiteTypeFromAndroid(type: Int, label: String?): String = when (type) {
    Website.TYPE_HOME -> "home"
    Website.TYPE_WORK -> "work"
    BaseTypes.TYPE_CUSTOM -> label?.trim()?.ifEmpty { null } ?: "other"
    else -> "other"
}

private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})""")
private val BASIC_DATE = Regex("""^(\d{4})(\d{2})(\d{2})$""")
private val NO_YEAR = Regex("""^--(\d{2})-?(\d{2})$""")

/**
 * Geburtstag aus ContactsContract (`1990-05-12`, `--05-12`, `19900512`, auch mit Uhrzeit) im Format der API
 * (`YYYY-MM-DD` oder `--MM-DD`); leer, wenn er sich nicht lesen lässt.
 */
fun normalizeBirthday(value: String?): String {
    val v = value?.trim().orEmpty()
    val (y, m, d) = ISO_DATE.find(v)?.destructured?.let { Triple(it.component1(), it.component2(), it.component3()) }
        ?: BASIC_DATE.matchEntire(v)?.destructured?.let { Triple(it.component1(), it.component2(), it.component3()) }
        ?: NO_YEAR.matchEntire(v)?.destructured?.let { Triple("", it.component1(), it.component2()) }
        ?: return ""
    val month = m.toInt()
    val day = d.toInt()
    if (month !in 1..12 || day !in 1..31) return ""
    // 1604 ist die Apple-Konvention für „ohne Jahr“
    return if (y.isEmpty() || y == "1604" || y == "0000") "--$m-$d" else "$y-$m-$d"
}

/** Adresse in Zeilen wie im Adressfeld eines Briefs – für FORMATTED_ADDRESS */
fun formatAddress(
    street: String, extended: String, poBox: String, postalCode: String, locality: String, region: String, country: String,
): String = listOf(
    street, extended, poBox, listOf(postalCode, locality).filter { it.isNotBlank() }.joinToString(" "), region, country,
).filter { it.isNotBlank() }.joinToString("\n")
