package org.jianyu.core.data

import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.DeletionTombstone
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.TombstoneTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FamilySyncEngineTest {
    private val codec = SyncFrameCodec()
    private val engine = FamilySyncEngine(codec)
    private val household = Household("household", "合成家庭", "2026-01-01T00:00:00Z")

    @Test
    fun `codec round trips canonical frame and detects content tampering`() {
        val frame = codec.create(
            state(evidence = listOf(evidence("e-1", "赛车"))),
            deviceId = "device-a",
            sequence = 1,
            previousFrameHash = null,
            createdAt = Instant.parse("2026-09-14T00:00:00Z"),
            frameId = "frame-1",
        )
        val encoded = codec.encode(frame)

        assertEquals(frame, codec.decodeAndVerify(encoded))
        val tampered = encoded.decodeToString().replace("赛车", "天文").encodeToByteArray()
        assertFails { codec.decodeAndVerify(tampered) }
    }

    @Test
    fun `device chain accepts next frame and exact retry is idempotent`() {
        val firstFrame = codec.create(
            state(evidence = listOf(evidence("e-1", "赛车"))),
            deviceId = "device-a",
            sequence = 1,
            previousFrameHash = null,
            createdAt = Instant.parse("2026-09-14T00:00:00Z"),
            frameId = "frame-1",
        )
        val first = engine.apply(state(), codec.encode(firstFrame))
        val retry = engine.apply(first.state, codec.encode(firstFrame))
        val secondFrame = codec.create(
            state(evidence = listOf(evidence("e-1", "赛车"), evidence("e-2", "机械"))),
            deviceId = "device-a",
            sequence = 2,
            previousFrameHash = firstFrame.contentHash,
            createdAt = Instant.parse("2026-09-14T01:00:00Z"),
            frameId = "frame-2",
        )
        val second = engine.apply(first.state, codec.encode(secondFrame))

        assertEquals(SyncApplyStatus.APPLIED, first.status)
        assertEquals(SyncApplyStatus.IDEMPOTENT, retry.status)
        assertEquals(first.state, retry.state)
        assertEquals(setOf("e-1", "e-2"), second.state.evidence.map { it.id }.toSet())
        assertEquals(2L, second.state.syncState.cursors.single().lastSequence)
    }

    @Test
    fun `sequence gap and retired device fail closed`() {
        val gap = codec.create(
            state(), "device-a", 2, "0".repeat(64),
            Instant.parse("2026-09-14T00:00:00Z"), "gap",
        )
        assertFails { engine.apply(state(), codec.encode(gap)) }

        val retired = state().copy(syncState = state().syncState.copy(retiredDeviceIds = listOf("device-a")))
        val first = codec.create(
            state(), "device-a", 1, null,
            Instant.parse("2026-09-14T00:00:00Z"), "retired",
        )
        assertFails { engine.apply(retired, codec.encode(first)) }
    }

    @Test
    fun `tombstone remains dominant when stale content arrives`() {
        val target = evidence("deleted", "这条内容不能复活")
        val local = state(tombstones = listOf(
            DeletionTombstone(
                tombstoneId = "delete-evidence",
                householdId = household.id,
                targetType = TombstoneTarget.EVIDENCE,
                targetId = target.id,
                subjectId = target.childId,
                authorId = "member",
                deviceId = "device-local",
                deletedAt = "2026-09-14T01:00:00Z",
            ),
        ))
        val stale = codec.create(
            state(evidence = listOf(target)), "device-stale", 1, null,
            Instant.parse("2026-09-14T00:00:00Z"), "stale-frame",
        )

        val result = engine.apply(local, codec.encode(stale))

        assertTrue(result.state.evidence.isEmpty())
        assertEquals(1, result.removedByTombstoneCount)
    }

    private fun state(
        evidence: List<Evidence> = emptyList(),
        tombstones: List<DeletionTombstone> = emptyList(),
    ) = FamilyState(
        household = household,
        members = emptyList(),
        children = emptyList(),
        evidence = evidence,
        tombstones = tombstones,
    )

    private fun evidence(id: String, expression: String) = Evidence(
        id = id,
        childId = "child",
        authorId = "member",
        stream = ContextStream.CHILD,
        kind = EvidenceKind.DIRECT_OBSERVATION,
        expression = expression,
        occurredAt = "2026-09-14T00:00:00Z",
        recordedAt = "2026-09-14T00:00:00Z",
        ownerId = "child-member",
        visibility = EvidenceVisibility.GUARDIANS,
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected validation failure", failed)
    }
}
