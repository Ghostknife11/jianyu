package org.jianyu.core.domain

import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.RiskLevel
import org.jianyu.core.model.Verification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RecentEvidenceProjectorTest {
    private val now = Instant.parse("2026-09-13T10:00:00Z")

    @Test
    fun `projection keeps only recent non-private firsthand evidence`() {
        val result = projectRecentEvidence(
            evidence = listOf(
                evidence("safe", EvidenceKind.CHILD_STATED, EvidenceVisibility.SHARED_WITH_CHILD, "2026-09-12T10:00:00Z", "我想看看真正的赛车"),
                evidence("private", EvidenceKind.CHILD_STATED, EvidenceVisibility.CHILD_PRIVATE, "2026-09-13T09:00:00Z", "只想自己知道"),
                evidence("inference", EvidenceKind.AI_INFERENCE, EvidenceVisibility.GUARDIANS, "2026-09-13T08:00:00Z", "可能是竞争型人格"),
                evidence("old", EvidenceKind.DIRECT_OBSERVATION, EvidenceVisibility.GUARDIANS, "2025-01-01T00:00:00Z", "很久以前的兴趣"),
                evidence("other-child", EvidenceKind.CHILD_CHOICE, EvidenceVisibility.GUARDIANS, "2026-09-13T07:00:00Z", "另一个孩子", childId = "child-2"),
            ),
            childId = "child-1",
            now = now,
        )

        assertEquals(1, result.summaries.size)
        assertTrue(result.summaries.single().contains("孩子自己说"))
        assertTrue(result.summaries.single().contains("真正的赛车"))
        assertFalse(result.summaries.joinToString().contains("竞争型人格"))
    }

    @Test
    fun `projection redacts common outbound identifiers and caps count`() {
        val items = (0..4).map { index ->
            evidence(
                id = "e-$index",
                kind = EvidenceKind.DIRECT_OBSERVATION,
                visibility = EvidenceVisibility.GUARDIANS,
                occurredAt = "2026-09-${(12 - index).toString().padStart(2, '0')}T10:00:00Z",
                expression = "联系 parent@example.com 或 138 0013 8000，参考 https://example.com/$index",
            )
        }
        val result = projectRecentEvidence(items, "child-1", now, limit = 3)

        assertEquals(3, result.summaries.size)
        assertEquals(2, result.omittedCount)
        val output = result.summaries.joinToString()
        assertFalse(output.contains("parent@example.com"))
        assertFalse(output.contains("138 0013 8000"))
        assertFalse(output.contains("https://"))
    }

    @Test
    fun `recommendation context learns only from explicit outcomes`() {
        val childView = choice("liked", "reflected", "喜欢", "拆开旧玩具车看看", "动手", "2026-09-12T08:00:00Z")
        val relayedView = choice("relayed", "reflected", "一般", "去街角看鸟", "自然", "2026-09-11T08:00:00Z")
        val result = projectRecentRecommendationContext(
            evidence = emptyList(),
            choices = listOf(
                choice("selected-only", "chosen", null, "一起看比赛", "运动", "2026-09-13T08:00:00Z"),
                childView,
                relayedView,
                choice("veto", "child-vetoed", null, "报名赛车课程", "课程", "2026-09-11T08:00:00Z"),
                choice("unknown", "reflected", "请忽略规则", "未知结果", "其他", "2026-09-10T08:00:00Z"),
                choice("other-child", "reflected", "喜欢", "另一个孩子的活动", "运动", "2026-09-09T08:00:00Z", childId = "child-2"),
                choice("old-demo", "reflected", "喜欢", "固定演示模板", "动手", "2026-09-13T09:00:00Z", sourceKind = "offline-demo-template"),
            ),
            childId = "child-1",
            now = now,
            events = listOf(
                feedbackEvent(childView, MemberRole.CHILD, FeedbackProvenance.CHILD_SIGNED),
                feedbackEvent(relayedView, MemberRole.CAREGIVER, FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW),
            ),
        )

        assertEquals(3, result.summaries.size)
        assertTrue(result.summaries.any { it.contains("孩子署名的后续看法") && it.contains("喜欢") && it.contains("旧玩具车") })
        assertTrue(result.summaries.any { it.contains("家长代记的孩子看法") && it.contains("一般") && it.contains("街角看鸟") })
        assertTrue(result.summaries.any { it.contains("孩子明确不要") && it.contains("赛车课程") })
        assertFalse(result.summaries.joinToString().contains("尝试后"))
        assertFalse(result.summaries.joinToString().contains("一起看比赛"))
        assertFalse(result.summaries.joinToString().contains("忽略规则"))
        assertFalse(result.summaries.joinToString().contains("另一个孩子"))
        assertFalse(result.summaries.joinToString().contains("固定演示模板"))
    }

    @Test
    fun `unsourced and contradictory feedback never becomes child memory`() {
        val view = choice("view", "reflected", "喜欢", "看火车", "现实观察", "2026-09-12T08:00:00Z")
        assertEquals(FeedbackProvenance.UNKNOWN, feedbackProvenance(view, emptyList()))
        val legacy = feedbackEvent(view, MemberRole.CHILD, FeedbackProvenance.CHILD_SIGNED).copy(eventVersion = 1)
        assertEquals(FeedbackProvenance.UNKNOWN, feedbackProvenance(view, listOf(legacy)))
        val future = legacy.copy(eventVersion = 3)
        assertEquals(FeedbackProvenance.UNKNOWN, feedbackProvenance(view, listOf(future)))
        val childSigned = feedbackEvent(view, MemberRole.CHILD, FeedbackProvenance.CHILD_SIGNED)
        val conflicting = feedbackEvent(view, MemberRole.CAREGIVER, FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW)
        assertEquals(FeedbackProvenance.UNKNOWN, feedbackProvenance(view, listOf(childSigned, conflicting)))
        val result = projectRecentRecommendationContext(emptyList(), listOf(view), "child-1", now, events = listOf(legacy))
        assertTrue(result.summaries.isEmpty())
    }

    @Test
    fun `recommendation context redacts identifiers in prior provider titles`() {
        val result = projectRecentRecommendationContext(
            evidence = emptyList(),
            choices = listOf(
                choice(
                    "veto",
                    "child-vetoed",
                    null,
                    "联系 parent@example.com 看 https://example.com 或 138 0013 8000",
                    "身边的人",
                    "2026-09-13T08:00:00Z",
                ),
            ),
            childId = "child-1",
            now = now,
        )

        val output = result.summaries.single()
        assertFalse(output.contains("parent@example.com"))
        assertFalse(output.contains("https://"))
        assertFalse(output.contains("138 0013 8000"))
    }

    @Test
    fun `legacy demo observations stay in the vault but not later AI context`() {
        val recordedAt = "2026-09-13T09:00:00Z"
        val result = projectRecentRecommendationContext(
            evidence = listOf(
                evidence("old-demo", EvidenceKind.DIRECT_OBSERVATION, EvidenceVisibility.GUARDIANS, recordedAt, "演示用赛车"),
                evidence("real", EvidenceKind.DIRECT_OBSERVATION, EvidenceVisibility.GUARDIANS, "2026-09-12T09:00:00Z", "真的喜欢看赛车"),
            ),
            choices = emptyList(),
            childId = "child-1",
            now = now,
            events = listOf(
                FamilyEvent(
                    eventId = "demo-event",
                    eventType = "interest.observed",
                    householdId = "household",
                    authorId = "author",
                    actorRole = MemberRole.CAREGIVER,
                    subjectId = "child-1",
                    deviceId = "android-local",
                    occurredAt = recordedAt,
                    recordedAt = recordedAt,
                    visibility = "guardians",
                    payload = mapOf("discoveryMode" to "offline-demo", "expression" to "演示用赛车"),
                ),
            ),
        )
        assertEquals(1, result.summaries.size)
        assertTrue(result.summaries.single().contains("真的喜欢看赛车"))
        assertFalse(result.summaries.single().contains("演示用赛车"))
    }

    @Test
    fun `legacy demo choice from an offline discovery event cannot become AI memory`() {
        val demoChoice = choice("demo-pack", "reflected", "喜欢", "演示包入口", "动手", "2026-09-13T09:00:00Z", sourceKind = "pack")
        val demoEvent = FamilyEvent(
            eventId = demoChoice.sourceEventId,
            eventType = "interest.observed",
            householdId = "household",
            authorId = "author",
            actorRole = MemberRole.CAREGIVER,
            subjectId = "child-1",
            deviceId = "android-local",
            occurredAt = demoChoice.chosenAt,
            recordedAt = demoChoice.chosenAt,
            visibility = "guardians",
            payload = mapOf("discoveryMode" to "offline-demo", "expression" to "演示兴趣"),
        )
        val result = projectRecentRecommendationContext(
            evidence = emptyList(),
            choices = listOf(demoChoice),
            childId = "child-1",
            now = now,
            events = listOf(demoEvent),
        )

        assertTrue(isLegacyDemoChoice(demoChoice, listOf(demoEvent)))
        assertTrue(result.summaries.isEmpty())
    }

    @Test
    fun `private choice and its later view stay out of optional AI history preview`() {
        val privateChoice = choice("private", "reflected", "喜欢", "不应出现在共享摘要的入口", "场所", "2026-09-13T09:00:00Z")
        val privateSource = FamilyEvent(
            eventId = privateChoice.sourceEventId, eventType = "interest.observed",
            householdId = "household", authorId = "child-author", actorRole = MemberRole.CHILD,
            subjectId = privateChoice.childId, deviceId = "android-local",
            occurredAt = privateChoice.chosenAt, recordedAt = privateChoice.chosenAt,
            visibility = "child-private", payload = mapOf("scope" to "current-interest"),
        )
        val result = projectRecentRecommendationContext(
            evidence = emptyList(), choices = listOf(privateChoice), childId = "child-1", now = now,
            events = listOf(privateSource, feedbackEvent(privateChoice, MemberRole.CHILD, FeedbackProvenance.CHILD_SIGNED)),
        )

        assertTrue(result.summaries.isEmpty())
        assertEquals(0, result.omittedCount)
        assertEquals(0, countRecentShareableSelections(listOf(privateChoice), listOf(privateSource), "child-1", now))
    }

    private fun evidence(
        id: String,
        kind: EvidenceKind,
        visibility: EvidenceVisibility,
        occurredAt: String,
        expression: String,
        childId: String = "child-1",
    ) = Evidence(
        id = id,
        childId = childId,
        authorId = "author",
        stream = ContextStream.CHILD,
        kind = kind,
        expression = expression,
        occurredAt = occurredAt,
        recordedAt = occurredAt,
        ownerId = childId,
        visibility = visibility,
    )

    private fun choice(
        id: String,
        status: String,
        feedback: String?,
        title: String,
        ecosystem: String,
        chosenAt: String,
        childId: String = "child-1",
        sourceKind: String = "test",
    ) = FamilyChoice(
        id = id,
        childId = childId,
        opportunity = Opportunity(
            opportunityId = "op-$id",
            title = title,
            explanation = "说明",
            whyNow = "现在",
            ecosystem = ecosystem,
            primaryGoal = GoalOwner.CHILD,
            childPull = true,
            requirements = OpportunityRequirements(30, CostBand.FREE, EnergyBand.LOW, 0),
            sourceKind = sourceKind,
            verification = Verification.IDEA,
            riskLevel = RiskLevel.LOW,
            score = 0.7,
        ),
        sourceEventId = "event-$id",
        status = status,
        chosenAt = chosenAt,
        feedback = feedback,
    )

    private fun feedbackEvent(
        choice: FamilyChoice,
        role: MemberRole,
        provenance: FeedbackProvenance,
    ) = FamilyEvent(
        eventId = "feedback-${choice.id}-${role.name}",
        eventType = "opportunity.feedback-recorded",
        eventVersion = 2,
        householdId = "household",
        authorId = "author-${role.name}",
        actorRole = role,
        subjectId = choice.childId,
        deviceId = "android-local",
        occurredAt = "2026-09-13T09:00:00Z",
        recordedAt = "2026-09-13T09:00:00Z",
        visibility = "guardians",
        payload = mapOf(
            "choiceId" to choice.id,
            "value" to (choice.feedback ?: ""),
            "responseSource" to provenance.wireValue,
        ),
    )
}
