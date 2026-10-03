package org.jianyu.core.domain

/** Narrow, short-lived authority for an explicitly initiated public synthetic AI probe. */
object PublicAiProbeCapability {
    const val PROVIDER_ID = "org.foe.openai-compatible-public-probe"
    const val KIND = "byok-llm-public-probe"
    const val PURPOSE = "check-public-ai-capability"
    const val DATA_CATEGORY = "fixed-public-synthetic-case"

    fun issueForExplicitCheck(): ProviderCapability = issueProviderCapability(
        providerId = PROVIDER_ID,
        kind = KIND,
        purpose = PURPOSE,
        dataCategories = setOf(DATA_CATEGORY),
    )
}
