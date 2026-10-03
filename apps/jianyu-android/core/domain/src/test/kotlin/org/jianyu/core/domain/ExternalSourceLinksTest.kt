package org.jianyu.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalSourceLinksTest {
    @Test
    fun `shows the actual HTTPS destination without treating a link as trusted`() {
        assertEquals("example.test", externalSourceHost("https://example.test/events?ref=public"))
        assertEquals("example.test", externalSourceHost("HTTPS://EXAMPLE.TEST/events"))
    }

    @Test
    fun `rejects unsafe or misleading external destinations`() {
        listOf(
            "http://example.test/event",
            "javascript:alert(1)",
            "https://name:secret@example.test/event",
            "https://example.test/event#private-fragment",
            "https://example.test:99999/event",
            "https://example.test /event",
            " https://example.test/event",
        ).forEach { assertNull(it, externalSourceHost(it)) }
    }
}
