package org.jianyu.core.domain

import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.Hypothesis

/** Older versions could save offline-preview inputs and picks in the vault. Keep them, but never treat them as real preferences. */
fun isLegacyDemoChoice(choice: FamilyChoice, events: List<FamilyEvent>): Boolean {
    if (choice.opportunity.sourceKind == "offline-demo-template") return true
    val sourceId = choice.sourceEventId.removePrefix("ephemeral:")
    return events.any { it.eventId == sourceId && it.isOfflineDemoInterest() }
}

fun isLegacyDemoEvidence(item: Evidence, events: List<FamilyEvent>): Boolean = events.any { event ->
    event.isOfflineDemoInterest() &&
        event.subjectId == item.childId &&
        event.authorId == item.authorId &&
        event.recordedAt == item.recordedAt &&
        event.expressionFor(item.stream) == item.expression
}

/** A conclusion that used even one demo-only input must not appear among real child interpretations. */
fun isLegacyDemoHypothesis(
    hypothesis: Hypothesis,
    evidence: List<Evidence>,
    events: List<FamilyEvent>,
): Boolean {
    val referencedIds = (hypothesis.supports + hypothesis.contradicts).toSet()
    return evidence.any { it.id in referencedIds && isLegacyDemoEvidence(it, events) }
}

private fun FamilyEvent.isOfflineDemoInterest() =
    eventType == "interest.observed" && payload["discoveryMode"] == "offline-demo"

private fun FamilyEvent.expressionFor(stream: ContextStream): String? = when (stream) {
    ContextStream.CHILD -> payload["expression"]
    ContextStream.SCHOOL -> payload["schoolWindow"]
    ContextStream.LIFE -> payload["lifeContext"]
    else -> null
}
