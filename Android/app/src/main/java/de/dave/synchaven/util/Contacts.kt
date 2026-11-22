package de.dave.synchaven.util

import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.PostalAddress
import de.dave.synchaven.data.model.TypedValue
import de.dave.synchaven.toUiText
import de.dave.synchaven.uiText
import java.text.Normalizer
import java.time.LocalDate
import java.time.Period

// Hilfen für Kontakte (wie utils/contact.ts im Web).

/** Auswahlmöglichkeit mit Anzeigetext und gespeichertem Wert */
data class TypeOption(val title: UiText, val value: String)

val PHONE_TYPES = listOf(
    TypeOption(uiText(R.string.type_mobile), "mobile"), TypeOption(uiText(R.string.type_home), "home"),
    TypeOption(uiText(R.string.type_work), "work"), TypeOption(uiText(R.string.type_fax), "fax"),
    TypeOption(uiText(R.string.type_pager), "pager"), TypeOption(uiText(R.string.type_other), "other"),
)
val OTHER_TYPES = listOf(
    TypeOption(uiText(R.string.type_home), "home"), TypeOption(uiText(R.string.type_work), "work"),
    TypeOption(uiText(R.string.type_other), "other"),
)

/** Anzeigetext eines Typs; eigene Bezeichnungen werden unverändert angezeigt. */
fun typeLabel(type: String, phone: Boolean = false): UiText =
    (if (phone) PHONE_TYPES else OTHER_TYPES).firstOrNull { it.value == type }?.title
        ?: if (type.isBlank()) uiText(R.string.type_other) else type.toUiText()

/** Anzeigename aus den Namensteilen (wie im Backend). */
fun Contact.derivedName(): String {
    val name = listOf(prefix, givenName, additionalNames, familyName, suffix)
        .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    return (listOf(name, organization.trim()) + emails.map { it.value.trim() } + phones.map { it.value.trim() })
        .firstOrNull { it.isNotEmpty() } ?: ""
}

/** Anzeigename; leer, wenn der Kontakt weder Name noch Firma, E-Mail oder Telefon hat */
fun Contact.displayName(): String = fullName.ifBlank { derivedName() }

/** Anzeigename mit Platzhalter für Kontakte ohne Namen */
fun Contact.displayNameText(): UiText = displayName().ifBlank { null }?.toUiText() ?: uiText(R.string.contact_no_name)

fun Contact.initials(): String {
    val parts = if (givenName.isNotBlank() || familyName.isNotBlank()) listOf(givenName, familyName)
    else displayName().split(Regex("\\s+"))
    return parts.filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.trim().take(1).uppercase(LOCALE) }
        .ifEmpty { "?" }
}

private val AVATAR_COLORS = listOf(
    "#6750A4", "#0B57D0", "#00897B", "#33B679", "#E37400", "#F4511E", "#D50000", "#8E24AA", "#3F51B5", "#795548",
)

fun Contact.avatarColor(): String {
    var h = 0u
    for (ch in uid.ifEmpty { id }) h = h * 31u + ch.code.toUInt()
    return AVATAR_COLORS[(h % AVATAR_COLORS.size.toUInt()).toInt()]
}

/** Buchstabe für die Gruppierung der Liste */
fun Contact.groupLetter(): String {
    val first = displayName().trim().take(1).uppercase(LOCALE)
    val base = Normalizer.normalize(first, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return if (base.length == 1 && base[0] in 'A'..'Z') base else "#"
}

fun Contact.subtitle(): String =
    listOf(title, organization).filter { it.isNotBlank() }.joinToString(", ")
        .ifEmpty { emails.firstOrNull()?.value ?: phones.firstOrNull()?.value ?: "" }

fun Contact.matches(query: String): Boolean {
    val q = query.trim().lowercase(LOCALE)
    if (q.isEmpty()) return true
    val digits = q.replace(Regex("[^\\d+]"), "")
    val hay = (listOf(fullName, nickname, organization, department, title, note) +
        emails.map { it.value } + phones.map { it.value } +
        addresses.map { "${it.street} ${it.postalCode} ${it.locality} ${it.country}" })
        .joinToString(" ").lowercase(LOCALE)
    if (hay.contains(q)) return true
    return digits.length >= 3 && phones.any { it.value.replace(Regex("[^\\d+]"), "").contains(digits) }
}

fun PostalAddress.lines(): List<UiText> = listOfNotNull(
    if (poBox.isNotBlank()) uiText(R.string.address_po_box, poBox) else null,
) + listOf(
    extended,
    street,
    listOf(postalCode, locality).filter { it.isNotBlank() }.joinToString(" "),
    region,
    country,
).filter { it.isNotBlank() }.map { it.toUiText() }

fun PostalAddress.isBlank(): Boolean =
    listOf(poBox, extended, street, postalCode, locality, region, country).all { it.isBlank() }

fun PostalAddress.mapQuery(): String =
    listOf(street, postalCode, locality, country).filter { it.isNotBlank() }.joinToString(", ")

fun TypedValue.websiteUrl(): String =
    if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(value)) value else "https://$value"

// ---------- Geburtstag ----------

private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
private val NO_YEAR = Regex("""^--(\d{2})-(\d{2})$""")
private val GERMAN_DATE = Regex("""^(\d{1,2})\.\s*(\d{1,2})\.?\s*(\d{4})?$""")

/** `1990-05-12` → `12.05.1990`, `--05-12` → `12.05.` */
fun birthdayToInput(b: String): String {
    ISO_DATE.matchEntire(b)?.let { val (y, m, d) = it.destructured; return "$d.$m.$y" }
    NO_YEAR.matchEntire(b)?.let { val (m, d) = it.destructured; return "$d.$m." }
    return b
}

/** `12.5.1990` / `12.05.` / `1990-05-12` → gespeichertes Format; `null` bei ungültiger Eingabe */
fun birthdayFromInput(s: String): String? {
    val t = s.trim()
    if (t.isEmpty()) return ""
    val day: Int
    val month: Int
    val year: Int?
    val g = GERMAN_DATE.matchEntire(t)
    val iso = ISO_DATE.matchEntire(t)
    when {
        g != null -> {
            day = g.groupValues[1].toInt(); month = g.groupValues[2].toInt()
            year = g.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()
        }
        iso != null -> {
            year = iso.groupValues[1].toInt(); month = iso.groupValues[2].toInt(); day = iso.groupValues[3].toInt()
        }
        else -> return null
    }
    runCatching { LocalDate.of(year ?: 2000, month, day) }.getOrNull() ?: return null
    val mm = month.toString().padStart(2, '0')
    val dd = day.toString().padStart(2, '0')
    return if (year == null) "--$mm-$dd" else "$year-$mm-$dd"
}

fun formatBirthday(b: String, today: LocalDate = LocalDate.now()): UiText {
    ISO_DATE.matchEntire(b)?.let {
        val d = runCatching { LocalDate.parse(b) }.getOrNull() ?: return b.toUiText()
        val age = Period.between(d, today).years
        val text = d.format(FMT_DATE_LONG)
        return if (!d.isAfter(today) && age < 130) UiText.Plural(R.plurals.birthday_age, age, listOf(text, age))
        else text.toUiText()
    }
    NO_YEAR.matchEntire(b)?.let {
        val (m, d) = it.destructured
        return runCatching { LocalDate.of(2000, m.toInt(), d.toInt()).format(FMT_DAY_MONTH) }.getOrDefault(b).toUiText()
    }
    return b.toUiText()
}
