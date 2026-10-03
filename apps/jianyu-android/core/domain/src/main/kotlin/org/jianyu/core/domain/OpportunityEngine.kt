package org.jianyu.core.domain

import java.util.concurrent.CancellationException
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.EvaluatedOpportunity
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.OpportunitySet
import org.jianyu.core.model.RiskLevel
import org.jianyu.core.model.Verification
import org.jianyu.core.model.WorldBrief
import java.time.Instant
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

class DefaultOpportunityPolicy(private val clock: () -> Instant = Instant::now) : OpportunityPolicy {
    override val id = "org.jianyu.opportunity-default"
    override val version = "0.2.10"

    override fun evaluate(opportunity: Opportunity, request: OpportunityDiscoveryRequest): PolicyDecision {
        val constraints = request.constraints
        val evaluationTime = clock()
        val expiry = opportunity.expiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val worldRetrievedAt = if (opportunity.sourceKind == "world-brief") {
            opportunity.retrievedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        } else null
        val reasons = buildList {
            if (!currentInterestHasNonRefusalClue(request.currentInterest)) add("no-current-child-pull")
            if (opportunity.title.isBlank() || opportunity.explanation.isBlank()) add("missing-explanation")
            if (opportunity.isNothing || opportunity.ecosystem == OpportunityEcosystems.NOTHING) add("reserved-nothing-option")
            if (constraints.timeMinutes <= 0) add("no-available-time")
            if (opportunity.requirements.timeMinutes <= 0) add("invalid-duration")
            if (opportunity.requirements.timeMinutes > constraints.timeMinutes) add("exceeds-time")
            if (opportunity.requirements.travelMinutes > constraints.travelMinutesMax) add("exceeds-travel")
            if (opportunity.requirements.costBand.ordinal > constraints.costBand.ordinal) add("exceeds-cost")
            if (opportunity.requirements.caregiverEnergy.ordinal > constraints.caregiverEnergy.ordinal) add("exceeds-caregiver-energy")
            if (opportunity.primaryGoal == GoalOwner.CAREGIVER && !opportunity.childPull) add("caregiver-goal-without-child-pull")
            if (!opportunity.childPull) add("insufficient-child-pull")
            if (opportunity.riskLevel == RiskLevel.HIGH) add("high-risk")
            if (request.age !in opportunity.minAge..opportunity.maxAge) add("age-out-of-range")
            if (!opportunity.naturalEntry) add("forced-educational-connection")
            if (opportunity.interventionPressure == RiskLevel.HIGH) add("excessive-intervention-pressure")
            if (listOf(opportunity.title, opportunity.explanation, opportunity.whyNow).any { it.isObviousDailyTask() }) {
                add("daily-task-pressure")
            }
            if (opportunity.score !in 0.0..1.0 || opportunity.confidence !in 0.0..1.0) add("invalid-confidence")
            if (opportunity.ecosystem.isBlank()) add("missing-ecosystem")
            if (opportunity.sourceKind == "byok-llm" &&
                OpportunityEcosystems.canonical(opportunity.ecosystem) == OpportunityEcosystems.WORLD_EVENT &&
                opportunity.sourceUrl.isNullOrBlank()
            ) add("ai-world-event-without-source")
            if (opportunity.expiresAt != null && expiry == null) add("invalid-expiry")
            if (expiry != null && !expiry.isAfter(evaluationTime)) add("expired-source")
            if (opportunity.sourceKind == "world-brief") {
                if (worldRetrievedAt == null) add("invalid-world-retrieval-time")
                else {
                    if (worldRetrievedAt.isBefore(evaluationTime.minusSeconds(14 * 24 * 60 * 60))) add("stale-world-brief")
                    if (worldRetrievedAt.isAfter(evaluationTime.plusSeconds(5 * 60))) add("future-world-retrieval-time")
                }
            }
            opportunity.sourceUrl?.let { if (externalSourceHost(it) == null) add("unsafe-source-url") }
        }
        val warnings = buildList {
            // The current request counts selections, not evidence that the activities happened.
            // Keep restraint visible without overriding a fresh child-led choice.
            if (request.recentInterventionCount >= 4) add("recent-intervention-load")
            if (opportunity.verification != Verification.VERIFIED) add("verification-${opportunity.verification.name.lowercase()}")
            if (opportunity.verification == Verification.VERIFIED && opportunity.sourceTitle.isNullOrBlank()) add("verified-without-source")
            if (opportunity.verification == Verification.VERIFIED && opportunity.sourceUrl.isNullOrBlank() && opportunity.sourceKind == "world-brief") {
                add("verified-without-source-url")
            }
            if (opportunity.riskLevel == RiskLevel.UNKNOWN) add("risk-unknown")
            if (opportunity.interventionPressure == RiskLevel.MEDIUM) add("intervention-pressure-medium")
            if (!opportunity.sponsorship.isNullOrBlank()) add("sponsored-content")
            if (opportunity.trackingWarning) add("source-tracking-warning")
            if (opportunity.bookingRequired) add("booking-required")
        }
        return PolicyDecision(
            allowed = reasons.isEmpty(),
            reasons = reasons.ifEmpty { listOf("fits-declared-constraints") },
            warnings = warnings,
        )
    }
}

