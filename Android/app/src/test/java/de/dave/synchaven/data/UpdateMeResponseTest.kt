package de.dave.synchaven.data

import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.model.UpdateMeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateMeResponseTest {
    private val user = """"id":"u1","email":"a@b.de","name":"A","default_view":"week""""

    @Test
    fun withoutPasswordChange() {
        val r = AppJson.decodeFromString<UpdateMeResponse>("{$user}")
        assertEquals("u1", r.user.id)
        assertEquals("week", r.user.defaultView)
        assertNull(r.token)
    }

    @Test
    fun afterPasswordChangeCarriesNewToken() {
        val r = AppJson.decodeFromString<UpdateMeResponse>("""{$user,"token":"t2","expires_at":"2026-12-25T00:00:00Z"}""")
        assertEquals("a@b.de", r.user.email)
        assertEquals("t2", r.token)
        assertEquals("2026-12-25T00:00:00Z", r.expiresAt)
    }
}
