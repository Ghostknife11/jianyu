package org.jianyu.core.model

import kotlinx.serialization.Serializable

@Serializable
data class FamilyState(
    val schema: String = "org.jianyu.family-vault/v5",
    val household: Household,
    val members: List<FamilyMember>,
    val children: List<Child>,
    val evidence: List<Evidence> = emptyList(),
    val hypotheses: List<Hypothesis> = emptyList(),
    val events: List<FamilyEvent> = emptyList(),
    val choices: List<FamilyChoice> = emptyList(),
    val tombstones: List<DeletionTombstone> = emptyList(),
    val syncState: FamilySyncState = FamilySyncState(),
)

@Serializable
data class Household(
    val id: String,
    val name: String,
    val createdAt: String,
)

@Serializable
data class FamilyMember(
    val id: String,
    val displayName: String,
    val role: MemberRole,
    val subjectId: String? = null,
    val createdAt: String,
)

@Serializable
enum class MemberRole { CAREGIVER, GUARDIAN, OBSERVER, CHILD }

@Serializable
enum class ContextStream { CHILD, SCHOOL, LIFE, WORLD }

@Serializable
enum class EvidenceKind {
    CHILD_STATED,
    CHILD_CHOICE,
    DIRECT_OBSERVATION,
    CAREGIVER_INTERPRETATION,
    TEACHER_FEEDBACK,
    ASSESSMENT,
    IMPORTED_CLAIM,
    AI_INFERENCE,
}

@Serializable
enum class EvidenceVisibility { GUARDIANS, SHARED_WITH_CHILD, CHILD_PRIVATE }

@Serializable
data class Evidence(
    val schema: String = "org.foe.evidence/v1",
    val id: String,
    val childId: String,
    val authorId: String,
    val stream: ContextStream,
    val kind: EvidenceKind,
    val expression: String,
    val occurredAt: String,
    val recordedAt: String,
    val confidence: Double = 1.0,
    val ownerId: String,
    val visibility: EvidenceVisibility,
    val source: String = "family-input",
)

@Serializable
data class Hypothesis(
    val schema: String = "org.foe.hypothesis/v1",
    val id: String,
    val childId: String,
    val statement: String,
    val confidence: Double,
    val supports: List<String>,
    val contradicts: List<String> = emptyList(),
    val modelId: String,
    val policyVersion: String,
    val derivedAt: String,
    val halfLifeDays: Int = 45,
    val status: String = "active",
)

@Serializable
data class Child(
    val id: String,
    val memberId: String,
    val displayName: String,
    val birthYear: Int,
    val createdAt: String,
    /** ISO-8601 local date. Null keeps vaults created before exact birthdays were introduced readable. */
    val birthDate: String? = null,
)

@Serializable
data class FamilyEvent(
    val schema: String = "org.foe.event/v1",
    val eventId: String,
    val eventType: String,
    val eventVersion: Int = 1,
    val householdId: String,
    val authorId: String,
    val actorRole: MemberRole,
    val subjectId: String? = null,
    val deviceId: String,
    val occurredAt: String,
    val recordedAt: String,
    val visibility: String,
    val payload: Map<String, String>,
)

@Serializable
data class FamilyGoals(
    val child: String,
    val caregiver: String? = null,
    val shared: String? = null,
)

@Serializable
data class FamilyConstraints(
    val timeMinutes: Int = 90,
    val costBand: CostBand = CostBand.LOW,
    val caregiverEnergy: EnergyBand = EnergyBand.MEDIUM,
    val travelMinutesMax: Int = 30,
)

@Serializable
enum class CostBand { FREE_EXISTING, FREE, LOW, MEDIUM, HIGH }

@Serializable
enum class EnergyBand { NONE, LOW, MEDIUM, HIGH }

@Serializable
enum class GoalOwner { CHILD, CAREGIVER, SHARED }

@Serializable
enum class Verification { VERIFIED, LIKELY, IDEA }

@Serializable
enum class RiskLevel { LOW, MEDIUM, HIGH, UNKNOWN }

@Serializable
data class WorldBrief(
    val schema: String = "org.foe.world-brief/v1",
    val id: String,
    val title: String,
    val summary: String,
    val topics: List<String> = emptyList(),
    val region: String,
    val startsAt: String? = null,
    val expiresAt: String? = null,
    val sourceTitle: String,
    val sourceUrl: String? = null,
    val retrievedAt: String,
    val verification: Verification,
    val timeMinutes: Int = 60,
    val costBand: CostBand = CostBand.LOW,
    val caregiverEnergy: EnergyBand = EnergyBand.MEDIUM,
    val travelMinutes: Int = 30,
    val minAge: Int = 4,
    val maxAge: Int = 15,
    val bookingRequired: Boolean = false,
    val verificationNotes: List<String> = emptyList(),
    val sponsorship: String? = null,
    val trackingWarning: Boolean = false,
    /** Optional public terms in the requested language; private interest matching remains on-device. */
    val matchTerms: List<String> = emptyList(),
)

@Serializable
data class OpportunityRequirements(
    val timeMinutes: Int,
    val costBand: CostBand,
    val caregiverEnergy: EnergyBand,
    val travelMinutes: Int,
)

@Serializable
data class Opportunity(
    val schema: String = "org.foe.opportunity/v1",
    val opportunityId: String,
    val title: String,
    val explanation: String,
    val whyNow: String,
    val ecosystem: String,
    val primaryGoal: GoalOwner,
    val childPull: Boolean,
    val requirements: OpportunityRequirements,
    val sourceKind: String,
    val verification: Verification,
    val score: Double,
    val isNothing: Boolean = false,
    val servedGoals: List<GoalOwner> = listOf(primaryGoal),
    val sourceTitle: String? = null,
    val sourceUrl: String? = null,
    val retrievedAt: String? = null,
    val expiresAt: String? = null,
    val riskLevel: RiskLevel = RiskLevel.UNKNOWN,
    val confidence: Double = score,
    val minAge: Int = 4,
    val maxAge: Int = 15,
    val naturalEntry: Boolean = true,
    val interventionPressure: RiskLevel = RiskLevel.LOW,
    val bookingRequired: Boolean = false,
    val verificationNotes: List<String> = emptyList(),
    val sponsorship: String? = null,
    val trackingWarning: Boolean = false,
)

@Serializable
data class EvaluatedOpportunity(
    val opportunity: Opportunity,
    val allowed: Boolean,
    val reasons: List<String>,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class OpportunitySet(
    val selected: List<EvaluatedOpportunity>,
    val rejected: List<EvaluatedOpportunity>,
    val nothing: Opportunity,
    /** Passed the Gate but was not displayed by the replaceable diversity selector. */
    val eligibleNotSelected: List<EvaluatedOpportunity> = emptyList(),
)

@Serializable
data class FamilyChoice(
    val id: String,
    val childId: String,
    val opportunity: Opportunity,
    val sourceEventId: String,
    val status: String,
    val chosenAt: String,
    val feedback: String? = null,
)

@Serializable
enum class LifecycleStage(val label: String) {
    CO_PLAY("共玩"),
    ACCOMPANY("陪伴"),
    CO_SELECT("共选"),
    HAND_OVER("放权"),
    GRADUATION("Graduation"),
}
