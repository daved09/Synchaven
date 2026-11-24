package de.dave.synchaven.util

import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.TypedValue
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsTest {
    @Test
    fun birthdayParsing() {
        assertEquals("1990-05-12", birthdayFromInput("12.5.1990"))
        assertEquals("--05-12", birthdayFromInput("12.05."))
        assertEquals("1990-05-12", birthdayFromInput("1990-05-12"))
        assertEquals("", birthdayFromInput("  "))
        assertNull(birthdayFromInput("31.02.2020"))
        assertEquals("12.05.1990", birthdayToInput("1990-05-12"))
        assertEquals("12.05.", birthdayToInput("--05-12"))
        assertEquals(
            UiText.Plural(R.plurals.birthday_age, 36, listOf("12. Mai 1990", 36)),
            formatBirthday("1990-05-12", LocalDate.of(2026, 11, 22)),
        )
        assertEquals(UiText.Plain("15. März"), formatBirthday("--03-15"))
    }

    @Test
    fun namesAndGrouping() {
        val c = Contact(id = "1", givenName = "Ärzte", familyName = "Team")
        assertEquals("Ärzte Team", c.displayName())
        assertEquals("ÄT", c.initials())
        assertEquals("A", c.groupLetter())
        assertEquals("#", Contact(id = "2", fullName = "123").groupLetter())
        assertEquals(UiText.Res(R.string.contact_no_name), Contact(id = "3").displayNameText())
        val nick = Contact(id = "4", givenName = "Johannes", familyName = "Müller", fullName = "Johannes Müller", nickname = "Hannes")
        assertEquals("Hannes", nick.displayName())
        assertEquals("Johannes Müller", nick.realName())
        assertEquals("Hannes", Contact(id = "6", fullName = "Hannes", nickname = "Hannes").realName())
        assertEquals("ACME", Contact(id = "7", fullName = "Hannes", nickname = "Hannes", organization = "ACME").realName())
        assertEquals("JM", nick.initials())
        assertEquals("J", nick.groupLetter())
        assertEquals(UiText.Plain("Johannes Müller"), nick.realNameText())
        assertEquals("Hannes", Contact(id = "5", fullName = "Alt", nickname = "Hannes").displayName())
        assertEquals(UiText.Res(R.string.type_work), typeLabel("work"))
        assertEquals(UiText.Plain("Ferienhaus"), typeLabel("Ferienhaus"))
    }

    @Test
    fun searchMatchesDigitsIgnoringFormatting() {
        val c = Contact(id = "1", fullName = "Erika", phones = listOf(TypedValue("mobile", "+49 171 1234567")))
        assertTrue(c.matches("1711234"))
        assertTrue(c.matches("erika"))
        assertFalse(c.matches("anna"))
    }
}
