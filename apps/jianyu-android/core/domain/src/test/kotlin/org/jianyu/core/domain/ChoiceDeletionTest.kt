package org.jianyu.core.domain

import org.jianyu.core.model.Child
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.DeletionTombstone
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.Household
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.SyncFrameBody
import org.jianyu.core.model.TombstoneTarget
import org.jianyu.core.model.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChoiceDeletionTest {
    private val at = "2026-10-03T08:00:00Z"
    private val referenceDate = LocalDate.of(2026, 10, 3)
    private val secretTitle = "不再保留的火车入口"
    private val household = Household("household", "虚构家庭", at)
    private val caregiver = FamilyMember("caregiver", "家长", MemberRole.CAREGIVER, createdAt = at)
    private val subject = FamilyMember("subject-member", "小禾", MemberRole.CHILD, "subject", at)

    @Test
    fun `deleting a choice removes linked content events and stale merge cannot restore them`() {
        val original = family(2018)
        val ids = listOf("choice-tombstone", "event-tombstone-1", "event-tombstone-2", "deletion-audit").iterator()
        val deleted = deleteChoiceWithTombstones(
            state = original,
            choiceId = "choice-1",
            authorId = caregiver.id,
            actorRole = caregiver.role,
            subjectConfirmed = false,
            deviceId = "device-local",
            deletedAt = at,
            nextId = ids::next,
            referenceDate = referenceDate,
        )

        assertTrue(deleted.choices.isEmpty())
        assertEquals(setOf(TombstoneTarget.CHOICE, TombstoneTarget.EVENT), deleted.tombstones.map(DeletionTombstone::targetType).toSet())
        assertEquals(setOf("choice-1", "choice-event", "feedback-event"), deleted.tombstones.map(DeletionTombstone::targetId).toSet())
        assertEquals(setOf("source-event", "unrelated-event", "deletion-audit"), deleted.events.map(FamilyEvent::eventId).toSet())
        assertEquals("opportunity.choice-deleted", deleted.events.last().eventType)
        assertFalse(deleted.choices.toString().contains(secretTitle))
        assertFalse(deleted.events.toString().contains(secretTitle))
        assertFalse(deleted.tombstones.toString().contains(secretTitle))
        assertFalse(deleted.events.toString().contains("不合适"))

        val stale = SyncFrameBody(
            frameId = "old-frame", household = household, deviceId = "other-device", sequence = 1,
            createdAt = at, members = original.members, children = original.children,
            choices = original.choices, events = original.events,
        )
        val merged = FamilyStateMerger().merge(deleted, stale).state
        assertTrue(merged.choices.isEmpty())
        assertEquals(deleted.events.map(FamilyEvent::eventId).toSet(), merged.events.map(FamilyEvent::eventId).toSet())
        assertEquals(deleted.tombstones.toSet(), merged.tombstones.toSet())
        assertEquals(deleted, deleteChoiceWithTombstones(
            deleted, "choice-1", caregiver.id, caregiver.role, false, "device-local", at,
            { error("idempotent deletion must not consume IDs") }, referenceDate,
        ))
    }

    @Test
    fun `hand over and graduation require explicit subject confirmation and attribution`() {
        for (birthYear in listOf(2012, 2010)) {
            val original = family(birthYear)
            assertThrows(IllegalArgumentException::class.java) {
                deleteChoiceWithTombstones(original, "choice-1", subject.id, MemberRole.CHILD, false,
                    "device-local", at, ids(), referenceDate)
            }
            assertThrows(IllegalArgumentException::class.java) {
                deleteChoiceWithTombstones(original, "choice-1", caregiver.id, MemberRole.CAREGIVER, true,
                    "device-local", at, ids(), referenceDate)
            }
            val deleted = deleteChoiceWithTombstones(original, "choice-1", subject.id, MemberRole.CHILD, true,
                "device-local", at, ids(), referenceDate)
            assertEquals(subject.id, deleted.events.last().authorId)
            assertEquals(MemberRole.CHILD, deleted.events.last().actorRole)
        }
    }

    @Test
    fun `cross subject linked event fails before deleting anything`() {
        val original = family(2018)
        val inconsistent = original.copy(events = original.events + event("wrong-subject", "opportunity.note", "other-subject", mapOf("choiceId" to "choice-1")))
        assertThrows(IllegalArgumentException::class.java) {
            deleteChoiceWithTombstones(inconsistent, "choice-1", caregiver.id, caregiver.role, false,
                "device-local", at, ids(), referenceDate)
        }
    }

    private fun family(birthYear: Int): FamilyState {
        val choice = FamilyChoice(
            id = "choice-1", childId = "subject", opportunity = Opportunity(
                opportunityId = "opportunity-1", title = secretTitle, explanation = "一次具体体验",
                whyNow = "孩子提出疑问", ecosystem = "places", primaryGoal = GoalOwner.CHILD,
                childPull = true, requirements = OpportunityRequirements(30, CostBand.FREE, EnergyBand.LOW, 0),
                sourceKind = "byok-llm", verification = Verification.IDEA, score = 0.5,
            ), sourceEventId = "source-event", status = "reflected", chosenAt = at, feedback = "不合适",
        )
        return FamilyState(
            household = household, members = listOf(caregiver, subject),
            children = listOf(Child("subject", subject.id, "小禾", birthYear, at, "$birthYear-01-01")),
            choices = listOf(choice),
            events = listOf(
                event("source-event", "interest.recorded", "subject", mapOf("scope" to "interest")),
                event("choice-event", "opportunity.chosen", "subject", mapOf("choiceId" to choice.id, "title" to secretTitle)),
                event("feedback-event", "opportunity.feedback-recorded", "subject", mapOf("choiceId" to choice.id, "value" to "不合适")),
                event("unrelated-event", "household.note", "subject", mapOf("scope" to "unrelated")),
            ),
        )
    }

    private fun event(id: String, type: String, subjectId: String, payload: Map<String, String>) = FamilyEvent(
        eventId = id, eventType = type, householdId = household.id, authorId = caregiver.id,
        actorRole = MemberRole.CAREGIVER, subjectId = subjectId, deviceId = "device-local",
        occurredAt = at, recordedAt = at, visibility = "guardians", payload = payload,
    )

    private fun ids(): () -> String = listOf("choice-tombstone", "event-tombstone-1", "event-tombstone-2", "deletion-audit").iterator()::next
}
