package org.jianyu.core.data

import kotlinx.coroutines.runBlocking
import org.jianyu.core.domain.DefaultContextFirewall
import org.jianyu.core.domain.DefaultOpportunityPolicy
import org.jianyu.core.domain.FamilyOpportunityEngine
import org.jianyu.core.domain.LLMProvider
import org.jianyu.core.domain.OpportunityDiscoveryRequest
import org.jianyu.core.domain.ProviderCapability
import org.jianyu.core.domain.PublicAiProbeCapability
import org.jianyu.core.domain.TaskContext
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyGoals
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.Opportunity
import java.util.concurrent.CancellationException

enum class AiCapabilityStatus {
    SAMPLE_PASSED,
    VALID_NOTHING,
    FORMAT_INCOMPATIBLE,
    RESPONSE_INCOMPLETE,
    RESPONSE_TIMED_OUT,
    LOCAL_CHECK_REJECTED,
    NO_DISPLAYABLE_DOORS,
    CONNECTION_FAILED,
    SETTINGS_UNAVAILABLE,
}

data class AiCapabilityResult(
    val status: AiCapabilityStatus,
    val acceptedCount: Int = 0,
    val httpStatus: Int? = null,
)

/**
 * A single, explicitly requested public synthetic probe. It never reads a vault or a real child.
 * Passing this sample is not a model recommendation, safety certification, or quality grade.
 */
class AiProviderCapabilityProbe(
    private val complete: (AiProviderSettings, String, String, Double) -> String = { settings, system, user, temperature ->
        OpenAiChatCompletions.request(settings, system, user, temperature)
    },
) {
    fun check(settings: AiProviderSettings, capability: ProviderCapability): AiCapabilityResult {
        capability.requireValid(
            PublicAiProbeCapability.PROVIDER_ID,
            PublicAiProbeCapability.KIND,
            PublicAiProbeCapability.PURPOSE,
            setOf(PublicAiProbeCapability.DATA_CATEGORY),
        )
        val request = syntheticRequest()
        val prompt = buildOpportunityTaskPrompt(DefaultContextFirewall().minimize(request, emptyList()).context)
        val content = try {
            complete(
                settings,
                OpenAiCompatibleOpportunitySource.OPPORTUNITY_SYSTEM_PROMPT,
                prompt,
                0.2,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return AiCapabilityResult(
                when (error) {
                    is AiChatIncompleteException -> AiCapabilityStatus.RESPONSE_INCOMPLETE
                    is AiChatTimeoutException -> AiCapabilityStatus.RESPONSE_TIMED_OUT
                    is AiChatResponseFormatException -> AiCapabilityStatus.FORMAT_INCOMPATIBLE
                    else -> AiCapabilityStatus.CONNECTION_FAILED
                },
                httpStatus = (error as? AiChatHttpException)?.status,
            )
        }
        val candidates = try {
            parseStructuredOpportunityContent(content, settings.providerName, settings.model)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return AiCapabilityResult(AiCapabilityStatus.FORMAT_INCOMPATIBLE)
        }
        if (candidates.isEmpty()) return AiCapabilityResult(AiCapabilityStatus.VALID_NOTHING)
        // Replay the already parsed public candidates through the same local Gate and selector as formal discovery.
        // This performs no second network call and uses no Family Vault data.
        val optionSet = runBlocking {
            FamilyOpportunityEngine(DefaultOpportunityPolicy()).discover(
                request = request,
                sources = listOf(object : LLMProvider {
                    override val id = "org.foe.public-sample-replay"
                    override val kind = "byok-llm"
                    override val modelId = settings.model
                    override suspend fun discover(context: TaskContext, capability: ProviderCapability): List<Opportunity> {
                        capability.requireValid(id, kind, context.purpose)
                        return candidates
                    }
                }),
            ).opportunities
        }
        return when {
            optionSet.selected.isNotEmpty() ->
                AiCapabilityResult(AiCapabilityStatus.SAMPLE_PASSED, optionSet.selected.size)
            optionSet.eligibleNotSelected.isNotEmpty() ->
                AiCapabilityResult(AiCapabilityStatus.NO_DISPLAYABLE_DOORS)
            else -> AiCapabilityResult(AiCapabilityStatus.LOCAL_CHECK_REJECTED)
        }
    }

    private fun syntheticRequest() = OpportunityDiscoveryRequest(
        currentInterest = "公开合成样例：一名虚构的 9 岁孩子最近主动折纸飞机，想试试怎样飞得更远。",
        age = 9,
        lifecycleStage = LifecycleStage.ACCOMPANY,
        goals = FamilyGoals(child = "自愿试着改一改纸飞机"),
        constraints = FamilyConstraints(
            timeMinutes = 30,
            costBand = CostBand.FREE,
            caregiverEnergy = EnergyBand.LOW,
            travelMinutesMax = 0,
        ),
    )
}
