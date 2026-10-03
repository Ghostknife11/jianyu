package org.jianyu.core.domain

import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.GoalOwner
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.OpportunityPack
import org.jianyu.core.model.PackedEntryPoint
import org.jianyu.core.model.PackedGoalAlignment
import org.jianyu.core.model.PackedOpportunity
import org.jianyu.core.model.PackedRequirements
import org.jianyu.core.model.OpportunityRequirements
import org.jianyu.core.model.RiskLevel
import org.jianyu.core.model.Verification
import java.time.Instant

class DeclarativePackOpportunitySource(
    private val packs: List<OpportunityPack>,
) : OpportunitySource {
    override val id = "org.foe.pack-source"
    override val kind = "pack"
    override val acceptedDataCategories = setOf("age-band", "current-interest")

    override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
        capability.requireValid(id, kind, context.purpose, setOf("age-band", "current-interest"))
        val currentInterest = context.currentInterest.trim()

        return packs.flatMap { pack ->
            pack.opportunities
                .asSequence()
                .filter { entry -> context.ageBand.overlaps(entry.minAge, entry.maxAge) }
                .filter { entry -> entry.matchesCurrentInterest(currentInterest) }
                .map { entry -> entry.toOpportunity(pack) }
                .toList()
        }
    }

    /**
     * Pack entries are supplemental authored mappings, not generic fallback advice.
     * They therefore fail closed unless an author supplied a specific term that is
     * present in the family's current, explicit interest. Historical evidence is
     * intentionally excluded so an old interest cannot silently reactivate a Pack.
     */
    private fun PackedOpportunity.matchesCurrentInterest(currentInterest: String): Boolean {
        if (currentInterest.isBlank()) return false
        val terms = triggerTerms
            .asSequence()
            .map(String::trim)
            .filter { it.length >= 2 }
            .distinct()
            .toList()
        return terms.none { currentInterestRefusesTerm(currentInterest, it) } &&
            terms.any { currentInterestMentionsTerm(currentInterest, it) }
    }

    private fun PackedOpportunity.toOpportunity(pack: OpportunityPack) = Opportunity(
        opportunityId = "pack:${pack.id}:$opportunityId",
        title = title,
        explanation = explanation,
        whyNow = entryPoint.whyNow,
        ecosystem = OpportunityEcosystems.canonical(ecosystem),
        primaryGoal = goalAlignment.primary.toWireEnum(GoalOwner.CHILD),
        servedGoals = (listOf(goalAlignment.primary) + goalAlignment.secondary)
            .map { it.toWireEnum(GoalOwner.CHILD) }
            .distinct(),
        childPull = childPull,
        requirements = OpportunityRequirements(
            timeMinutes = requirements.timeMinutes,
            costBand = requirements.costBand.toWireEnum(CostBand.LOW),
            caregiverEnergy = requirements.caregiverEnergy.toWireEnum(EnergyBand.MEDIUM),
            travelMinutes = requirements.travelMinutes,
        ),
        sourceKind = "pack",
        sourceTitle = "${pack.title} · ${pack.publisher}",
        sourceUrl = source?.url,
        retrievedAt = source?.retrievedAt,
        verification = verification.toWireEnum(Verification.IDEA),
        riskLevel = risks.map { it.level.toWireEnum(RiskLevel.UNKNOWN) }.maxByOrNull { it.ordinal } ?: RiskLevel.LOW,
        score = score.coerceIn(0.0, 1.0),
        confidence = score.coerceIn(0.0, 1.0),
        minAge = minAge,
        maxAge = maxAge,
        naturalEntry = naturalEntry ?: childPull,
        interventionPressure = interventionPressure?.toWireEnum(RiskLevel.LOW)
            ?: if (childPull) RiskLevel.LOW else RiskLevel.HIGH,
    )

    private inline fun <reified T : Enum<T>> String.toWireEnum(fallback: T): T {
        val normalized = uppercase().replace('-', '_')
        return enumValues<T>().firstOrNull { it.name == normalized } ?: fallback
    }

    private fun String.overlaps(minAge: Int, maxAge: Int): Boolean {
        val bounds = split(Regex("[-–]")).mapNotNull(String::toIntOrNull)
        return bounds.size == 2 && bounds[0] <= maxAge && bounds[1] >= minAge
    }
}

/** Converts public-world records to candidates locally, after the privacy boundary. */
class WorldBriefOpportunitySource : OpportunitySource {
    override val id = "org.foe.world-brief-opportunity-source"
    override val kind = "world-brief"
    override val acceptedDataCategories = setOf("current-interest", "public-world-briefs")

