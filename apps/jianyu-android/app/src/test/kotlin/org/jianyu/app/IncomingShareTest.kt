package org.jianyu.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingShareTest {
    @Test
    fun `shared subject and text become one reviewable draft`() {
        assertEquals(
            "赛车为什么能高速过弯\nhttps://example.test/video",
            buildIncomingShareDraft(" 赛车为什么能高速过弯 ", " https://example.test/video "),
        )
    }

    @Test
    fun `duplicate and blank share parts do not create noise`() {
        assertEquals("同一段内容", buildIncomingShareDraft("同一段内容", "同一段内容"))
        assertNull(buildIncomingShareDraft("  ", null))
    }

    @Test
    fun `incoming shares are bounded before reaching compose state`() {
        val draft = buildIncomingShareDraft(null, "a".repeat(2_000))
        assertTrue(requireNotNull(draft).length <= 800)
    }
}
