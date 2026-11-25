package de.dave.synchaven.data

import de.dave.synchaven.data.api.isCleartextUrl
import de.dave.synchaven.data.api.normalizeServerUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {
    @Test
    fun normalizes() {
        // Without a scheme always encrypted – no silent fallback to http
        assertEquals("https://homeserver.local:8080", normalizeServerUrl(" homeserver.local:8080/ "))
        assertEquals("http://192.168.1.5:8080", normalizeServerUrl("http://192.168.1.5:8080"))
        assertEquals("https://example.org/synchaven", normalizeServerUrl("https://example.org/synchaven/api/"))
        assertEquals("HTTPS://example.org", normalizeServerUrl("HTTPS://example.org"))
    }

    @Test
    fun detectsCleartext() {
        assertTrue(isCleartextUrl("http://192.168.1.5:8080"))
        assertTrue(isCleartextUrl(" HTTP://nas.local"))
        assertFalse(isCleartextUrl("https://example.org"))
        assertFalse(isCleartextUrl(normalizeServerUrl("example.org")))
    }
}