    override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
        val requiredCategories = if (context.publicWorldBriefs.isEmpty()) {
            setOf("current-interest")
        } else {
            setOf("current-interest", "public-world-briefs")
        }
        capability.requireValid(id, kind, context.purpose, requiredCategories)
        if (context.publicWorldBriefs.isEmpty()) return emptyList()
        // A past veto or disliked outcome can mention the same topic. It is not
        // evidence of current child pull and must not reactivate a public brief.
        val currentInterest = context.currentInterest.trim()
        return context.publicWorldBriefs
            .filter { brief ->
                val terms = brief.topics + brief.matchTerms
                terms.none { currentInterestRefusesTerm(currentInterest, it) } &&
                    terms.any { currentInterestMentionsTerm(currentInterest, it) }
            }
            .map { brief ->
            Opportunity(
                opportunityId = "world:${brief.id}",
                title = brief.title,
                explanation = brief.summary,
                whyNow = "这是与当前时间和填写的地区相关的公共世界信息；是否适合仍由家庭现场判断。",
                ecosystem = OpportunityEcosystems.WORLD_EVENT,
                primaryGoal = GoalOwner.CHILD,
                childPull = true,
                requirements = OpportunityRequirements(
                    brief.timeMinutes,
                    brief.costBand,
                    brief.caregiverEnergy,
                    brief.travelMinutes,
                ),
                sourceKind = "world-brief",
                sourceTitle = brief.sourceTitle,
                sourceUrl = brief.sourceUrl,
                retrievedAt = brief.retrievedAt,
                expiresAt = brief.expiresAt,
                verification = brief.verification,
                riskLevel = RiskLevel.UNKNOWN,
                score = 0.60,
                confidence = if (brief.verification == Verification.VERIFIED) 0.78 else 0.52,
                minAge = brief.minAge,
                maxAge = brief.maxAge,
                bookingRequired = brief.bookingRequired,
                verificationNotes = brief.verificationNotes,
                sponsorship = brief.sponsorship,
                trackingWarning = brief.trackingWarning,
            )
        }
    }
}

/** Built-in generic starter knowledge. It is data behind the public Pack contract, not app-only logic. */
fun starterOpportunityPack() = OpportunityPack(
    id = "org.jianyu.starter.motorsport",
    version = "1.0.0",
    title = "从兴趣到现实：赛车",
    publisher = "内置合成示例",
    license = "Apache-2.0",
    opportunities = listOf(
        PackedOpportunity(
            opportunityId = "measure-coast",
            triggerTerms = listOf("赛车", "汽车", "轮胎", "卡丁车"),
            title = "找一段安全斜面，比较不同小车滑行的差别",
            explanation = "先保留预测、试错和争论，不急着把体验改写成物理课。",
            entryPoint = PackedEntryPoint("optimization", "孩子正在主动注意车、速度或调校。"),
            ecosystem = OpportunityEcosystems.MAKING,
            goalAlignment = PackedGoalAlignment("child"),
            childPull = true,
            requirements = PackedRequirements(35, "free-existing", "low", 0),
        ),
        PackedOpportunity(
            opportunityId = "ask-a-practitioner",
            triggerTerms = listOf("赛车", "汽车", "轮胎", "卡丁车"),
            title = "下次本来就要保养时，请孩子问师傅一个真问题",
            explanation = "把兴趣接到真实职业与真实机器；不为教育目的额外制造行程。",
            entryPoint = PackedEntryPoint("real-machines", "已有问题可以在自然生活场景里得到更真实的回应。"),
            ecosystem = OpportunityEcosystems.PEOPLE,
            goalAlignment = PackedGoalAlignment("child"),
            childPull = true,
            requirements = PackedRequirements(15, "free-existing", "low", 0),
        ),
    ),
)

/** Public-data-only fixture used by the explicit offline demo and conformance tests. */
class SyntheticWorldBriefProvider(
    private val clock: () -> Instant = Instant::now,
) : WorldBriefProvider {
    override val id = "org.foe.world-brief.synthetic"

    override suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<org.jianyu.core.model.WorldBrief> {
        capability.requireValid(id, kind, "fetch-public-world-brief", setOf("public-world-query"))
        return listOf(
            org.jianyu.core.model.WorldBrief(
            id = "synthetic-public-event",
            title = "查看${query.region ?: "所在城市"}近期公开活动（演示数据）",
            summary = "这是用于验证 World Brief 边界的合成记录，不代表真实活动；行动前必须自行查证。",
            topics = listOf("赛车", "汽车", "速度"),
            region = query.region ?: "未指定地区",
            sourceTitle = "合成世界信息（演示）",
            retrievedAt = clock().toString(),
            verification = Verification.IDEA,
            ),
        )
    }
}
