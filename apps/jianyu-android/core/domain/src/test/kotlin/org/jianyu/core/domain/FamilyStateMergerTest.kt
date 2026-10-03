package org.jianyu.core.domain

import org.jianyu.core.model.Child
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.DeletionTombstone
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.SyncFrameBody
import org.jianyu.core.model.TombstoneTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyStateMergerTest {
    private val household = Household("household", "合成家庭", "2026-01-01T00:00:00Z")

    @Test
    fun `concurrent evidence with distinct ids coexists idempotently`() {
        val local = state(evidence = listOf(evidence("local", "本机观察")))
        val incoming = frame(evidence = listOf(evidence("remote", "远端观察")))
        val merger = FamilyStateMerger()

        val first = merger.merge(local, incoming)
        val second = merger.merge(first.state, incoming)

        assertEquals(setOf("local", "remote"), first.state.evidence.map { it.id }.toSet())
        assertEquals(first.state, second.state)
        assertEquals(0, second.addedObjectCount)
    }

    @Test
    fun `tombstone dominates content regardless of arrival order`() {
        val target = evidence("deleted", "不应复活的内容")
        val tombstone = tombstone(TombstoneTarget.EVIDENCE, target.id)
        val result = FamilyStateMerger().merge(
            state(evidence = listOf(target), tombstones = listOf(tombstone)),
            frame(evidence = listOf(target)),
        )

        assertTrue(result.state.evidence.isEmpty())
        assertEquals(listOf(tombstone), result.state.tombstones)
        assertEquals(1, result.removedByTombstoneCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `same evidence id with different content fails closed`() {
        FamilyStateMerger().merge(
            state(evidence = listOf(evidence("same", "版本 A"))),
            frame(evidence = listOf(evidence("same", "版本 B"))),
        )
    }

    @Test
    fun `subject tombstone removes subject projections but preserves other children`() {
        val first = Child("child-1", "member-1", "合成甲", 2015, "2026-01-01T00:00:00Z")
        val second = Child("child-2", "member-2", "合成乙", 2016, "2026-01-01T00:00:00Z")
        val result = FamilyStateMerger().merge(
            state(
                children = listOf(first, second),
                evidence = listOf(evidence("first-evidence", "甲的记录", first.id), evidence("second-evidence", "乙的记录", second.id)),
                tombstones = listOf(tombstone(TombstoneTarget.SUBJECT, first.id)),
            ),
            frame(),
        )

        assertEquals(listOf(second), result.state.children)
        assertFalse(result.state.evidence.any { it.childId == first.id })
        assertTrue(result.state.evidence.any { it.childId == second.id })
    }

    private fun state(
        children: List<Child> = emptyList(),
        evidence: List<Evidence> = emptyList(),
        tombstones: List<DeletionTombstone> = emptyList(),
    ) = FamilyState(household = household, members = emptyList(), children = children, evidence = evidence, tombstones = tombstones)

    private fun frame(evidence: List<Evidence> = emptyList()) = SyncFrameBody(
        frameId = "frame",
        household = household,
        deviceId = "remote-device",
        sequence = 1,
        createdAt = "2026-09-14T00:00:00Z",
        evidence = evidence,
    )

    private fun evidence(id: String, expression: String, childId: String = "child-1") = Evidence(
        id = id,
        childId = childId,
        authorId = "author",
        stream = ContextStream.CHILD,
        kind = EvidenceKind.DIRECT_OBSERVATION,
        expression = expression,
        occurredAt = "2026-09-14T00:00:00Z",
        recordedAt = "2026-09-14T00:00:00Z",
        ownerId = childId,
        visibility = EvidenceVisibility.GUARDIANS,
    )

    private fun tombstone(type: TombstoneTarget, targetId: String) = DeletionTombstone(
        tombstoneId = "delete-$targetId",
        householdId = household.id,
        targetType = type,
        targetId = targetId,
        subjectId = targetId.takeIf { type == TombstoneTarget.SUBJECT },
        authorId = "author",
        deviceId = "local-device",
        deletedAt = "2026-09-14T01:00:00Z",
    )
}
