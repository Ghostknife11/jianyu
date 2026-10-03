package org.jianyu.core.model

const val CURRENT_FAMILY_STATE_SCHEMA = "org.jianyu.family-vault/v5"

fun migrateFamilyState(state: FamilyState): FamilyState = when (state.schema) {
    CURRENT_FAMILY_STATE_SCHEMA -> state
    "org.jianyu.family-vault/v2", "org.jianyu.family-vault/v3", "org.jianyu.family-vault/v4" -> state.copy(
        schema = CURRENT_FAMILY_STATE_SCHEMA,
        evidence = state.evidence,
        hypotheses = state.hypotheses,
        tombstones = state.tombstones,
        syncState = state.syncState,
    )
    else -> error("Unsupported family state schema: ${state.schema}")
}
