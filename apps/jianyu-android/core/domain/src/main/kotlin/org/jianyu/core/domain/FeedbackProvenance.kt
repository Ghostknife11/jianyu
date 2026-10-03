package org.jianyu.core.domain

import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.MemberRole
import java.time.Instant
import java.time.LocalDate

/** A record signature is provenance, not proof of who held a shared device. */
enum class FeedbackProvenance(val wireValue: String) {
    CHILD_SIGNED("child-signed"),
    CAREGIVER_RELAYED_CHILD_VIEW("caregiver-relayed-child-view"),
    UNKNOWN("unknown"),
}

data class FeedbackAttribution(val provenance: FeedbackProvenance, val occurredAt: Instant)

/**
 * v1 feedback had no response-source claim. Do not silently upgrade its single
 * feedback field into a verified child response when building later AI context.
 */
fun feedbackAttribution(choice: FamilyChoice, events: List<FamilyEvent>): FeedbackAttribution? {
    val feedback = choice.feedback ?: return null
    val matching = events.asSequence()
        .filter {
            it.eventType == "opportunity.feedback-recorded" &&
                it.eventVersion == 2 &&
                it.subjectId == choice.childId &&
                it.payload["choiceId"] == choice.id &&
                it.payload["value"] == feedback
        }
        .toList()
    if (matching.isEmpty()) return null
    val origins = matching.map { event ->
            when {
                event.payload["responseSource"] == FeedbackProvenance.CHILD_SIGNED.wireValue &&
                    event.actorRole == MemberRole.CHILD -> FeedbackProvenance.CHILD_SIGNED
                event.payload["responseSource"] == FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW.wireValue &&
                    event.actorRole in setOf(MemberRole.CAREGIVER, MemberRole.GUARDIAN) ->
                    FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW
                else -> FeedbackProvenance.UNKNOWN
            }
        }
        .distinct()
    val provenance = origins.singleOrNull()?.takeUnless { it == FeedbackProvenance.UNKNOWN } ?: return null
    val occurredAt = matching.mapNotNull { runCatching { Instant.parse(it.occurredAt) }.getOrNull() }
        .maxOrNull() ?: return null
    return FeedbackAttribution(provenance, occurredAt)
}

fun feedbackProvenance(choice: FamilyChoice, events: List<FamilyEvent>): FeedbackProvenance =
    feedbackAttribution(choice, events)?.provenance ?: FeedbackProvenance.UNKNOWN

/** Append one source-marked view without changing the original family choice. */
fun appendChoiceFeedback(
    family: FamilyState,
    choiceId: String,
    value: String,
    author: FamilyMember,
    provenance: FeedbackProvenance,
    eventId: String,
    recordedAt: Instant,
    deviceId: String,
    referenceDate: LocalDate = LocalDate.now(),
): FamilyState {
    require(value in setOf("喜欢", "一般", "不合适")) { "不支持的反馈内容" }
    val choice = requireNotNull(family.choices.firstOrNull { it.id == choiceId }) { "入口选择已不存在" }
    require(choice.status == "chosen" && choice.feedback == null) { "这次选择已经有后续看法" }
    val child = requireNotNull(family.children.firstOrNull { it.id == choice.childId }) { "关联的孩子资料已不存在" }
    val stage = lifecycleStage(child, referenceDate)
    require(stage != LifecycleStage.GRADUATION) { "成年交接后不能新增家长侧反馈" }
    require(!isLegacyDemoChoice(choice, family.events)) { "离线演示选择不能追加真实反馈" }
    require(family.members.any { it.id == author.id && it == author }) { "署名成员不属于当前家庭" }
    when (provenance) {
        FeedbackProvenance.CHILD_SIGNED -> {
            require(stage == LifecycleStage.HAND_OVER && author.role == MemberRole.CHILD &&
                author.id == child.memberId && author.subjectId == child.id) { "放权阶段需要孩子署名" }
        }
        FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW -> {
            require(stage != LifecycleStage.HAND_OVER && author.role in setOf(MemberRole.CAREGIVER, MemberRole.GUARDIAN)) {
                "只有家长或监护人能代记较小孩子的看法"
            }
        }
        FeedbackProvenance.UNKNOWN -> error("来源不明的反馈不能新增")
    }
    val restrictedSource = choice.hasRestrictedLinkedEvent(family.events)
    val event = FamilyEvent(
        eventId = eventId,
        eventType = "opportunity.feedback-recorded",
        eventVersion = 2,
        householdId = family.household.id,
        authorId = author.id,
        actorRole = author.role,
        subjectId = choice.childId,
        deviceId = deviceId,
        occurredAt = recordedAt.toString(),
        recordedAt = recordedAt.toString(),
        visibility = if (restrictedSource) {
            "child-private"
        } else if (stage == LifecycleStage.CO_SELECT || provenance == FeedbackProvenance.CHILD_SIGNED) {
            "shared-with-child"
        } else {
            "guardians"
        },
        payload = mapOf("choiceId" to choiceId, "value" to value, "responseSource" to provenance.wireValue),
    )
    return family.copy(
        choices = family.choices.map { if (it.id == choiceId) it.copy(status = "reflected", feedback = value) else it },
        events = family.events + event,
    )
}
