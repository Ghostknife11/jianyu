package org.jianyu.core.domain

import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EvidenceDeletionTest {
    @Test
    fun `deletion removes projection without copying plaintext into marker or audit event`() {
        val secret = "孩子不想被保留的具体原文"
        val evidence = Evidence(
            id = "evidence-1",
            childId = "child-1",
            authorId = "caregiver-1",
            stream = ContextStream.CHILD,
            kind = EvidenceKind.DIRECT_OBSERVATION,
            expression = secret,
            occurredAt = "2026-09-14T00:00:00Z",
            recordedAt = "2026-09-14T00:00:00Z",
            ownerId = "child-member-1",
            visibility = EvidenceVisibility.SHARED_WITH_CHILD,
        )
        val state = FamilyState(
            household = Household("household", "合成家庭", "2026-01-01T00:00:00Z"),
            members = emptyList(),
            children = emptyList(),
            evidence = listOf(evidence),
        )

        val deleted = deleteEvidenceWithTombstone(
            state = state,
            evidenceId = evidence.id,
            authorId = "caregiver-1",
            actorRole = MemberRole.CAREGIVER,
            deviceId = "device-local",
            deletedAt = "2026-09-14T01:00:00Z",
            tombstoneId = "tombstone-1",
            eventId = "event-1",
        )

        assertEquals(0, deleted.evidence.size)
        assertEquals("evidence-1", deleted.tombstones.single().targetId)
        assertEquals("evidence.deleted", deleted.events.single().eventType)
        assertFalse(deleted.tombstones.single().toString().contains(secret))
        assertFalse(deleted.events.single().payload.values.any { it.contains(secret) })
    }
}
