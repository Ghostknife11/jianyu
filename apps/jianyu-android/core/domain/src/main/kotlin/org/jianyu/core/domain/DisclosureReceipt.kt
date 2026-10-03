package org.jianyu.core.domain

/** Produces an auditable receipt without copying any disclosed values or provider secrets. */
fun ContextDisclosureReceipt.toEventPayload(
    providerId: String,
    modelId: String,
    contextPersisted: Boolean,
): Map<String, String> = mapOf(
    "provider" to providerId,
    "model" to modelId,
    "purpose" to purpose,
    "includedCategories" to includedCategories.joinToString(","),
    "excludedCategories" to excludedCategories.joinToString(","),
    "rawValuesStored" to "false",
    "contextPersisted" to contextPersisted.toString(),
)
