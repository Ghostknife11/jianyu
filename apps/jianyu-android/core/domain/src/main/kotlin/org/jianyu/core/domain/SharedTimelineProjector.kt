package org.jianyu.core.domain

import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Hypothesis

/**
 * A caregiver/shared-device projection of the Family Vault.
 *
 * This is a presentation boundary, not an authorization or cryptographic boundary.
 * Records still share the household vault and household key until subject-separated
 * identity and key spaces are implemented.
 */
data class SharedTimelineProjection(
    val choices: List<FamilyChoice>,
    val evidence: List<Evidence>,
    val hypotheses: List<Hypothesis>,
    val events: List<FamilyEvent>,
    val hasRestrictedRecords: Boolean,
)

fun projectSharedTimeline(family: FamilyState): SharedTimelineProjection {
    val visibleEvidence = family.evidence.filter { it.visibility != EvidenceVisibility.CHILD_PRIVATE }
    val visibleEvidenceIds = visibleEvidence.mapTo(mutableSetOf()) { it.id }

    val visibleEventIds = family.events.asSequence()
        .filter(FamilyEvent::isVisibleInSharedTimeline)
        .mapTo(mutableSetOf()) { it.eventId }
    val allEventIds = family.events.mapTo(mutableSetOf()) { it.eventId }
    val hiddenChoiceIds = family.events.asSequence()
        .filter { it.eventId !in visibleEventIds }
        .mapNotNull { it.payload["choiceId"] }
        .toMutableSet()
    family.choices.forEach { choice ->
        if (choice.hasRestrictedLinkedEvent(family.events) ||
            choice.sourceEventId in allEventIds && choice.sourceEventId !in visibleEventIds) {
            hiddenChoiceIds += choice.id
        }
    }

    // A later choice or response event may have a broader visibility label than its source.
    // Do not let that event disclose a title or response for a hidden choice.
    val visibleEvents = family.events.filter { event ->
        event.eventId in visibleEventIds && event.payload["choiceId"] !in hiddenChoiceIds
    }

    val visibleChoices = family.choices.filter { choice ->
        choice.id !in hiddenChoiceIds &&
            (choice.sourceEventId !in allEventIds || choice.sourceEventId in visibleEventIds)
    }

    // Hypotheses have no independent visibility field in v1. Fail closed unless
    // every evidence reference is visible and at least one supporting item remains.
    val visibleHypotheses = family.hypotheses.filter { hypothesis ->
        hypothesis.supports.isNotEmpty() &&
            hypothesis.supports.all { it in visibleEvidenceIds } &&
            hypothesis.contradicts.all { it in visibleEvidenceIds }
    }

    return SharedTimelineProjection(
        choices = visibleChoices,
        evidence = visibleEvidence,
        hypotheses = visibleHypotheses,
        events = visibleEvents,
        hasRestrictedRecords = visibleChoices.size != family.choices.size ||
            visibleEvidence.size != family.evidence.size ||
            visibleHypotheses.size != family.hypotheses.size ||
            visibleEvents.size != family.events.size,
    )
}
