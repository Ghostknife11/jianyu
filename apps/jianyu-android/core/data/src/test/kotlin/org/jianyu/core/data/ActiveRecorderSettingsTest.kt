package org.jianyu.core.data

import org.junit.Assert.fail
import org.junit.Test

class ActiveRecorderSettingsTest {
    @Test
    fun acceptsOpaqueMemberId() {
        ActiveRecorderSettings("0a702baa-45fd-41dd-8e27-9ec7a47e8211").validate()
    }

    @Test
    fun rejectsBlankOrControlCharacters() {
        assertInvalid { ActiveRecorderSettings("").validate() }
        assertInvalid { ActiveRecorderSettings("member\nother").validate() }
    }

    @Test
    fun rejectsOversizedMemberId() {
        assertInvalid { ActiveRecorderSettings("a".repeat(129)).validate() }
    }

    private fun assertInvalid(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid active recorder settings to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
