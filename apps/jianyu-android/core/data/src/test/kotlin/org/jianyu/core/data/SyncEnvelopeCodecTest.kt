package org.jianyu.core.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.SyncFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncEnvelopeCodecTest {
    private val frameCodec = SyncFrameCodec()
    private val envelopeCodec = SyncEnvelopeCodec(frameCodec)
    private val key = HouseholdSyncKey.fromBytes("opaque_sync_key_0001", ByteArray(32) { (it + 1).toByte() })

    @Test
    fun `encrypted envelope round trips a verified frame without semantic metadata`() {
        val frame = frame()
        val encrypted = envelopeCodec.seal(frame, key)
        val visible = encrypted.decodeToString()

        assertEquals(frame, envelopeCodec.open(encrypted, key))
        assertTrue(visible.contains("org.foe.encrypted-sync-envelope/v1"))
        assertTrue(visible.contains("opaque_sync_key_0001"))
        assertFalse(visible.contains("household-private"))
        assertFalse(visible.contains("device-private"))
        assertFalse(visible.contains("frame-private"))
        assertFalse(visible.contains("孩子最近喜欢赛车"))
        assertFalse(visible.contains(frame.contentHash))
        assertFalse(key.toString().contains("AQID"))
    }

    @Test
    fun `same frame seals differently and both copies open`() {
        val frame = frame()
        val first = envelopeCodec.seal(frame, key)
        val second = envelopeCodec.seal(frame, key)

        assertNotEquals(first.decodeToString(), second.decodeToString())
        assertEquals(frame, envelopeCodec.open(first, key))
        assertEquals(frame, envelopeCodec.open(second, key))
    }

    @Test
    fun `wrong key modified ciphertext and unknown fields fail closed`() {
        val encrypted = envelopeCodec.seal(frame(), key)
        val wrongMaterial = HouseholdSyncKey.fromBytes("opaque_sync_key_0001", ByteArray(32) { 7 })
        assertFails { envelopeCodec.open(encrypted, wrongMaterial) }

        val text = encrypted.decodeToString()
        val marker = "\"ciphertext\":\""
        val index = text.indexOf(marker) + marker.length
        val replacement = if (text[index] == 'A') 'B' else 'A'
        val tampered = (text.substring(0, index) + replacement + text.substring(index + 1)).encodeToByteArray()
        assertFails { envelopeCodec.open(tampered, key) }

        val withUnknownField = text.replaceFirst("{", "{\"surprise\":true,").encodeToByteArray()
        assertFails { envelopeCodec.open(withUnknownField, key) }

        val otherId = HouseholdSyncKey.fromBytes("opaque_sync_key_0002", ByteArray(32) { (it + 1).toByte() })
        assertFails { envelopeCodec.open(encrypted, otherId) }
    }

    @Test
    fun `family sync engine only sees plaintext after authenticated opening`() {
        val encrypted = envelopeCodec.seal(frame(), key)
        val result = FamilySyncEngine(frameCodec, envelopeCodec = envelopeCodec)
            .applyEncrypted(state(), encrypted, key)

        assertEquals(SyncApplyStatus.APPLIED, result.status)
        assertEquals(listOf("evidence-private"), result.state.evidence.map(Evidence::id))
        assertEquals(1L, result.state.syncState.cursors.single().lastSequence)
    }

    @Test
    fun `web crypto fixture opens in the Android codec`() {
        val fixtureText = requireNotNull(javaClass.classLoader?.getResourceAsStream("interop/encrypted-sync-envelope-v1.json"))
            .bufferedReader()
            .use { it.readText() }
        val fixture = Json.parseToJsonElement(fixtureText).jsonObject
        assertEquals("bytes-1-through-32", fixture.getValue("testVector").jsonPrimitive.content)
        val fixtureKey = HouseholdSyncKey.fromBytes(
            fixture.getValue("keyId").jsonPrimitive.content,
            ByteArray(32) { (it + 1).toByte() },
        )
        val envelope = fixture.getValue("envelope").toString().encodeToByteArray()
        val expected = Json.decodeFromString<SyncFrame>(fixture.getValue("frame").toString())

        assertEquals(expected, envelopeCodec.open(envelope, fixtureKey))
    }

    private fun frame() = frameCodec.create(
        state = state(
            evidence = listOf(
                Evidence(
                    id = "evidence-private",
                    childId = "child-private",
                    authorId = "member-private",
                    stream = ContextStream.CHILD,
                    kind = EvidenceKind.CHILD_STATED,
                    expression = "孩子最近喜欢赛车",
                    occurredAt = "2026-09-14T00:00:00Z",
                    recordedAt = "2026-09-14T00:00:00Z",
                    ownerId = "member-private",
                    visibility = EvidenceVisibility.GUARDIANS,
                ),
            ),
        ),
        deviceId = "device-private",
        sequence = 1,
        previousFrameHash = null,
        createdAt = Instant.parse("2026-09-14T00:00:00Z"),
        frameId = "frame-private",
    )

    private fun state(evidence: List<Evidence> = emptyList()) = FamilyState(
        household = Household("household-private", "合成家庭", "2026-09-14T00:00:00Z"),
        members = emptyList(),
        children = emptyList(),
        evidence = evidence,
    )

    private fun assertFails(block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue("Expected a fail-closed envelope error", failure is IllegalArgumentException)
    }
}
