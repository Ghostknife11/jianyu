package org.jianyu.app.ui

import org.jianyu.app.MainUiState
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.LifecycleStage

/** Explain an empty displayed set without confusing Gate rejection with diversity omission. */
internal fun emptyDiscoveryDescriptionFor(state: MainUiState, stage: LifecycleStage): String {
    val set = requireNotNull(state.opportunities)
    return emptyDiscoveryDescription(
        hasIncompleteSource = state.discoverySourceIssues.isNotEmpty(),
        childFacing = stage == LifecycleStage.HAND_OVER,
        hasUnsourcedAiWorldEvent = set.rejected.any { "ai-world-event-without-source" in it.reasons },
        hasCaregiverOnlyCandidates = set.rejected.isEmpty() && set.eligibleNotSelected.isNotEmpty() &&
            set.eligibleNotSelected.all { it.opportunity.primaryGoal == GoalOwner.CAREGIVER },
    )
}

/** A missing result is not evidence that doing nothing is the best option. */
internal fun emptyDiscoveryDescription(
    hasIncompleteSource: Boolean,
    childFacing: Boolean,
    hasUnsourcedAiWorldEvent: Boolean = false,
    hasCaregiverOnlyCandidates: Boolean = false,
): String =
    if (hasIncompleteSource) {
        "有来源未完成，这次暂时没有可展示的入口；不能据此判断没有合适机会。" +
            if (childFacing) "你可以调整后再试，也可以留白。" else "你们可以调整后再试，也可以留白。"
    } else if (hasUnsourcedAiWorldEvent) {
        "AI 提到的活动缺少原始来源。\n本机未展示。\n可以换个线索，也可以留白。"
    } else if (hasCaregiverOnlyCandidates) {
        "这次只找到回应家长期待的入口。\n" +
            if (childFacing) {
                "没有把它们当作你的入口展示。\n" +
                    "如果你另有想做或想知道的事，\n可以换个线索；也可以留白。"
            } else {
                "没有把它们当作孩子的入口展示。\n" +
                    "孩子若另有想做或想知道的事，\n可以换个线索；也可以留白。"
            }
    } else {
        "这次没有可展示的入口。不必为了凑数安排活动；可以换个线索，也可以留白。"
    }
