package de.dave.synchaven.data.local

import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.PostalAddress
import de.dave.synchaven.data.model.TypedValue
import de.dave.synchaven.util.displayName
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * vCard lesen (2.1, 3.0, 4.0) und schreiben (3.0) im lokalen Modus – wie `backend/src/vcard.rs`. Lokal werden
 * Kontakte strukturiert gespeichert; beim Import bleiben deshalb nur die Felder erhalten, die die App kennt.
 */
object VCard {
    data class Line(
        val group: String? = null,
        /** Großgeschrieben, z. B. `TEL` */
        val name: String,
        /** Parametername großgeschrieben, Wert ohne Anführungszeichen */
        val params: List<Pair<String, String>> = emptyList(),
        /** Roher (escapter) Wert */
        val value: String,
    ) {
        fun param(key: String) = params.firstOrNull { it.first == key }?.second

        /** Alle TYPE-Werte, kleingeschrieben (`TYPE=work,voice;TYPE=pref` → work, voice, pref) */
        fun types(): List<String> = params.filter { it.first == "TYPE" }
            .flatMap { it.second.split(',') }.map { it.trim().trim('"').lowercase() }.filter { it.isNotEmpty() }

        fun serialize(out: StringBuilder) {
            val s = StringBuilder()
            group?.let { s.append(it).append('.') }
            s.append(name)
            for ((k, v) in params) {
                s.append(';').append(k).append('=')
                if (v.contains(':') || v.contains(';')) s.append('"').append(v.replace('"', '\'')).append('"') else s.append(v)
            }
            s.append(':').append(value)
            ICal.fold(s.toString(), out)
        }
    }

    class Card(val lines: MutableList<Line> = mutableListOf()) {
        fun get(name: String) = lines.firstOrNull { it.name == name }
        fun text(name: String) = get(name)?.let { unescape(it.value) }.orEmpty()
        val version: String get() = get("VERSION")?.value?.trim() ?: "3.0"
        val uid: String? get() = get("UID")?.value?.trim()?.takeIf { it.isNotEmpty() }

        fun serialize(): String {
            val out = StringBuilder()
            ICal.fold("BEGIN:VCARD", out)
            // VERSION muss direkt auf BEGIN folgen
            get("VERSION")?.serialize(out)
            lines.filter { it.name != "VERSION" }.forEach { it.serialize(out) }
            ICal.fold("END:VCARD", out)
            return out.toString()
        }

        fun set(name: String, value: String?) {
            val pos = lines.indexOfFirst { it.name == name }.let { if (it < 0) lines.size else it }
            val before = lines.take(pos).count { it.name == name }
            lines.removeAll { it.name == name }
            if (value != null) lines.add(pos - before, Line(name = name, value = value))
        }

        /** Eigene Bezeichnung einer gruppierten Eigenschaft (`itemN.X-ABLabel`) */
        fun labelOf(line: Line): String? {
            val g = line.group ?: return null
            return lines.firstOrNull { it.name == "X-ABLABEL" && it.group.equals(g, ignoreCase = true) }?.let { unescape(it.value) }
        }
    }

    // ---------- Escaping ----------

    fun unescape(s: String) = ICal.unescape(s)
    fun escape(s: String) = ICal.escape(s)

