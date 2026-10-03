package org.jianyu.core.domain

import org.jianyu.core.model.Child
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.SyncFrameBody
import org.jianyu.core.model.TombstoneTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GraduationRetentionTest {
    private val now = Instant.parse("2026-09-15T08:00:00Z")

    @Test
    fun `relationship-only keeps subject identity but clears all history and blocks stale resurrection`() {
        val cleared = applyGraduationRetention(
            state(), "graduate", GraduationRetentionMode.RELATIONSHIP_ONLY,
            authorization("graduate"), now, "tombstone-content", "device-local", 2026,
        )

        assertTrue(cleared.children.any { it.id == "graduate" })
        assertTrue(cleared.members.any { it.subjectId == "graduate" })
        assertFalse(cleared.evidence.any { it.childId == "graduate" })
        assertFalse(cleared.events.any { it.subjectId == "graduate" })
        assertTrue(cleared.evidence.any { it.childId == "sibling" })
        assertEquals(TombstoneTarget.SUBJECT_CONTENT, cleared.tombstones.single().targetType)

        val merged = FamilyStateMerger().merge(cleared, frame(state())).state
        assertTrue(merged.children.any { it.id == "graduate" })
        assertFalse(merged.evidence.any { it.childId == "graduate" })
        assertFalse(merged.events.any { it.subjectId == "graduate" })
    }

    @Test
    fun `complete erasure removes subject link and blocks stale resurrection`() {
        val erased = applyGraduationRetention(
            state(), "graduate", GraduationRetentionMode.ERASE_SUBJECT,
            authorization("graduate"), now, "tombstone-subject", "device-local", 2026,
        )

        assertFalse(erased.children.any { it.id == "graduate" })
        assertFalse(erased.members.any { it.subjectId == "graduate" })
        assertTrue(erased.children.any { it.id == "sibling" })
        assertEquals(TombstoneTarget.SUBJECT, erased.tombstones.single().targetType)

        val merged = FamilyStateMerger().merge(erased, frame(state())).state
        assertFalse(merged.children.any { it.id == "graduate" })
        assertFalse(merged.members.any { it.subjectId == "graduate" })
        assertFalse(merged.evidence.any { it.childId == "graduate" })
    }

    @Test
    fun `underage mismatched or stale authorization fails closed`() {
        assertFails {
            applyGraduationRetention(
                state(), "sibling", GraduationRetentionMode.ERASE_SUBJECT,
                authorization("sibling"), now, "t-1", "device-local", 2026,
            )
        }
        assertFails {
            applyGraduationRetention(
                state(), "graduate", GraduationRetentionMode.ERASE_SUBJECT,
                authorization("sibling"), now, "t-2", "device-local", 2026,
            )
        }
        assertFails {
            applyGraduationRetention(
                state(), "graduate", GraduationRetentionMode.ERASE_SUBJECT,
                GraduationRetentionAuthorization("graduate", "2026-09-15T07:00:00Z"), now,
                "t-3", "device-local", 2026,
            )
        }
    }

    @Test
    fun `subject-content semantics cannot be smuggled into a v1 tombstone`() {
        val valid = applyGraduationRetention(
            state(), "graduate", GraduationRetentionMode.RELATIONSHIP_ONLY,
            authorization("graduate"), now, "tombstone-content", "device-local", 2026,
        )
        val invalid = valid.copy(
            tombstones = valid.tombstones.map { it.copy(schema = "org.foe.deletion-tombstone/v1") },
        )

        assertFails { FamilyStateMerger().merge(invalid, frame(invalid)) }
    }

    private fun authorization(subjectId: String) = GraduationRetentionAuthorization(subjectId, now.toString())

    private fun state() = FamilyState(
        household = Household("household", "家庭", "2020-01-01T00:00:00Z"),
        members = listOf(
            FamilyMember("member-graduate", "本人", MemberRole.CHILD, "graduate", "2020-01-01T00:00:00Z"),
            FamilyMember("member-sibling", "手足", MemberRole.CHILD, "sibling", "2020-01-01T00:00:00Z"),
            FamilyMember("caregiver", "家长", MemberRole.CAREGIVER, createdAt = "2020-01-01T00:00:00Z"),
        ),
        children = listOf(
            Child("graduate", "member-graduate", "本人", 2010, "2020-01-01T00:00:00Z"),
            Child("sibling", "member-sibling", "手足", 2015, "2020-01-01T00:00:00Z"),
        ),
        evidence = listOf(evidence("mine", "graduate"), evidence("theirs", "sibling")),
        events = listOf(event("mine-event", "graduate"), event("their-event", "sibling")),
    )

    private fun evidence(id: String, childId: String) = Evidence(
        id = id,
        childId = childId,
        authorId = "caregiver",
        stream = ContextStream.CHILD,
        kind = EvidenceKind.DIRECT_OBSERVATION,
        expression = "内容-$id",
        occurredAt = "2026-01-01T00:00:00Z",
        recordedAt = "2026-01-01T00:00:00Z",
        confidence = 1.0,
        ownerId = "caregiver",
        visibility = EvidenceVisibility.GUARDIANS,
    )

    private fun event(id: String, subjectId: String) = FamilyEvent(
        eventId = id, eventType = "test", householdId = "household", authorId = "caregiver",
        actorRole = MemberRole.CAREGIVER, subjectId = subjectId, deviceId = "device",
        occurredAt = "2026-01-01T00:00:00Z", recordedAt = "2026-01-01T00:00:00Z",
        visibility = "guardians", payload = emptyMap(),
    )

    private fun frame(source: FamilyState) = SyncFrameBody(
        frameId = "stale", household = source.household, deviceId = "stale-device", sequence = 1,
        createdAt = "2026-01-01T00:00:00Z", members = source.members, children = source.children,
        evidence = source.evidence, events = source.events,
    )

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
