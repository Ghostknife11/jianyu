package org.jianyu.core.data

import org.jianyu.core.model.Child
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class GraduationBundleTest {
    private val now = Instant.parse("2026-09-14T08:00:00Z")
    private val codec = GraduationBundleCodec(
        clock = { now },
        currentYear = { 2026 },
        currentDate = { LocalDate.of(2026, 9, 14) },
    )

    @Test
    fun `graduating person receives only their attributable records and human view`() {
        val exported = codec.create(state(), "child-graduate", authorization("child-graduate"))
        val visibleEnvelope = exported.bytes.decodeToString()
        val archive = codec.open(exported.bytes, exported.recoveryCode)

        assertEquals("org.foe.graduation-archive/v2", archive.schema)
        assertEquals("Graduate Person", archive.subject.displayName)
        assertEquals("2010-09-14", archive.subject.birthDate)
        assertEquals(listOf("graduate-evidence", "caregiver-evidence"), archive.evidence.map(Evidence::id))
        assertEquals(setOf(MemberRole.CHILD, MemberRole.CAREGIVER), archive.authors.map { it.role }.toSet())
        assertTrue(archive.humanReadableMarkdown.contains("private-graduate-interest"))
        assertTrue(archive.humanReadableMarkdown.contains("不是能力、性格、潜力或发展评分"))
        assertFalse(archive.humanReadableMarkdown.contains("sibling-private-secret"))
        assertFalse(archive.evidence.any { it.childId == "child-sibling" })
        assertFalse(visibleEnvelope.contains("Graduate Person"))
        assertFalse(visibleEnvelope.contains("Sibling Person"))
        assertFalse(visibleEnvelope.contains("private-graduate-interest"))
    }

    @Test
    fun `human readable graduation view does not upgrade legacy feedback to a child statement`() {
        val opportunity = Opportunity(
            opportunityId = "opportunity",
            title = "看看火车",
            explanation = "观察火车",
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
        val sourced = FamilyChoice("sourced", "child-graduate", opportunity, "discovery", "reflected", now.toString(), "喜欢")
        val legacy = sourced.copy(id = "legacy", feedback = "一般")
        val event = FamilyEvent(
            eventId = "feedback-event",
            eventType = "opportunity.feedback-recorded",
            eventVersion = 2,
            householdId = "household-private",
            authorId = "member-graduate",
            actorRole = MemberRole.CHILD,
            subjectId = "child-graduate",
            deviceId = "test-device",
            occurredAt = now.toString(),
            recordedAt = now.toString(),
            visibility = "shared-with-child",
            payload = mapOf("choiceId" to "sourced", "value" to "喜欢", "responseSource" to "child-signed"),
        )
        val export = codec.create(state().copy(choices = listOf(sourced, legacy), events = listOf(event)),
            "child-graduate", authorization("child-graduate"))
        val markdown = codec.open(export.bytes, export.recoveryCode).humanReadableMarkdown
        assertTrue(markdown.contains("后续看法（孩子署名）：喜欢"))
        assertTrue(markdown.contains("后续看法（旧记录，来源未确认）：一般"))
        assertFalse(markdown.contains("真实反馈"))
    }

    @Test
    fun `each Graduation export has independent recovery material and ciphertext`() {
        val first = codec.create(state(), "child-graduate", authorization("child-graduate"))
        val second = codec.create(state(), "child-graduate", authorization("child-graduate"))

        assertNotEquals(first.recoveryCode, second.recoveryCode)
        assertNotEquals(first.bytes.decodeToString(), second.bytes.decodeToString())
        assertEquals("Graduate Person", codec.open(first.bytes, first.recoveryCode).subject.displayName)
        assertEquals("Graduate Person", codec.open(second.bytes, second.recoveryCode).subject.displayName)
    }

    @Test
    fun `wrong recovery code and modified Graduation bundle fail closed`() {
        val exported = codec.create(state(), "child-graduate", authorization("child-graduate"))
        val otherCode = codec.create(state(), "child-graduate", authorization("child-graduate")).recoveryCode
        assertFails { codec.open(exported.bytes, otherCode) }

        val text = exported.bytes.decodeToString()
        val marker = "\"ciphertext\":\""
        val index = text.indexOf(marker) + marker.length
        val replacement = if (text[index] == 'A') 'B' else 'A'
        val tampered = (text.substring(0, index) + replacement + text.substring(index + 1)).encodeToByteArray()
        assertFails { codec.open(tampered, exported.recoveryCode) }
    }

    @Test
    fun `underage or mismatched subject authorization cannot create Graduation export`() {
        assertFails { codec.create(state(), "child-sibling", authorization("child-sibling")) }
        assertFails { codec.create(state(), "child-graduate", authorization("child-sibling")) }
        assertFails {
            codec.create(
                state(),
                "child-graduate",
                authorization("child-graduate").copy(statementVersion = "unknown-consent"),
            )
        }
    }

    private fun authorization(subjectId: String) = GraduationAuthorization(subjectId, now.toString())

    private fun state() = FamilyState(
        household = Household("household-private", "Family Name", "2020-01-01T00:00:00Z"),
        members = listOf(
            FamilyMember("member-graduate", "Graduate Person", MemberRole.CHILD, "child-graduate", "2020-01-01T00:00:00Z"),
            FamilyMember("member-sibling", "Sibling Person", MemberRole.CHILD, "child-sibling", "2020-01-01T00:00:00Z"),
            FamilyMember("member-caregiver", "Caregiver Name", MemberRole.CAREGIVER, createdAt = "2020-01-01T00:00:00Z"),
        ),
        children = listOf(
            Child("child-graduate", "member-graduate", "Graduate Person", 2010, "2020-01-01T00:00:00Z", "2010-09-14"),
            Child("child-sibling", "member-sibling", "Sibling Person", 2015, "2020-01-01T00:00:00Z"),
        ),
        evidence = listOf(
            evidence("graduate-evidence", "child-graduate", "member-graduate", "private-graduate-interest"),
            evidence("caregiver-evidence", "child-graduate", "member-caregiver", "caregiver-observation"),
            evidence("sibling-evidence", "child-sibling", "member-sibling", "sibling-private-secret"),
        ),
    )

    private fun evidence(id: String, childId: String, memberId: String, expression: String) = Evidence(
        id = id,
        childId = childId,
        authorId = memberId,
        stream = ContextStream.CHILD,
        kind = EvidenceKind.CHILD_STATED,
        expression = expression,
        occurredAt = "2026-01-01T00:00:00Z",
        recordedAt = "2026-01-01T00:00:00Z",
        ownerId = memberId,
        visibility = EvidenceVisibility.CHILD_PRIVATE,
    )

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException)
    }
}