    /** Strukturierten Wert (N, ADR, ORG) an unescapten `;` teilen und entschlüsseln. */
    private fun components(value: String): List<String> {
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when (c) {
                '\\' -> {
                    cur.append(c)
                    if (i + 1 < value.length) cur.append(value[i + 1])
                    i++
                }
                ';' -> {
                    parts += unescape(cur.toString())
                    cur.clear()
                }
                else -> cur.append(c)
            }
            i++
        }
        parts += unescape(cur.toString())
        return parts
    }

    private fun joinComponents(parts: List<String>) = parts.joinToString(";") { escape(it.trim()) }

    // ---------- Lesen ----------

    private fun isQp(head: String) = head.uppercase().contains("QUOTED-PRINTABLE")

    /** Fortsetzungszeilen zusammenführen, auch Quoted-Printable-„soft line breaks“ aus vCard 2.1. */
    private fun unfold(text: String): List<String> {
        val raw = text.removePrefix("﻿").split('\n').map { it.removeSuffix("\r") }
        val lines = mutableListOf<String>()
        var i = 0
        while (i < raw.size) {
            val line = raw[i++]
            if ((line.startsWith(' ') || line.startsWith('\t')) && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last() + line.substring(1)
                continue
            }
            if (line.isBlank()) continue
            var cur = line
            val headIsQp = ':' in cur && isQp(cur.substringBefore(':'))
            while (headIsQp && cur.endsWith('=') && i < raw.size) cur = cur.dropLast(1) + raw[i++]
            lines += cur
        }
        return lines
    }

    private fun parseLine(line: String): Line? {
        val colon = ICal.valueColon(line).takeIf { it >= 0 } ?: return null
        val segs = ICal.splitUnquoted(line.substring(0, colon), ';')
        val fullName = segs.first().trim()
        val group = fullName.substringBeforeLast('.', "").takeIf { it.isNotEmpty() }
        val name = fullName.substringAfterLast('.')
        if (name.isEmpty()) return null
        val params = segs.drop(1).filter { it.isNotBlank() }.map { p ->
            if ('=' in p) {
                p.substringBefore('=').trim().uppercase() to p.substringAfter('=').trim().trim('"')
            } else {
                // vCard 2.1: Parameter ohne Namen, z. B. `TEL;CELL;PREF:` oder `;QUOTED-PRINTABLE`
                val v = p.trim().uppercase()
                (if (v in listOf("QUOTED-PRINTABLE", "BASE64", "8BIT", "7BIT", "B")) "ENCODING" else "TYPE") to v
            }
        }
        return Line(group, name.uppercase(), params, line.substring(colon + 1))
    }

    /** Liest alle vCards aus einem Text (eine .vcf-Datei darf beliebig viele enthalten). */
    fun parseCards(text: String): List<Card> {
        val cards = mutableListOf<Card>()
        var current: Card? = null
        var depth = 0 // AGENT-Karten in vCard 2.1 können verschachtelt sein
        for (raw in unfold(text)) {
            val line = parseLine(raw) ?: continue
            val isVcard = line.value.trim().equals("VCARD", ignoreCase = true)
            when {
                line.name == "BEGIN" && isVcard -> if (current == null) { current = Card(); depth = 0 } else depth++
                line.name == "END" && isVcard -> if (depth > 0) depth-- else current?.let { cards += it; current = null }
                else -> if (depth == 0) current?.lines?.add(line)
            }
        }
        return cards
    }

    private fun qpDecode(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            if (b[i] == '='.code.toByte() && i + 2 < b.size) {
                val v = String(b, i + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            out.write(b[i].toInt())
            i++
        }
        return out.toByteArray()
    }

    private fun decodeCharset(bytes: ByteArray, charset: String?): String {
        val c = charset?.lowercase()
        if (c != null && (c.contains("8859") || c.contains("1252") || c == "latin1")) return String(bytes, Charsets.ISO_8859_1)
        val decoder = Charsets.UTF_8.newDecoder()
        // Kein gültiges UTF-8: wahrscheinlich Latin-1 ohne CHARSET-Angabe
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    private val STRUCTURED = setOf("N", "ADR", "ORG")
    private val TEXT = setOf("FN", "NICKNAME", "TITLE", "ROLE", "NOTE", "LABEL", "CATEGORIES", "X-ABLABEL", "SORT-STRING")

    /** Entschlüsselt Quoted-Printable/Zeichensätze und gleicht das Escaping von vCard 2.1 an 3.0 an. */
    fun normalizeImport(card: Card): Card {
        val v21 = card.version.startsWith("2")
        val lines = card.lines.map { line ->
            // Die Datei ist bereits als Text gelesen; nur Quoted-Printable enthält noch Bytes im angegebenen Zeichensatz
            val qp = line.param("ENCODING")?.equals("QUOTED-PRINTABLE", ignoreCase = true) == true
            val charset = line.param("CHARSET")
            var value = if (qp) decodeCharset(qpDecode(line.value), charset) else line.value
            var params = line.params.filterNot { (k, v) ->
                k == "CHARSET" || (k == "ENCODING" && (v.equals("QUOTED-PRINTABLE", ignoreCase = true) || v.contains("BIT")))
            }.map { (k, v) -> if (k == "ENCODING" && v.equals("BASE64", ignoreCase = true)) k to "b" else k to v }
            if (params.any { it.first == "ENCODING" && it.second.equals("b", ignoreCase = true) }) {
                value = value.filterNot { it.isWhitespace() }
            }
            if (v21) {
                if (line.name in STRUCTURED) {
                    value = ICal.splitUnquoted(value, ';').joinToString(";") { escape(it.replace("\\;", ";")) }
                } else if (line.name in TEXT) {
                    value = escape(value.replace("\\;", ";").replace("\\n", "\n").replace("\\N", "\n"))
                }
                // TYPE-Werte zusammenfassen: TEL;TYPE=CELL;TYPE=PREF → TEL;TYPE=CELL,PREF
                val types = params.filter { it.first == "TYPE" }.map { it.second }
                if (types.size > 1) params = params.filterNot { it.first == "TYPE" } + ("TYPE" to types.joinToString(","))
            }
            line.copy(params = params, value = value)
        }
        return Card(lines.toMutableList())
    }

    private fun labelType(label: String): String {
        val inner = label.trim().removePrefix("_\$!<").removeSuffix(">!\$_")
        return when (inner.lowercase()) {
            "mobile", "iphone", "cell" -> "mobile"
            "home", "homepage" -> "home"
            "work" -> "work"
            "homefax", "workfax", "otherfax", "fax" -> "fax"
            "pager" -> "pager"
            "other" -> "other"
            else -> label.trim()
        }
    }

    private fun entryType(card: Card, line: Line): String {
        card.labelOf(line)?.let { return labelType(it) }
        val types = line.types()
        if (line.name == "TEL") {
            if ("cell" in types) return "mobile"
            if ("fax" in types) return "fax"
            if ("pager" in types) return "pager"
        }
        return when {
            "work" in types -> "work"
            "home" in types -> "home"
            else -> "other"
        }
    }

    private fun typedValue(card: Card, line: Line): TypedValue {
        var value = unescape(line.value.trim())
        if (line.name == "TEL") value = value.removePrefix("tel:")
        if (line.name == "EMAIL") value = value.removePrefix("mailto:")
        return TypedValue(entryType(card, line), value.trim())
    }

    private fun address(card: Card, line: Line): PostalAddress {
        val c = components(line.value)
        fun part(i: Int) = c.getOrNull(i)?.trim().orEmpty()
        return PostalAddress(entryType(card, line), part(0), part(1), part(2), part(3), part(4), part(5), part(6))
    }

    /** Geburtstag in `YYYY-MM-DD` oder `--MM-DD` umwandeln. */
    private fun normalizeBday(line: Line): String {
        val v = line.value.trim().substringBefore('T')
        val digits = v.filter { it.isDigit() }
        val omitYear = line.param("X-APPLE-OMIT-YEAR") != null
        if (v.startsWith("--") && digits.length == 4) return "--${digits.take(2)}-${digits.drop(2)}"
        if (digits.length == 8) {
            val (y, m, d) = Triple(digits.take(4), digits.substring(4, 6), digits.drop(6))
            return if (omitYear || y == "1604") "--$m-$d" else "$y-$m-$d"
        }
        return v
    }

    /** Kontaktdaten (ohne IDs) aus einer Karte lesen. */
    fun extract(card: Card): Contact {
        val n = card.get("N")?.let { components(it.value) }.orEmpty()
        fun nPart(i: Int) = n.getOrNull(i)?.trim().orEmpty()
        val org = card.get("ORG")?.let { components(it.value) }.orEmpty()
        fun all(name: String) = card.lines.filter { it.name == name }
        return Contact(
            uid = card.uid.orEmpty(),
            fullName = card.text("FN").trim(),
            familyName = nPart(0), givenName = nPart(1), additionalNames = nPart(2), prefix = nPart(3), suffix = nPart(4),
            nickname = card.text("NICKNAME").trim(),
            organization = org.getOrNull(0)?.trim().orEmpty(),
            department = org.getOrNull(1)?.trim().orEmpty(),
            title = card.text("TITLE").trim(),
            phones = all("TEL").map { typedValue(card, it) }.filter { it.value.isNotEmpty() },
            emails = all("EMAIL").map { typedValue(card, it) }.filter { it.value.isNotEmpty() },
            addresses = all("ADR").map { address(card, it) },
            urls = all("URL").map { typedValue(card, it) }.filter { it.value.isNotEmpty() },
            birthday = card.get("BDAY")?.let(::normalizeBday).orEmpty(),
            note = all("NOTE").joinToString("\n") { unescape(it.value) }.trimEnd(),
            photo = photo(card),
        )
    }

    /** Foto als data:-URL; Verweise auf andere Adressen werden nicht geladen. */
    fun photo(card: Card): String? {
        val line = card.get("PHOTO") ?: return null
        val v = line.value.trim()
        if (v.startsWith("data:")) return v
        val enc = line.param("ENCODING").orEmpty().lowercase()
        if (enc == "b" || enc == "base64") {
            val type = line.types().firstOrNull()?.removePrefix("image/") ?: "jpeg"
            return "data:image/$type;base64,${v.filterNot { it.isWhitespace() }}"
        }
        return null
    }

    // ---------- Schreiben ----------

    private val KNOWN_TEL = setOf("mobile", "home", "work", "fax", "pager", "other")
    private val KNOWN_OTHER = setOf("home", "work", "other")
    private val REV_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    private fun typeParams(name: String, kind: String): List<String> = when {
        name == "TEL" && kind == "mobile" -> listOf("CELL")
        name == "TEL" && kind == "fax" -> listOf("FAX")
        name == "TEL" && kind == "pager" -> listOf("PAGER")
        kind == "home" -> listOf("HOME")
        kind == "work" -> listOf("WORK")
        else -> emptyList()
    }

    private fun isKnown(name: String, kind: String) =
        kind.isEmpty() || if (name == "TEL") kind in KNOWN_TEL else kind in KNOWN_OTHER

    /** Neue Karte (vCard 3.0) aus einem Kontakt; [photo] ist eine data:-URL. */
    fun create(c: Contact, photo: String?, now: Instant = Instant.now()): Card {
        val lines = mutableListOf(
            Line(name = "VERSION", value = "3.0"),
            Line(name = "PRODID", value = "-//Synchaven//Synchaven Android//DE"),
            Line(name = "UID", value = c.uid),
            Line(name = "FN", value = escape(c.displayName())),
            Line(name = "N", value = joinComponents(listOf(c.familyName, c.givenName, c.additionalNames, c.prefix, c.suffix))),
        )
        fun opt(name: String, v: String) {
            if (v.isNotBlank()) lines += Line(name = name, value = escape(v.trim()))
        }
        opt("NICKNAME", c.nickname)
        if (c.department.isNotBlank()) lines += Line(name = "ORG", value = joinComponents(listOf(c.organization, c.department)))
        else opt("ORG", c.organization)
        opt("TITLE", c.title)
        val b = c.birthday.trim()
        when {
            b.startsWith("--") -> lines += Line(
                // vCard 3.0 kennt kein Datum ohne Jahr: Apple-Konvention, die auch DAVx⁵ versteht
                name = "BDAY", params = listOf("X-APPLE-OMIT-YEAR" to "1604"), value = "1604-${b.removePrefix("--")}",
            )
            b.isNotEmpty() -> lines += Line(name = "BDAY", value = b)
        }
        val labels = mutableListOf<Line>()
        var nextGroup = 1
        fun multi(name: String, kind: String, value: String) {
            if (isKnown(name, kind)) {
                val types = typeParams(name, kind)
                lines += Line(name = name, params = if (types.isEmpty()) emptyList() else listOf("TYPE" to types.joinToString(",")), value = value)
            } else {
                // Eigene Bezeichnung wie bei Apple/DAVx⁵: itemN.TEL + itemN.X-ABLabel
                val g = "item${nextGroup++}"
                lines += Line(group = g, name = name, value = value)
                labels += Line(group = g, name = "X-ABLABEL", value = escape(kind))
            }
        }
        c.phones.forEach { multi("TEL", it.type, escape(it.value)) }
        c.emails.forEach { multi("EMAIL", it.type, escape(it.value)) }
        c.addresses.forEach { a ->
            multi("ADR", a.type, joinComponents(listOf(a.poBox, a.extended, a.street, a.locality, a.region, a.postalCode, a.country)))
        }
        c.urls.forEach { multi("URL", it.type, it.value) }
        lines += labels
        if (c.note.isNotBlank()) lines += Line(name = "NOTE", value = escape(c.note))
        photo?.let(::photoLine)?.let { lines += it }
        lines += Line(name = "REV", value = REV_FMT.format(now))
        return Card(lines)
    }

    private fun photoLine(dataUrl: String): Line? {
        val rest = dataUrl.trim().removePrefix("data:").takeIf { it.length < dataUrl.trim().length } ?: return null
        val head = rest.substringBefore(',', "")
        val data = rest.substringAfter(',', "").filterNot { it.isWhitespace() }
        val mime = head.removeSuffix(";base64").takeIf { it != head } ?: return null
        val type = mime.trim().removePrefix("image/").uppercase()
        if (type.isEmpty() || data.isEmpty()) return null
        return Line(name = "PHOTO", params = listOf("ENCODING" to "b", "TYPE" to type), value = data)
    }
}
