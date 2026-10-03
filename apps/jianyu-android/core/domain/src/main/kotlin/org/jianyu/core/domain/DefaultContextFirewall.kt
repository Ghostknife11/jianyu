package org.jianyu.core.domain

import org.jianyu.core.model.WorldBrief

class DefaultContextFirewall : ContextFirewall {
    override fun minimize(request: OpportunityDiscoveryRequest, worldBriefs: List<WorldBrief>): FirewallResult {
        val included = buildList {
            add("age-band")
            add("lifecycle-stage")
            add("current-interest")
            add("declared-goals")
            add("practical-constraints")
            if (!request.schoolWindow.isNullOrBlank()) add("school-window")
            if (!request.lifeContext.isNullOrBlank()) add("life-context")
            if (!request.region.isNullOrBlank()) add("coarse-region")
            if (request.recentEvidence.isNotEmpty()) add("recent-evidence-summaries")
            if (request.includeRecentSelectionCountInProviderContext && request.recentInterventionCount > 0) {
                add("recent-intervention-count")
            }
            if (worldBriefs.isNotEmpty()) add("public-world-briefs")
        }
        return FirewallResult(
            context = TaskContext(
                ageBand = when (request.age) {
                    in 4..6 -> "4-6"
                    in 7..9 -> "7-9"
                    in 10..12 -> "10-12"
                    in 13..15 -> "13-15"
                    else -> "16+"
                },
                lifecycleStage = request.lifecycleStage,
                currentInterest = request.currentInterest.trim().take(800),
                childGoal = request.goals.child.trim().take(300),
                caregiverGoal = request.goals.caregiver?.trim()?.take(300),
                sharedGoal = request.goals.shared?.trim()?.take(300),
                schoolWindow = request.schoolWindow?.trim()?.take(500),
                lifeContext = request.lifeContext?.trim()?.take(500),
                region = request.region?.trim()?.take(80),
                constraints = request.constraints,
                recentEvidenceSummaries = request.recentEvidence.take(5).map { it.take(240) },
                publicWorldBriefs = worldBriefs.take(8),
                recentInterventionCount = request.recentInterventionCount.coerceAtLeast(0)
                    .takeIf { request.includeRecentSelectionCountInProviderContext && it > 0 },
            ),
            receipt = ContextDisclosureReceipt(
                purpose = "discover-family-opportunities",
                includedCategories = included,
                excludedCategories = listOf(
                    "names",
                    "household-id",
                    "child-id",
                    "member-ids",
                    "exact-location",
                    "full-family-history",
                    "provider-secrets",
                ) + if (request.recentInterventionCount > 0 && !request.includeRecentSelectionCountInProviderContext) {
                    listOf("recent-intervention-count")
                } else {
                    emptyList()
                },
            ),
        )
    }
}