/** High-confidence clause cues only; a child's self-directed study is not categorically excluded. */
private fun String.isObviousDailyTask(): Boolean {
    return split(ASSIGNMENT_CLAUSE_BOUNDARY).any { clause ->
        val text = clause.trim().lowercase(Locale.ROOT)
        DAILY_ASSIGNMENT_TITLE.containsMatchIn(text) || MANDATORY_ASSIGNMENT_TITLE.containsMatchIn(text) ||
            ENGLISH_DAILY_ASSIGNMENT_TITLE.containsMatchIn(text)
    }
}

private val ASSIGNMENT_CLAUSE_BOUNDARY = Regex("[，,。；;：:！!？?\\n]+")
private val DAILY_ASSIGNMENT_TITLE = Regex(
    "^(?:然后|接着|之后|回家后|家长)?\\s*(每天|每日|连续[0-9一二三四五六七十]+天).{0,16}(刷题|做题|作业|练习|打卡|做.{0,8}题)",
)
private val MANDATORY_ASSIGNMENT_TITLE = Regex("^(?:然后|接着|之后|回家后|家长)?\\s*(必须|强制|要求孩子).{0,16}(完成|刷题|做题|打卡|练习)")
private val ENGLISH_DAILY_ASSIGNMENT_TITLE = Regex(
    "^(?:then\\s+)?(daily|every day|mandatory|must).{0,32}(worksheet|homework|drill|quiz|check-in)",
)

/** Explicit offline demo. It must never be presented as AI output. */
class LocalDemoOpportunitySource : OpportunitySource {
    override val id = "org.jianyu.local-demo"
    override val kind = "offline-demo"
    override val acceptedDataCategories = setOf("current-interest")

