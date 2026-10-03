package org.jianyu.core.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicAiProbeCapabilityTest {
    @Test
    fun `public probe grant is short lived and cannot authorize family discovery`() {
        val grant = PublicAiProbeCapability.issueForExplicitCheck()
        grant.requireValid(
            PublicAiProbeCapability.PROVIDER_ID,
            PublicAiProbeCapability.KIND,
            PublicAiProbeCapability.PURPOSE,
            setOf(PublicAiProbeCapability.DATA_CATEGORY),
        )
        assertTrue(grant.dataCategories == setOf(PublicAiProbeCapability.DATA_CATEGORY))
        assertFalse(grant.dataCategories.contains("current-interest"))
        assertTrue(runCatching {
            grant.requireValid("org.foe.openai-compatible", "byok-llm", "discover-family-opportunities")
        }.isFailure)
    }
}
