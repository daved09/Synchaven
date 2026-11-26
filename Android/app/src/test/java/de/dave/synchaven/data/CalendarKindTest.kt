package de.dave.synchaven.data

import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarKindTest {
    private fun decode(kind: String) = AppJson.decodeFromString<Calendar>("""{"id":"c","name":"C","kind":"$kind"}""")

    @Test
    fun knownKinds() {
        assertEquals(CalendarKind.DEFAULT, decode("default").kind)
        assertTrue(decode("birthdays").isBirthdays)
        val sub = decode("subscription")
        assertEquals(CalendarKind.SUBSCRIPTION, sub.kind)
        assertTrue(sub.isReadOnly)
        assertFalse(sub.isBirthdays)
        assertFalse(decode("default").isReadOnly)
    }

    @Test
    fun unknownKindFromANewerServerBecomesDefault() {
        assertEquals(CalendarKind.DEFAULT, decode("something-new").kind)
        assertEquals(CalendarKind.DEFAULT, AppJson.decodeFromString<Calendar>("""{"id":"c","name":"C"}""").kind)
    }

    @Test
    fun encodesAsTheApiString() {
        assertTrue(AppJson.encodeToString(Calendar.serializer(), Calendar("c", "C", kind = CalendarKind.SUBSCRIPTION))
            .contains(""""kind":"subscription""""))
    }
}
