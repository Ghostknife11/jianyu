package org.jianyu.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Canonical FOE v1 declarative Pack shared with the public JavaScript SDK. */
@Serializable
data class OpportunityPack(
    val schema: String = "org.foe.pack/v1",
    val id: String,
    val version: String,
    val publisher: String,
    val license: String,
    val title: String = id,
    val language: String = "zh-CN",
    val contentDigest: String? = null,
    val signature: String? = null,
    val sponsorship: String? = null,
    val opportunities: List<PackedOpportunity>,
)

@Serializable
data class PackedOpportunity(
    val schema: String = "org.foe.opportunity/v1",
    val opportunityId: String,
    val title: String,
    val entryPoint: PackedEntryPoint,
    val ecosystem: String,
    val goalAlignment: PackedGoalAlignment,
    val childPull: Boolean,
    val requirements: PackedRequirements,
    val source: PackedSource? = null,
    val verification: String = "idea",
    val risks: List<PackedRisk> = emptyList(),
    val score: Double = 0.5,
    val explanation: String,
    val sponsorship: String? = null,
    val triggerTerms: List<String> = emptyList(),
    val minAge: Int = 4,
    val maxAge: Int = 15,
    val naturalEntry: Boolean? = null,
    val interventionPressure: String? = null,
)

@Serializable
data class PackedEntryPoint(
    val motivation: String,
    val whyNow: String,
    val startupCost: String = "low",
)

@Serializable
data class PackedGoalAlignment(
    val primary: String,
    val secondary: List<String> = emptyList(),
)

@Serializable
data class PackedRequirements(
    val timeMinutes: Int,
    val costBand: String,
    val caregiverEnergy: String,
    val travelMinutes: Int,
)

@Serializable
data class PackedSource(
    val kind: String,
    val publisher: String? = null,
    val url: String? = null,
    val retrievedAt: String? = null,
)

@Serializable
data class PackedRisk(
    val level: String,
    val kind: String,
)

object OpportunityPackCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun decode(value: String): OpportunityPack = json.decodeFromString(value)

    fun encode(pack: OpportunityPack): String = json.encodeToString(pack)
}
