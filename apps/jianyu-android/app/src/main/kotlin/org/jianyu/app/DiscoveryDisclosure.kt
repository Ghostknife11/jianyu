package org.jianyu.app

import org.jianyu.core.domain.ContextDisclosureReceipt

/** Public-world query categories; the region value is free text and is not verified as coarse. */
internal fun publicWorldQueryDisclosure(regionAsEntered: String?): ContextDisclosureReceipt = ContextDisclosureReceipt(
    purpose = "fetch-public-world-brief",
    includedCategories = buildList {
        if (!regionAsEntered.isNullOrBlank()) add("coarse-region")
        add("public-time-window")
        add("language")
        add("public-categories")
    },
    excludedCategories = listOf(
        "current-interest",
        "life-context",
        "recent-evidence-summaries",
        "names",
        "full-family-history",
        "exact-location",
        "provider-secrets",
    ),
)
