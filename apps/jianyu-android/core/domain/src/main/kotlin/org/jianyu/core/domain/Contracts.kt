package org.jianyu.core.domain

import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyGoals
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.Opportunity
import org.jianyu.core.model.WorldBrief
import java.time.Instant
import java.util.UUID

data class OpportunityDiscoveryRequest(
    val currentInterest: String,
    val age: Int,
    val lifecycleStage: LifecycleStage,
    val goals: FamilyGoals,
    val constraints: FamilyConstraints,
    val schoolWindow: String? = null,
    val lifeContext: String? = null,
    val region: String? = null,
    val recentEvidence: List<String> = emptyList(),
    /** Legacy field name; the current Android producer counts selections, not completed interventions. */
    val recentInterventionCount: Int = 0,
    val includeRecentSelectionCountInProviderContext: Boolean = false,
)

/** Purpose-scoped data that may leave the device after explicit family approval. */
class TaskContext internal constructor(
    val purpose: String = "discover-family-opportunities",
    val ageBand: String,
    val lifecycleStage: LifecycleStage?,
    val currentInterest: String,
    val childGoal: String,
    val caregiverGoal: String?,
    val sharedGoal: String?,
    val schoolWindow: String?,
    val lifeContext: String?,
    val region: String?,
    val constraints: FamilyConstraints,
    val recentEvidenceSummaries: List<String>,
    val publicWorldBriefs: List<WorldBrief>,
    val recentInterventionCount: Int?,
)

/** Short-lived bearer capability issued by the Engine for one provider and purpose. */
class ProviderCapability internal constructor(
    val id: String,
    val providerId: String,
    val kind: String,
    val purpose: String,
    val dataCategories: Set<String>,
    val expiresAt: Instant,
) {
    fun requireValid(
        expectedProviderId: String,
        expectedKind: String,
        expectedPurpose: String,
        requiredCategories: Set<String> = emptySet(),
        now: Instant = Instant.now(),
    ) {
        require(providerId == expectedProviderId) { "Provider capability target mismatch" }
        require(kind == expectedKind) { "Provider capability kind mismatch" }
        require(purpose == expectedPurpose) { "Provider capability purpose mismatch" }
        require(dataCategories.containsAll(requiredCategories)) { "Provider capability lacks required data category" }
        require(now.isBefore(expiresAt)) { "Provider capability expired" }
    }
}

internal fun issueProviderCapability(
    providerId: String,
    kind: String,
    purpose: String,
    dataCategories: Collection<String>,
    now: Instant = Instant.now(),
) = ProviderCapability(
    id = UUID.randomUUID().toString(),
    providerId = providerId,
    kind = kind,
    purpose = purpose,
    dataCategories = dataCategories.toSet(),
    expiresAt = now.plusSeconds(60),
)

data class ContextDisclosureReceipt(
    val purpose: String,
    val includedCategories: List<String>,
    val excludedCategories: List<String>,
)

data class FirewallResult(
    val context: TaskContext,
    val receipt: ContextDisclosureReceipt,
)

interface ContextFirewall {
    fun minimize(request: OpportunityDiscoveryRequest, worldBriefs: List<WorldBrief>): FirewallResult
}

interface OpportunitySource {
    val id: String
    val kind: String
    val acceptedDataCategories: Set<String> get() = emptySet()
    suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity>
}

/** Small, safe failure vocabulary for source status; never include a provider body or secret. */
enum class SourceFailureReason(val code: String) {
    RESPONSE_INCOMPLETE("response-incomplete"),
    RESPONSE_TIMED_OUT("response-timed-out"),
    AUTHENTICATION_REJECTED("authentication-rejected"),
    RATE_LIMITED("rate-limited"),
    REQUEST_REJECTED("request-rejected"),
    INVALID_RESPONSE("invalid-response"),
}

class SourceFailureException(val reason: SourceFailureReason) : Exception(reason.code)

interface LLMProvider : OpportunitySource {
    val modelId: String
    override val acceptedDataCategories: Set<String>
        get() = setOf(
            "age-band",
            "lifecycle-stage",
            "current-interest",
            "declared-goals",
            "practical-constraints",
            "school-window",
            "life-context",
            "coarse-region",
            "recent-evidence-summaries",
            "recent-intervention-count",
        )
}

data class PublicWorldQuery(
    val region: String?,
    val timeWindow: String?,
    val language: String = "zh-CN",
    val categories: List<String> = listOf("events", "culture", "sport", "science", "nature"),
)

interface WorldBriefProvider {
    val id: String
    val kind: String get() = "world-brief"
    suspend fun fetch(query: PublicWorldQuery, capability: ProviderCapability): List<WorldBrief>
}

data class BrandConfig(
    val id: String,
    val displayName: String,
    val romanizedName: String,
    val engineName: String,
    val tagline: String,
    val hero: String,
    val mission: String,
    val privacyUrl: String? = null,
    val sourceUrl: String? = null,
    val features: Map<String, Boolean> = emptyMap(),
    val defaultPolicies: Map<String, String> = emptyMap(),
)

interface OpportunityPolicy {
    val id: String
    val version: String
    fun evaluate(opportunity: Opportunity, request: OpportunityDiscoveryRequest): PolicyDecision
}

data class PolicyDecision(
    val allowed: Boolean,
    val reasons: List<String>,
    val warnings: List<String> = emptyList(),
)

interface VaultRepository {
    suspend fun load(): FamilyState?
    suspend fun save(state: FamilyState)
    suspend fun erase()
    suspend fun exportEncrypted(): ByteArray
    suspend fun importEncrypted(bytes: ByteArray)
}