    override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
        capability.requireValid(id, kind, context.purpose, setOf("current-interest"))
        val subject = context.currentInterest.trim().take(36)
        val token = UUID.randomUUID().toString().take(8)
        return listOf(
            opportunity(token, "existing", "顺着「$subject」继续看看", OpportunityEcosystems.EXISTING_INTEREST, 20, EnergyBand.LOW, 0, 0.76,
                "兴趣已经出现，不需要另造学习任务", "先沿着正在做的事继续；自然停顿时可以问一个真实问题，不急着讲课。"),
            opportunity(token, "making", "把「$subject」变成一个能动手的小版本", OpportunityEcosystems.MAKING, 45, EnergyBand.MEDIUM, 0, 0.70,
                "从喜欢走向制作，方向仍可随时调整", "用家里已有材料做一个粗糙版本，成品和知识覆盖都不是目标。"),
            opportunity(token, "people", "问问身边谁真的接触过「$subject」", OpportunityEcosystems.PEOPLE, 30, EnergyBand.LOW, 0, 0.65,
                "真实的人和经历可能比再看一段内容更有连接感", "要不要问、问谁和问什么，都可以由感兴趣的人决定。"),
            opportunity(token, "world", "在本来要出门时留意「$subject」的现实痕迹", OpportunityEcosystems.REAL_WORLD, 60, EnergyBand.MEDIUM, 20, 0.61,
                "把兴趣接回真实世界，但不专门制造课程", "只在本来要出门时顺便观察，不为完成教育目标额外奔波。"),
        )
    }

    private fun opportunity(
        token: String,
        id: String,
        title: String,
        ecosystem: String,
        minutes: Int,
        energy: EnergyBand,
        travel: Int,
        score: Double,
        whyNow: String,
        explanation: String,
    ) = Opportunity(
        opportunityId = "demo-$id-$token",
        title = title,
        explanation = explanation,
        whyNow = whyNow,
        ecosystem = ecosystem,
        primaryGoal = GoalOwner.CHILD,
        childPull = true,
        requirements = OpportunityRequirements(minutes, CostBand.FREE_EXISTING, energy, travel),
        sourceKind = "offline-demo-template",
        sourceTitle = "内置离线演示模板",
        verification = Verification.IDEA,
        riskLevel = RiskLevel.LOW,
        score = score,
    )
}

class LocalWorldBriefProvider : WorldBriefProvider {
    override val id = "org.jianyu.world-brief-fixture"

    override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief> {
        capability.requireValid(id, kind, "fetch-public-world-brief", setOf("public-world-query"))
        return emptyList()
    }
}

data class DiscoveryResult(
    val opportunities: OpportunitySet,
    val disclosure: ContextDisclosureReceipt,
    val sourceDisclosures: Map<String, ContextDisclosureReceipt>,
    val sourceKinds: List<String>,
    val sourceIssues: List<DiscoverySourceIssue> = emptyList(),
)

data class DiscoverySourceIssue(
    val sourceId: String,
    val sourceKind: String,
    val reasonCode: String = "provider-unavailable-or-invalid-response",
)

