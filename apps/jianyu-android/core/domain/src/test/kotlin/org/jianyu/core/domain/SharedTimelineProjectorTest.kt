package org.jianyu.core.domain

import org.jianyu.core.model.Child
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.Household
import org.jianyu.core.model.Hypothesis
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.RiskLevel
import org.jianyu.core.model.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTimelineProjectorTest {
    @Test
    fun `shared projection excludes private evidence and dependent hypothesis`() {
        val family = family(
            evidence = listOf(evidence("shared", EvidenceVisibility.SHARED_WITH_CHILD), evidence("private", EvidenceVisibility.CHILD_PRIVATE)),
            hypotheses = listOf(
                hypothesis("visible-hypothesis", supports = listOf("shared")),
                hypothesis("private-hypothesis", supports = listOf("private")),
                hypothesis("mixed-hypothesis", supports = listOf("shared"), contradicts = listOf("private")),
                hypothesis("unlinked-hypothesis", supports = emptyList()),
            ),
        )

        val projection = projectSharedTimeline(family)

        assertEquals(listOf("shared"), projection.evidence.map { it.id })
        assertEquals(listOf("visible-hypothesis"), projection.hypotheses.map { it.id })
        assertTrue(projection.hasRestrictedRecords)
    }

    @Test
    fun `shared projection hides restricted events and linked choices`() {
        val family = family(
            choices = listOf(choice("shared-choice", "shared-source"), choice("private-choice", "private-source")),
            events = listOf(
                event("shared-source", "guardians"),
                event("shared-choice-event", "guardians", mapOf("choiceId" to "shared-choice")),
                event("private-source", "child-private"),
                event("private-choice-event", "child-private", mapOf("choiceId" to "private-choice")),
            ),
        )

        val projection = projectSharedTimeline(family)

        assertEquals(listOf("shared-choice"), projection.choices.map { it.id })
        assertEquals(listOf("shared-source", "shared-choice-event"), projection.events.map { it.eventId })
        assertTrue(projection.hasRestrictedRecords)
    }

    @Test
    fun `private source hides later shared labelled choice and response events too`() {
        val family = family(
            choices = listOf(choice("private-choice", "private-source")),
            events = listOf(
                event("private-source", "child-private"),
                event("choice-event", "shared-with-child", mapOf("choiceId" to "private-choice", "title" to "不能公开的入口")),
                event("response-event", "shared-with-child", mapOf("choiceId" to "private-choice", "value" to "不合适")),
                event("unrelated", "guardians"),
            ),
        )

        val projection = projectSharedTimeline(family)

        assertTrue(projection.choices.isEmpty())
        assertEquals(listOf("unrelated"), projection.events.map { it.eventId })
        assertTrue(projection.hasRestrictedRecords)
        assertFalse(projection.events.toString().contains("不能公开的入口"))
    }

    @Test
    fun `shared projection fails closed for visibility requiring identity or unknown policy`() {
        val family = family(
            events = listOf(
                event("guardians", "guardians"),
                event("family", "family"),
                event("shared-child", "shared-with-child"),
                event("selected", "selected-members"),
                event("recommendation", "recommendation-only"),
                event("private", "private"),
                event("unknown", "future-policy"),
            ),
        )

        val projection = projectSharedTimeline(family)

        assertEquals(listOf("guardians", "family", "shared-child"), projection.events.map { it.eventId })
        assertTrue(projection.hasRestrictedRecords)
    }

    @Test
    fun `ordinary shared timeline remains unchanged`() {
        val family = family(
            evidence = listOf(evidence("shared", EvidenceVisibility.GUARDIANS)),
            choices = listOf(choice("choice", "missing-legacy-source")),
            hypotheses = listOf(hypothesis("hypothesis", supports = listOf("shared"))),
            events = listOf(event("event", "guardians")),
        )

        val projection = projectSharedTimeline(family)

        assertEquals(family.evidence, projection.evidence)
        assertEquals(family.choices, projection.choices)
        assertEquals(family.hypotheses, projection.hypotheses)
        assertEquals(family.events, projection.events)
        assertFalse(projection.hasRestrictedRecords)
    }

    private fun family(
        evidence: List<Evidence> = emptyList(),
        hypotheses: List<Hypothesis> = emptyList(),
        events: List<FamilyEvent> = emptyList(),
        choices: List<FamilyChoice> = emptyList(),
    ) = FamilyState(
        household = Household("household", "测试家庭", NOW),
        members = listOf(FamilyMember("caregiver", "家长", MemberRole.CAREGIVER, createdAt = NOW)),
        children = listOf(Child("child", "child-member", "孩子", 2014, NOW, "2014-01-01")),
        evidence = evidence,
        hypotheses = hypotheses,
        events = events,
        choices = choices,
    )

    private fun evidence(id: String, visibility: EvidenceVisibility) = Evidence(
        id = id,
        childId = "child",
        authorId = "caregiver",
        stream = ContextStream.CHILD,
        kind = EvidenceKind.CHILD_STATED,
        expression = "合成记录 $id",
        occurredAt = NOW,
        recordedAt = NOW,
        ownerId = "child-member",
        visibility = visibility,
    )

    private fun hypothesis(id: String, supports: List<String>, contradicts: List<String> = emptyList()) = Hypothesis(
        id = id,
        childId = "child",
        statement = "合成推测 $id",
        confidence = 0.5,
        supports = supports,
        contradicts = contradicts,
        modelId = "test",
        policyVersion = "test",
        derivedAt = NOW,
    )

    private fun event(id: String, visibility: String, payload: Map<String, String> = emptyMap()) = FamilyEvent(
        eventId = id,
        eventType = "test.event",
        householdId = "household",
        authorId = "caregiver",
        actorRole = MemberRole.CAREGIVER,
        subjectId = "child",
        deviceId = "test-device",
        occurredAt = NOW,
        recordedAt = NOW,
        visibility = visibility,
        payload = payload,
    )

    private fun choice(id: String, sourceEventId: String) = FamilyChoice(
        id = id,
        childId = "child",
        opportunity = Opportunity(
            opportunityId = "opportunity-$id",
            title = "合成机会 $id",
            explanation = "说明",
            whyNow = "现在",
            ecosystem = "生活",
            primaryGoal = GoalOwner.CHILD,
            childPull = true,
            requirements = OpportunityRequirements(30, CostBand.FREE, EnergyBand.LOW, 0),
            sourceKind = "test",
            verification = Verification.IDEA,
            score = 0.5,
            riskLevel = RiskLevel.LOW,
        ),
        sourceEventId = sourceEventId,
        status = "chosen",
        chosenAt = NOW,
    )

    private companion object {
        const val NOW = "2026-09-20T00:00:00Z"
    }
}
