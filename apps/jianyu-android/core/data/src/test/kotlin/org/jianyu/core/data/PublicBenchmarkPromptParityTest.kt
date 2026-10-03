package org.jianyu.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jianyu.core.domain.DefaultContextFirewall
import org.junit.Assert.assertEquals
import org.junit.Test

/** Keeps the public fictional corpus aligned with the actual per-call AI payload. */
class PublicBenchmarkPromptParityTest {
    @Test
    fun `every public case matches the production firewall and prompt payload`() {
        val cases = publicBenchmarkCases()
        assertEquals(6, cases.size)

        for (benchmarkCase in cases) {
            val minimized = DefaultContextFirewall().minimize(benchmarkCase.request, emptyList()).context
            val actual = buildOpportunityTaskPrompt(minimized)
                .substringAfter("<family-context-json>\n")
                .substringBefore("\n</family-context-json>")
                .trim()
            assertEquals(
                "Public benchmark payload drifted from the Android prompt: ${benchmarkCase.id}",
                benchmarkCase.context,
                Json.parseToJsonElement(actual) as JsonObject,
            )
        }
    }
}
