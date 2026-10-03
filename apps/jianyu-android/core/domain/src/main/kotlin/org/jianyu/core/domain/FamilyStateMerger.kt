package org.jianyu.core.domain

import org.jianyu.core.model.Child
import org.jianyu.core.model.DeletionTombstone
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Hypothesis
import org.jianyu.core.model.SyncFrameBody
import org.jianyu.core.model.TombstoneTarget
import java.time.Instant

data class FamilyMergeResult(
    val state: FamilyState,
    val addedObjectCount: Int,
    val removedByTombstoneCount: Int,
)

/** Deterministic, clock-independent merge of an already authenticated sync frame body. */
class FamilyStateMerger {
    fun merge(local: FamilyState, incoming: SyncFrameBody): FamilyMergeResult {
        require(local.household.id == incoming.household.id) { "Sync frame belongs to another household" }
        require(local.household == incoming.household) { "Conflicting household object" }

        val tombstones = mergeStrict(local.tombstones, incoming.tombstones, DeletionTombstone::tombstoneId, "tombstone")
        tombstones.forEach { it.requireValidFor(local.household.id) }
        val deletedSubjects = tombstones.filter { it.targetType == TombstoneTarget.SUBJECT }.mapTo(mutableSetOf()) { it.targetId }
        val clearedSubjectContent = tombstones.filter { it.targetType == TombstoneTarget.SUBJECT_CONTENT }
            .mapTo(mutableSetOf()) { it.targetId }
        val deletedEvidence = tombstones.idsFor(TombstoneTarget.EVIDENCE)
        val deletedHypotheses = tombstones.idsFor(TombstoneTarget.HYPOTHESIS)
        val deletedChoices = tombstones.idsFor(TombstoneTarget.CHOICE)
        val deletedEvents = tombstones.idsFor(TombstoneTarget.EVENT)

        val rawMembers = mergeStrict(local.members, incoming.members, FamilyMember::id, "member")
        val rawChildren = mergeStrict(local.children, incoming.children, Child::id, "child")
        val rawEvidence = mergeStrict(local.evidence, incoming.evidence, Evidence::id, "evidence")
        val rawHypotheses = mergeStrict(local.hypotheses, incoming.hypotheses, Hypothesis::id, "hypothesis")
        val rawChoices = mergeChoices(local.choices, incoming.choices)
        val rawEvents = mergeStrict(local.events, incoming.events, FamilyEvent::eventId, "event")

        val members = rawMembers.filterNot { it.subjectId in deletedSubjects }
        val children = rawChildren.filterNot { it.id in deletedSubjects }
        val unavailableSubjectContent = deletedSubjects + clearedSubjectContent
        val evidence = rawEvidence.filterNot { it.id in deletedEvidence || it.childId in unavailableSubjectContent }
        val hypotheses = rawHypotheses.filterNot { it.id in deletedHypotheses || it.childId in unavailableSubjectContent }
        val choices = rawChoices.filterNot { it.id in deletedChoices || it.childId in unavailableSubjectContent }
        val events = rawEvents.filterNot { it.eventId in deletedEvents || it.subjectId in unavailableSubjectContent }

        val beforeVisible = local.members.size + local.children.size + local.evidence.size + local.hypotheses.size +
            local.choices.size + local.events.size
        val rawVisible = rawMembers.size + rawChildren.size + rawEvidence.size + rawHypotheses.size +
            rawChoices.size + rawEvents.size
        val afterVisible = members.size + children.size + evidence.size + hypotheses.size + choices.size + events.size

        return FamilyMergeResult(
            state = local.copy(
                members = members.sortedWith(compareBy(FamilyMember::createdAt, FamilyMember::id)),
                children = children.sortedWith(compareBy(Child::createdAt, Child::id)),
                evidence = evidence.sortedWith(compareBy(Evidence::recordedAt, Evidence::id)),
                hypotheses = hypotheses.sortedWith(compareBy(Hypothesis::derivedAt, Hypothesis::id)),
                choices = choices.sortedWith(compareByDescending<FamilyChoice> { it.chosenAt }.thenBy { it.id }),
                events = events.sortedWith(compareBy(FamilyEvent::recordedAt, FamilyEvent::eventId)),
                tombstones = tombstones.sortedWith(compareBy(DeletionTombstone::deletedAt, DeletionTombstone::tombstoneId)),
                syncState = local.syncState.copy(
                    retiredDeviceIds = (local.syncState.retiredDeviceIds + incoming.retiredDeviceIds).distinct().sorted(),
                ),
            ),
            addedObjectCount = (rawVisible - beforeVisible).coerceAtLeast(0) +
                (tombstones.size - local.tombstones.size).coerceAtLeast(0),
            removedByTombstoneCount = rawVisible - afterVisible,
        )
    }
}

private fun DeletionTombstone.requireValidFor(expectedHouseholdId: String) {
    require(schema in setOf("org.foe.deletion-tombstone/v1", "org.foe.deletion-tombstone/v2")) {
        "Unsupported deletion tombstone schema"
    }
    require(householdId == expectedHouseholdId) { "Deletion tombstone belongs to another household" }
    require(tombstoneId.isNotBlank() && targetId.isNotBlank() && authorId.isNotBlank() && deviceId.isNotBlank()) {
        "Deletion tombstone identity is invalid"
    }
    runCatching { Instant.parse(deletedAt) }
        .getOrElse { throw IllegalArgumentException("Deletion tombstone time is invalid") }
    if (targetType == TombstoneTarget.SUBJECT_CONTENT) {
        require(schema == "org.foe.deletion-tombstone/v2" && subjectId == targetId) {
            "Subject-content deletion requires a v2 subject-bound tombstone"
        }
    }
}

private fun List<DeletionTombstone>.idsFor(type: TombstoneTarget) =
    asSequence().filter { it.targetType == type }.mapTo(mutableSetOf()) { it.targetId }

private fun mergeChoices(local: List<FamilyChoice>, incoming: List<FamilyChoice>): List<FamilyChoice> {
    val merged = LinkedHashMap<String, FamilyChoice>()
    (local + incoming).forEach { candidate ->
        val current = merged[candidate.id]
        merged[candidate.id] = when {
            current == null || current == candidate -> candidate
            current.sameChoiceIdentity(candidate) && current.feedback == null && candidate.feedback != null -> candidate
            current.sameChoiceIdentity(candidate) && current.feedback != null && candidate.feedback == null -> current
            else -> error("Conflicting choice object: ${candidate.id}")
        }
    }
    return merged.values.toList()
}

private fun FamilyChoice.sameChoiceIdentity(other: FamilyChoice) =
    id == other.id && childId == other.childId && opportunity == other.opportunity &&
        sourceEventId == other.sourceEventId && chosenAt == other.chosenAt &&
        status in setOf("chosen", "reflected") && other.status in setOf("chosen", "reflected")

private fun <T> mergeStrict(
    local: List<T>,
    incoming: List<T>,
    id: (T) -> String,
    label: String,
): List<T> {
    val merged = LinkedHashMap<String, T>()
    (local + incoming).forEach { candidate ->
        val key = id(candidate)
        require(key.isNotBlank()) { "$label ID must not be blank" }
        val current = merged[key]
        require(current == null || current == candidate) { "Conflicting $label object: $key" }
        if (current == null) merged[key] = candidate
    }
    return merged.values.toList()
}
