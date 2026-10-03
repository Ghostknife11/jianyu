package org.jianyu.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncProviderContractTest {
    @Test
    fun `generated object IDs are canonical and independent`() {
        val first = OpaqueSyncObjectId.generate()
        val second = OpaqueSyncObjectId.generate()
        assertEquals(24, first.value.length)
        assertEquals(first, OpaqueSyncObjectId.parse(first.value))
        assertNotEquals(first, second)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `path traversal cannot become an opaque object ID`() {
        OpaqueSyncObjectId.parse("../../household.json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `uuid-shaped semantic identifiers are not canonical object IDs`() {
        OpaqueSyncObjectId.parse("550e8400-e29b-41d4-a716-446655440000")
    }
}
