package org.jianyu.core.domain

import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.Hypothesis
import org.jianyu.core.model.MemberRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyDemoRecordsTest {
    @Test
    fun `old preview input and dependent interpretations are historical, even when mixed with real input`() {
        val demo = evidence("demo", "演示赛车")
        val real = evidence("real", "真的看了比赛")
        val demoEvent = event("offline-demo", "演示赛车")
        val realEvent = event("byok-ai", "真的看了比赛")
        val events = listOf(demoEvent, realEvent)

        assertTrue(isLegacyDemoEvidence(demo, events))
        assertFalse(isLegacyDemoEvidence(real, events))
        assertTrue(isLegacyDemoHypothesis(hypothesis("mixed", listOf("demo", "real")), listOf(demo, real), events))
        assertFalse(isLegacyDemoHypothesis(hypothesis("real-only", listOf("real")), listOf(demo, real), events))
    }

    private fun evidence(id: String, expression: String) = Evidence(
        id = id,
        childId = "child",
        authorId = "parent",
        stream = ContextStream.CHILD,
        kind = EvidenceKind.DIRECT_OBSERVATION,
        expression = expression,
        occurredAt = AT,
        recordedAt = AT,
        ownerId = "child",
        visibility = EvidenceVisibility.GUARDIANS,
    )

    private fun event(mode: String, expression: String) = FamilyEvent(
        eventId = "event-$mode",
        eventType = "interest.observed",
        householdId = "household",
        authorId = "parent",
        actorRole = MemberRole.CAREGIVER,
        subjectId = "child",
        deviceId = "android-local",
        occurredAt = AT,
        recordedAt = AT,
        visibility = "guardians",
        payload = mapOf("discoveryMode" to mode, "expression" to expression),
    )

    private fun hypothesis(id: String, supports: List<String>) = Hypothesis(
        id = id,
        childId = "child",
        statement = "测试用推测",
        confidence = 0.5,
        supports = supports,
        contradicts = emptyList(),
        modelId = "test",
        policyVersion = "test",
        derivedAt = AT,
    )

    private companion object {
        const val AT = "2026-09-20T00:00:00Z"
    }
}