class FamilyOpportunityEngine(
    private val policy: OpportunityPolicy,
    private val firewall: ContextFirewall = DefaultContextFirewall(),
) {
    /** Conservative approved scope before a public-world fetch can add data to the task context. */
    fun previewSourceDisclosure(
        request: OpportunityDiscoveryRequest,
        source: OpportunitySource,
        worldBriefMayContribute: Boolean,
    ): ContextDisclosureReceipt {
        val base = firewall.minimize(request, emptyList()).receipt
        val potential = base.copy(includedCategories = (
            base.includedCategories + if (worldBriefMayContribute) listOf("public-world-briefs") else emptyList()
        ).distinct())
        return potential.forSource(source)
    }

    suspend fun discover(
        request: OpportunityDiscoveryRequest,
        sources: List<OpportunitySource>,
        worldBriefProvider: WorldBriefProvider? = null,
        approvedCategoriesBySource: Map<String, Set<String>>? = null,
        approvedWorldQuery: PublicWorldQuery? = null,
    ): DiscoveryResult {
        require(sources.isNotEmpty()) { "At least one opportunity source is required" }
        require(sources.map { it.id }.distinct().size == sources.size) { "Opportunity source IDs must be unique" }
        val sourceIssues = mutableListOf<DiscoverySourceIssue>()
        val hasCurrentChildClue = currentInterestHasNonRefusalClue(request.currentInterest)
        val worldQuery = PublicWorldQuery(region = request.region, timeWindow = "next-14-days")
        val worldBriefs = worldBriefProvider?.let { provider ->
            if (approvedWorldQuery != worldQuery) {
                sourceIssues += DiscoverySourceIssue(provider.id, provider.kind, "outside-approved-scope")
                emptyList()
            } else if (!hasCurrentChildClue) {
                sourceIssues += DiscoverySourceIssue(provider.id, provider.kind, "no-current-child-pull")
                emptyList()
            } else {
                try {
                    provider.fetch(
                        worldQuery,
                        issueProviderCapability(
                            providerId = provider.id,
                            kind = provider.kind,
                            purpose = "fetch-public-world-brief",
                            dataCategories = listOf("public-world-query"),
                        ),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: SourceFailureException) {
                    sourceIssues += DiscoverySourceIssue(provider.id, provider.kind, failure.reason.code)
                    emptyList()
                } catch (_: Exception) {
                    sourceIssues += DiscoverySourceIssue(provider.id, provider.kind)
                    emptyList()
                }
            }
        }.orEmpty()
        val minimized = firewall.minimize(request, worldBriefs)
        val sourceDisclosures = sources.associate { source -> source.id to minimized.receipt.forSource(source) }
        val successfulSourceKinds = mutableListOf<String>()
        val candidates = buildList {
            sources.forEach { source ->
                val sourceDisclosure = sourceDisclosures.getValue(source.id)
                if (approvedCategoriesBySource != null && (
                    source.id !in approvedCategoriesBySource ||
                        !approvedCategoriesBySource.getValue(source.id).containsAll(sourceDisclosure.includedCategories)
                )) {
                    sourceIssues += DiscoverySourceIssue(source.id, source.kind, "outside-approved-scope")
                    return@forEach
                }
                if (!hasCurrentChildClue) {
                    sourceIssues += DiscoverySourceIssue(source.id, source.kind, "no-current-child-pull")
                    return@forEach
                }
                try {
                    addAll(
                        source.discover(
                            minimized.context.restrictTo(sourceDisclosure.includedCategories.toSet()),
                            issueProviderCapability(
                                providerId = source.id,
                                kind = source.kind,
                                purpose = minimized.context.purpose,
                                dataCategories = sourceDisclosure.includedCategories,
                            ),
                        ).map { candidate ->
                            candidate.copy(sourceKind = when {
                                source is LLMProvider -> "byok-llm"
                                source.kind == "offline-demo" -> "offline-demo-template"
                                else -> source.kind
                            })
                        },
                    )
                    successfulSourceKinds += source.kind
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: SourceFailureException) {
                    sourceIssues += DiscoverySourceIssue(source.id, source.kind, failure.reason.code)
                } catch (_: Exception) {
                    sourceIssues += DiscoverySourceIssue(source.id, source.kind)
                }
            }
        }
        val evaluated = candidates
            .map { rawOpportunity ->
                val opportunity = rawOpportunity.copy(
                    ecosystem = OpportunityEcosystems.canonical(rawOpportunity.ecosystem),
                )
                val decision = policy.evaluate(opportunity, request)
                EvaluatedOpportunity(opportunity, decision.allowed, decision.reasons, decision.warnings)
            }
        val selected = selectDiverseOpportunities(evaluated.filter { it.allowed })
        val eligibleNotSelected = evaluated.filter { candidate ->
            candidate.allowed && selected.none { chosen -> chosen === candidate }
        }
        return DiscoveryResult(
            opportunities = OpportunitySet(
                selected = selected,
                rejected = evaluated.filterNot { it.allowed },
                eligibleNotSelected = eligibleNotSelected,
                nothing = Opportunity(
                    opportunityId = "nothing:family-choice",
                    title = "这次什么都不做",
                    explanation = "兴趣可以只是兴趣。这次停在这里，不会破坏任何进度。",
                    whyNow = "不行动也是家庭的正式选择。",
                    ecosystem = OpportunityEcosystems.NOTHING,
                    primaryGoal = GoalOwner.SHARED,
                    childPull = true,
                    requirements = OpportunityRequirements(0, CostBand.FREE_EXISTING, EnergyBand.NONE, 0),
                    sourceKind = "family-choice",
                    sourceTitle = "家庭自主选择",
                    verification = Verification.VERIFIED,
                    riskLevel = RiskLevel.LOW,
                    score = 0.0,
                    confidence = 1.0,
                    isNothing = true,
                ),
            ),
            disclosure = minimized.receipt,
            sourceDisclosures = sourceDisclosures,
            sourceKinds = successfulSourceKinds.distinct(),
            sourceIssues = sourceIssues.distinct(),
        )
    }
}

private fun ContextDisclosureReceipt.forSource(source: OpportunitySource): ContextDisclosureReceipt {
    val accepted = includedCategories.filter { it in source.acceptedDataCategories }
    return ContextDisclosureReceipt(
        purpose = purpose,
        includedCategories = accepted,
        excludedCategories = (excludedCategories + includedCategories.filterNot { it in accepted }).distinct(),
    )
}

/** Source turns prevent a model's self-reported score from becoming a quality ranking or crowding out other feasible sources. */
private fun selectDiverseOpportunities(allowed: List<EvaluatedOpportunity>): List<EvaluatedOpportunity> {
    val sourceTurns = allowed.groupBy { it.opportunity.sourceKind }.values.map { it.iterator() }
    val ecosystems = mutableSetOf<String>()
    val entrySignatures = mutableSetOf<Pair<String, String>>()
    val selected = mutableListOf<EvaluatedOpportunity>()
    val deferredCaregiver = ArrayDeque<EvaluatedOpportunity>()
    var childOrSharedCount = 0
    var caregiverCount = 0
    fun selectIfDistinct(item: EvaluatedOpportunity): Boolean {
        if (item.opportunity.ecosystem in ecosystems) return false
        val signature = item.opportunity.entrySignature()
        if (signature != null && signature in entrySignatures) return false
        ecosystems += item.opportunity.ecosystem
        signature?.let(entrySignatures::add)
        selected += item
        return true
    }
    fun admitDeferredCaregiver() {
        while (selected.size < 5 && caregiverCount < childOrSharedCount && deferredCaregiver.isNotEmpty()) {
            if (selectIfDistinct(deferredCaregiver.removeFirst())) caregiverCount++
        }
    }
    while (selected.size < 5 && sourceTurns.any { it.hasNext() }) {
        for (turn in sourceTurns) {
            if (selected.size == 5) break
            while (turn.hasNext()) {
                val item = turn.next()
                if (item.opportunity.primaryGoal == GoalOwner.CAREGIVER) {
                    deferredCaregiver.addLast(item)
                    break
                }
                if (selectIfDistinct(item)) {
                    childOrSharedCount++
                    admitDeferredCaregiver()
                    break
                }
            }
        }
    }
    admitDeferredCaregiver()
    return selected
}

/** Suppress only clear title re-labels; distinct original URLs remain distinct sourced items. */
private fun Opportunity.entrySignature(): Pair<String, String>? {
    val normalizedTitle = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\p{P}\\p{Z}\\s]+"), "")
    if (normalizedTitle.length < 4) return null
    return normalizedTitle to sourceUrl?.trim().orEmpty()
}

