package org.jianyu.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jianyu.core.domain.DefaultOpportunityPolicy
import org.jianyu.core.domain.FamilyOpportunityEngine
import org.jianyu.core.domain.currentInterestHasNonRefusalClue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays the public fictional corpus through the production Android parser, Gate and selector. */
class PublicBenchmarkPipelineTest {
    private val settings = AiProviderSettings(
        providerName = "虚构服务", baseUrl = "https://example.test/v1",
        model = "synthetic-model", apiKey = "synthetic-key",
    )

    @Test
    fun `all six fictional responses reach the actual Android decision pipeline`() = runBlocking {
        val cases = publicBenchmarkCases()
        assertEquals(6, cases.size)
        val responses = Json.parseToJsonElement(publicBenchmarkFile("example-run.synthetic.json").readText())
            .jsonObject.getValue("responses").jsonObject
        assertEquals(cases.map { it.id }.toSet(), responses.keys)

        for (benchmarkCase in cases) {
            var calls = 0
            val source = OpenAiCompatibleOpportunitySource(
                loadSettings = { settings },
                complete = { received, system, prompt, temperature ->
                    calls++
                    assertEquals(settings, received)
                    assertTrue(system.contains("AI 只负责理解和发散"))
                    assertEquals(0.65, temperature, 0.0)
                    val outboundContext = prompt.substringAfter("<family-context-json>\n")
                        .substringBefore("\n</family-context-json>").trim()
                    assertEquals(benchmarkCase.context, Json.parseToJsonElement(outboundContext))
                    responses.getValue(benchmarkCase.id).toString()
                },
            )
            val result = FamilyOpportunityEngine(DefaultOpportunityPolicy())
                .discover(benchmarkCase.request, listOf(source))
            val hasChildClue = currentInterestHasNonRefusalClue(benchmarkCase.request.currentInterest)
            assertEquals("Unexpected AI call count: ${benchmarkCase.id}", if (hasChildClue) 1 else 0, calls)
            assertTrue("Nothing missing: ${benchmarkCase.id}", result.opportunities.nothing.isNothing)
            if (benchmarkCase.requiresEmpty) {
                assertTrue("Should keep this occasion empty: ${benchmarkCase.id}", result.opportunities.selected.isEmpty())
            } else {
                assertFalse("Lost positive child-led entrance: ${benchmarkCase.id}", result.opportunities.selected.isEmpty())
                assertTrue(result.opportunities.selected.all { it.opportunity.sourceKind == "byok-llm" })
            }
            if (hasChildClue) {
                assertTrue("Source failed: ${benchmarkCase.id}", result.sourceIssues.isEmpty())
            } else {
                assertEquals("no-current-child-pull", result.sourceIssues.single().reasonCode)
            }
            when (benchmarkCase.id) {
                "motorsport-multiple-doors" -> assertEquals(
                    setOf("making", "people"),
                    result.opportunities.selected.map { it.opportunity.ecosystem }.toSet(),
                )
                "unverified-city-event" -> assertEquals(
                    listOf("media"), result.opportunities.selected.map { it.opportunity.ecosystem },
                )
            }
        }
    }

    @Test
    fun `zero minute suggestion cannot defeat an explicit no time boundary`() = runBlocking {
        val benchmarkCase = publicBenchmarkCases().single { it.id == "no-time-no-energy" }
        val response = """{"opportunities":[{
            "title":"现在抬头看星星","explanation":"马上看一眼，不需要家长帮忙。",
            "whyNow":"孩子偶尔会看星星。","ecosystem":"nature","primaryGoal":"CHILD",
            "childPull":true,"timeMinutes":0,"costBand":"FREE_EXISTING",
            "caregiverEnergy":"NONE","travelMinutes":0,"confidence":0.8,
            "minAge":7,"maxAge":12,"naturalEntry":true,"interventionPressure":"LOW"
        }]}""".trimIndent()
        val source = OpenAiCompatibleOpportunitySource(
            loadSettings = { settings }, complete = { _, _, _, _ -> response },
        )

        val result = FamilyOpportunityEngine(DefaultOpportunityPolicy())
            .discover(benchmarkCase.request, listOf(source))

        assertTrue(result.opportunities.selected.isEmpty())
        assertTrue("no-available-time" in result.opportunities.rejected.single().reasons)
        assertTrue("invalid-duration" in result.opportunities.rejected.single().reasons)
        assertTrue(result.opportunities.nothing.isNothing)
    }
}
