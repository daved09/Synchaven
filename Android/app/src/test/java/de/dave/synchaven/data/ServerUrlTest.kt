package de.dave.synchaven.data

import de.dave.synchaven.data.api.normalizeServerUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUrlTest {
    @Test
    fun normalizes() {
        assertEquals("http://homeserver.local:8080", normalizeServerUrl(" homeserver.local:8080/ "))
        assertEquals("https://example.org/synchaven", normalizeServerUrl("https://example.org/synchaven/api/"))
        assertEquals("HTTPS://example.org", normalizeServerUrl("HTTPS://example.org"))
    }
}
