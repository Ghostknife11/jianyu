package org.jianyu.core.data

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jianyu.core.domain.OpportunityDiscoveryRequest
import org.jianyu.core.model.CostBand
import org.jianyu.core.model.EnergyBand
import org.jianyu.core.model.FamilyConstraints
import org.jianyu.core.model.FamilyGoals
import org.jianyu.core.model.LifecycleStage

/** The public fictional corpus is shared by prompt-parity and actual Android pipeline tests. */
internal data class PublicBenchmarkCase(
    val id: String,
    val context: JsonObject,
    val request: OpportunityDiscoveryRequest,
    val requiresEmpty: Boolean,
)

internal fun publicBenchmarkFile(name: String): File =
    generateSequence(File(System.getProperty("user.dir") ?: error("Working directory is unavailable")).canonicalFile) { it.parentFile }
        .map { File(it, "models/benchmark/$name") }
        .firstOrNull(File::isFile)
        ?: error("Public benchmark file $name not found above ${System.getProperty("user.dir")}")

internal fun publicBenchmarkCases(): List<PublicBenchmarkCase> {
    val corpus = Json.parseToJsonElement(publicBenchmarkFile("v0.1-cases.json").readText()).jsonObject
    require(corpus.getValue("schema").jsonPrimitive.content == "org.foe.public-model-benchmark/v1")
    return corpus.getValue("cases").jsonArray.map { element ->
        val item = element.jsonObject
        val context = item.getValue("context").jsonObject
        val goals = context.getValue("goals").jsonObject
        val limits = context.getValue("constraints").jsonObject
        val stageLabel = context.getValue("lifecycleStage").jsonPrimitive.content
        PublicBenchmarkCase(
            id = item.getValue("id").jsonPrimitive.content,
            context = context,
            requiresEmpty = item.getValue("expectation").jsonObject.getValue("requiresEmpty").jsonPrimitive.boolean,
            request = OpportunityDiscoveryRequest(
                age = item.getValue("age").jsonPrimitive.int,
                lifecycleStage = LifecycleStage.entries.single { it.label == stageLabel },
                currentInterest = context.getValue("currentInterest").jsonPrimitive.content,
                goals = FamilyGoals(
                    child = goals.getValue("child").jsonPrimitive.content,
                    caregiver = goals.getValue("caregiver").jsonPrimitive.content.optionalBenchmarkInput(),
                    shared = goals.getValue("shared").jsonPrimitive.content.optionalBenchmarkInput(),
                ),
                constraints = FamilyConstraints(
                    timeMinutes = limits.getValue("timeMinutesMax").jsonPrimitive.int,
                    costBand = CostBand.valueOf(limits.getValue("costBandMax").jsonPrimitive.content),
                    travelMinutesMax = limits.getValue("travelMinutesMax").jsonPrimitive.int,
                    caregiverEnergy = EnergyBand.valueOf(limits.getValue("caregiverEnergyMax").jsonPrimitive.content),
                ),
                schoolWindow = context.getValue("schoolWindow").jsonPrimitive.content.optionalBenchmarkInput(),
                lifeContext = context.getValue("lifeContext").jsonPrimitive.content.optionalBenchmarkInput(),
                region = context.getValue("regionAsEntered").jsonPrimitive.content.optionalBenchmarkInput(),
                recentEvidence = context.getValue("recentEvidenceSummaries").jsonArray.map { it.jsonPrimitive.content },
            ),
        )
    }
}

private fun String.optionalBenchmarkInput(): String? = takeUnless { it == "未提供" }
