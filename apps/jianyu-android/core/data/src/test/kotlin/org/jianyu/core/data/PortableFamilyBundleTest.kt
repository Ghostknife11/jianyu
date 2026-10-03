package org.jianyu.core.data

import org.jianyu.core.model.Child
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PortableFamilyBundleTest {
    private val codec = PortableFamilyBundleCodec()

    @Test
    fun `portable bundle round trips on another codec instance`() {
        val exported = codec.create(state())
        val restored = PortableFamilyBundleCodec().open(exported.bytes, exported.recoveryCode)

        assertEquals(state(), restored)
        assertEquals(32, Base64.getUrlDecoder().decode(exported.recoveryCode).size)
    }

    @Test
    fun `bundle contains no family plaintext or semantic filename`() {
        val exported = codec.create(state())
        val envelope = exported.bytes.decodeToString()

        assertFalse(envelope.contains("Synthetic Child"))
        assertFalse(envelope.contains("private-interest-racing"))
        assertFalse(envelope.contains("family.json"))
        assertTrue(envelope.contains("org.foe.portable-family-bundle/v1"))
    }

    @Test
    fun `same state encrypts differently every time`() {
        val first = codec.create(state())
        val second = codec.create(state())

        assertNotEquals(first.bytes.decodeToString(), second.bytes.decodeToString())
        assertNotEquals(first.recoveryCode, second.recoveryCode)
    }

    @Test
    fun `wrong recovery code and tampering fail closed`() {
        val exported = codec.create(state())
        val otherCode = codec.create(state()).recoveryCode
        val wrongKey = runCatching { codec.open(exported.bytes, otherCode) }.exceptionOrNull()
        assertTrue(wrongKey is IllegalArgumentException)

        val text = exported.bytes.decodeToString()
        val marker = "\"ciphertext\":\""
        val index = text.indexOf(marker) + marker.length
        val replacement = if (text[index] == 'A') 'B' else 'A'
        val tampered = (text.substring(0, index) + replacement + text.substring(index + 1)).encodeToByteArray()
        val tamperFailure = runCatching { codec.open(tampered, exported.recoveryCode) }.exceptionOrNull()
        assertTrue(tamperFailure is IllegalArgumentException)
    }

    private fun state() = FamilyState(
        household = Household("household-opaque", "Synthetic Family", "2026-09-13T00:00:00Z"),
        members = listOf(FamilyMember("member-child", "Synthetic Child", MemberRole.CHILD, "child-1", "2026-09-13T00:00:00Z")),
        children = listOf(Child("child-1", "member-child", "Synthetic Child", 2015, "2026-09-13T00:00:00Z")),
        evidence = listOf(
            Evidence(
                id = "evidence-1",
                childId = "child-1",
                authorId = "member-child",
                stream = ContextStream.CHILD,
                kind = EvidenceKind.CHILD_STATED,
                expression = "private-interest-racing",
                occurredAt = "2026-09-13T00:00:00Z",
                recordedAt = "2026-09-13T00:00:00Z",
                ownerId = "member-child",
                visibility = EvidenceVisibility.CHILD_PRIVATE,
            ),
        ),
        events = emptyList(),
        choices = emptyList(),
    )
}
