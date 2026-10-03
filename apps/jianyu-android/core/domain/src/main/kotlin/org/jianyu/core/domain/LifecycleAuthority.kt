package org.jianyu.core.domain

import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.EvidenceVisibility

data class LifecycleAuthority(
    val allowsNewObservation: Boolean,
    val requiresChildConfirmation: Boolean,
    val decisionOwner: String,
)

fun lifecycleAuthority(stage: LifecycleStage): LifecycleAuthority = when (stage) {
    LifecycleStage.CO_PLAY -> LifecycleAuthority(true, false, "caregiver-with-child")
    LifecycleStage.ACCOMPANY -> LifecycleAuthority(true, false, "caregiver-with-child-veto")
    LifecycleStage.CO_SELECT -> LifecycleAuthority(true, false, "shared")
    LifecycleStage.HAND_OVER -> LifecycleAuthority(true, true, "child")
    LifecycleStage.GRADUATION -> LifecycleAuthority(false, true, "self-authorized-adult")
}

/** Resolves durable observation visibility without letting earlier stages claim a private child scope. */
fun lifecycleEvidenceVisibility(
    stage: LifecycleStage,
    childPrivateRequested: Boolean,
): EvidenceVisibility = when {
    stage == LifecycleStage.HAND_OVER && childPrivateRequested -> EvidenceVisibility.CHILD_PRIVATE
    stage == LifecycleStage.CO_SELECT || stage == LifecycleStage.HAND_OVER -> EvidenceVisibility.SHARED_WITH_CHILD
    else -> EvidenceVisibility.GUARDIANS
}
