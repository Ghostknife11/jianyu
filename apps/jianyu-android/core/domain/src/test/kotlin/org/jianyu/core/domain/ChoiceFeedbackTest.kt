package org.jianyu.core.domain

import org.jianyu.core.model.Child
import org.jianyu.core.model.CostBand
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
import org.jianyu.core.model.RiskLevel
import org.jianyu.core.model.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ChoiceFeedbackTest {
    private val referenceDate = LocalDate.of(2026, 9, 27)
    private val now = Instant.parse("2026-09-27T08:00:00Z")
    private val caregiver = FamilyMember("caregiver", "Parent", MemberRole.CAREGIVER, createdAt = now.toString())
    private val teenMember = FamilyMember("teen-member", "Teen", MemberRole.CHILD, "teen", now.toString())

    @Test
    fun `handover feedback stays child signed and does not imply participation`() {
        val family = family("teen", "2012-09-19", teenMember)
        val next = appendChoiceFeedback(family, "choice", "喜欢", teenMember, FeedbackProvenance.CHILD_SIGNED,
            "feedback-event", now, "test-device", referenceDate)

        assertEquals("reflected", next.choices.single().status)
        assertEquals("喜欢", next.choices.single().feedback)
        assertEquals(2, next.events.single().eventVersion)
        assertEquals("child-signed", next.events.single().payload["responseSource"])
        assertEquals(FeedbackProvenance.CHILD_SIGNED, feedbackProvenance(next.choices.single(), next.events))
        assertThrows(IllegalArgumentException::class.java) {
            appendChoiceFeedback(next, "choice", "一般", teenMember, FeedbackProvenance.CHILD_SIGNED,
                "second-event", now, "test-device", referenceDate)
        }
    }

    @Test
    fun `later child view keeps a private source private`() {
        val original = family("teen", "2012-09-19", teenMember)
        val source = FamilyEvent(
            eventId = "discovery", eventType = "interest.recorded", householdId = original.household.id,
            authorId = teenMember.id, actorRole = MemberRole.CHILD, subjectId = "teen",
            deviceId = "test-device", occurredAt = now.toString(), recordedAt = now.toString(),
            visibility = "child-private", payload = mapOf("scope" to "current-interest"),
        )
        val next = appendChoiceFeedback(original.copy(events = listOf(source)), "choice", "喜欢", teenMember,
            FeedbackProvenance.CHILD_SIGNED, "feedback-event", now, "test-device", referenceDate)

        assertEquals("child-private", next.events.last().visibility)
        assertEquals(emptyList<String>(), projectSharedTimeline(next).events.map { it.eventId })
    }

    @Test
    fun `caregiver may relay a younger child's view but not sign over a teenager's decision`() {
        val younger = family("young", "2018-09-14", FamilyMember("young-member", "Young", MemberRole.CHILD, "young", now.toString()))
        val relayed = appendChoiceFeedback(younger, "choice", "一般", caregiver,
            FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW, "feedback-event", now, "test-device", referenceDate)
        assertEquals("caregiver-relayed-child-view", relayed.events.single().payload["responseSource"])
        assertEquals("guardians", relayed.events.single().visibility)
        assertEquals(FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW,
            feedbackProvenance(relayed.choices.single(), relayed.events))

        val coSelecting = family("co-select", "2015-09-14", FamilyMember("co-select-member", "Co-select", MemberRole.CHILD, "co-select", now.toString()))
        val sharedView = appendChoiceFeedback(coSelecting, "choice", "喜欢", caregiver,
            FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW, "shared-feedback-event", now, "test-device", referenceDate)
        assertEquals("caregiver", sharedView.events.single().authorId)
        assertEquals(MemberRole.CAREGIVER, sharedView.events.single().actorRole)
        assertEquals("caregiver-relayed-child-view", sharedView.events.single().payload["responseSource"])
        assertEquals("shared-with-child", sharedView.events.single().visibility)

        val teen = family("teen", "2012-09-19", teenMember)
        assertThrows(IllegalArgumentException::class.java) {
            appendChoiceFeedback(teen, "choice", "喜欢", caregiver,
                FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW, "feedback-event", now, "test-device", referenceDate)
        }
    }

    @Test
    fun `graduation cannot add a new child feedback record`() {
        val adult = family("adult", "2010-09-14", FamilyMember("adult-member", "Adult", MemberRole.CHILD, "adult", now.toString()))
        assertThrows(IllegalArgumentException::class.java) {
            appendChoiceFeedback(adult, "choice", "喜欢", caregiver,
                FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW, "feedback-event", now, "test-device", referenceDate)
        }
    }

    private fun family(childId: String, birthDate: String, childMember: FamilyMember): FamilyState {
        val opportunity = Opportunity(
            opportunityId = "opportunity",
            title = "看看火车",
            explanation = "一起观察",
            whyNow = "孩子提起火车",
            ecosystem = "real-world",
            primaryGoal = GoalOwner.CHILD,
            childPull = true,
            requirements = OpportunityRequirements(30, CostBand.FREE, EnergyBand.LOW, 0),
            sourceKind = "test",
            verification = Verification.IDEA,
            riskLevel = RiskLevel.LOW,
            score = 0.7,
        )
        return FamilyState(
            household = Household("household", "Family", now.toString()),
            members = listOf(caregiver, childMember),
            children = listOf(Child(childId, childMember.id, childMember.displayName,
                birthDate.substring(0, 4).toInt(), now.toString(), birthDate)),
            choices = listOf(FamilyChoice("choice", childId, opportunity, "discovery", "chosen", now.toString())),
        )
    }
}