private fun TaskContext.restrictTo(categories: Set<String>) = TaskContext(
    purpose = purpose,
    ageBand = if ("age-band" in categories) ageBand else "not-disclosed",
    lifecycleStage = lifecycleStage.takeIf { "lifecycle-stage" in categories },
    currentInterest = currentInterest.takeIf { "current-interest" in categories }.orEmpty(),
    childGoal = childGoal.takeIf { "declared-goals" in categories }.orEmpty(),
    caregiverGoal = caregiverGoal.takeIf { "declared-goals" in categories },
    sharedGoal = sharedGoal.takeIf { "declared-goals" in categories },
    schoolWindow = schoolWindow.takeIf { "school-window" in categories },
    lifeContext = lifeContext.takeIf { "life-context" in categories },
    region = region.takeIf { "coarse-region" in categories },
    constraints = if ("practical-constraints" in categories) constraints else FamilyConstraints(
        timeMinutes = 0,
        costBand = CostBand.FREE_EXISTING,
        caregiverEnergy = EnergyBand.NONE,
        travelMinutesMax = 0,
    ),
    recentEvidenceSummaries = recentEvidenceSummaries.takeIf { "recent-evidence-summaries" in categories }.orEmpty(),
    publicWorldBriefs = publicWorldBriefs.takeIf { "public-world-briefs" in categories }.orEmpty(),
    recentInterventionCount = recentInterventionCount.takeIf { "recent-intervention-count" in categories },
)
