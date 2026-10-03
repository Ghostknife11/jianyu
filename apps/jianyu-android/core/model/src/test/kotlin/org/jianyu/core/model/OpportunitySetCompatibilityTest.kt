package org.jianyu.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpportunitySetCompatibilityTest {
    private val nothing = Opportunity(
        opportunityId = "nothing:test",
        title = "这次什么都不做",
        explanation = "留白也可以",
        whyNow = "家庭自己选择",
        ecosystem = "nothing",
        primaryGoal = GoalOwner.SHARED,
        childPull = true,
        requirements = OpportunityRequirements(0, CostBand.FREE_EXISTING, EnergyBand.NONE, 0),
        sourceKind = "family-choice",
        verification = Verification.VERIFIED,
        score = 0.0,
        isNothing = true,
    )

    @Test
    fun `older result without selector omissions decodes with an empty eligible list`() {
        val legacy = """{"selected":[],"rejected":[],"nothing":${Json.encodeToString(nothing)}}"""
        val decoded = Json.decodeFromString<OpportunitySet>(legacy)
        assertTrue(decoded.eligibleNotSelected.isEmpty())
        assertEquals(nothing, decoded.nothing)
    }

    @Test
    fun `selector omissions remain distinct from Gate rejections on round trip`() {
        val eligible = EvaluatedOpportunity(
            opportunity = nothing.copy(opportunityId = "adult:fixture", isNothing = false, primaryGoal = GoalOwner.CAREGIVER),
            allowed = true,
            reasons = listOf("fits-time"),
        )
        val result = OpportunitySet(emptyList(), emptyList(), nothing, listOf(eligible))
        val decoded = Json.decodeFromString<OpportunitySet>(Json.encodeToString(result))
        assertEquals(listOf(eligible), decoded.eligibleNotSelected)
        assertTrue(decoded.rejected.isEmpty())
    }
}
