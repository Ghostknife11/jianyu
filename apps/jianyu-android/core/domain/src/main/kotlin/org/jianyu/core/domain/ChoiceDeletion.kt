package org.jianyu.core.domain

import org.jianyu.core.model.DeletionTombstone
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.TombstoneTarget
import java.time.Instant
import java.time.LocalDate

/** Deletes one choice and the events that carry its title or later view, not its earlier interest. */
fun deleteChoiceWithTombstones(
    state: FamilyState,
    choiceId: String,
    authorId: String,
    actorRole: MemberRole,
    subjectConfirmed: Boolean,
    deviceId: String,
    deletedAt: String,
    nextId: () -> String,
    referenceDate: LocalDate = LocalDate.now(),
): FamilyState {
    require(choiceId.isNotBlank() && authorId.isNotBlank() && deviceId.isNotBlank()) { "Deletion identity must not be blank" }
    Instant.parse(deletedAt)
    val choice = state.choices.firstOrNull { it.id == choiceId } ?: return state
    require(state.tombstones.none { it.targetType == TombstoneTarget.CHOICE && it.targetId == choiceId }) {
        "Choice has an inconsistent deletion marker"
    }
    val child = requireNotNull(state.children.firstOrNull { it.id == choice.childId }) { "Choice subject is missing" }
    val stage = lifecycleStage(child, referenceDate)
    val author = requireNotNull(state.members.firstOrNull { it.id == authorId && it.role == actorRole }) {
        "Deletion author does not belong to this household"
    }
    if (stage == LifecycleStage.HAND_OVER || stage == LifecycleStage.GRADUATION) {
        require(subjectConfirmed && actorRole == MemberRole.CHILD && author.id == child.memberId && author.subjectId == child.id) {
            "Subject confirmation and attribution are required for this choice deletion"
        }
    } else {
        require(actorRole == MemberRole.CAREGIVER || actorRole == MemberRole.GUARDIAN) {
            "A caregiver or guardian must author this choice deletion"
        }
    }

    val linkedEvents = state.events.filter { it.payload["choiceId"] == choiceId }
    require(linkedEvents.all { it.subjectId == child.id }) { "Choice-linked event has a different subject" }
    val newIds = List(linkedEvents.size + 2) { nextId() }
    require(newIds.all(String::isNotBlank) && newIds.size == newIds.toSet().size) { "Deletion record IDs must be distinct" }
    require(newIds.dropLast(1).none { id -> state.tombstones.any { it.tombstoneId == id } }) {
        "Deletion tombstone ID already exists"
    }
    require(state.events.none { it.eventId == newIds.last() }) { "Deletion audit event ID already exists" }

    val restrictedSource = choice.hasRestrictedLinkedEvent(state.events)
    val auditVisibility = when {
        restrictedSource -> "child-private"
        stage == LifecycleStage.CO_SELECT || stage == LifecycleStage.HAND_OVER || stage == LifecycleStage.GRADUATION -> "shared-with-child"
        else -> "guardians"
    }
    val targets = listOf(TombstoneTarget.CHOICE to choiceId) +
        linkedEvents.map { TombstoneTarget.EVENT to it.eventId }
    val tombstones = targets.mapIndexed { index, (targetType, targetId) ->
        DeletionTombstone(
            tombstoneId = newIds[index], householdId = state.household.id,
            targetType = targetType, targetId = targetId, subjectId = child.id,
            authorId = author.id, deviceId = deviceId, deletedAt = deletedAt,
        )
    }
    val audit = FamilyEvent(
        eventId = newIds.last(), eventType = "opportunity.choice-deleted",
        householdId = state.household.id, authorId = author.id, actorRole = actorRole,
        subjectId = child.id, deviceId = deviceId, occurredAt = deletedAt,
        recordedAt = deletedAt, visibility = auditVisibility,
        payload = mapOf(
            "targetType" to TombstoneTarget.CHOICE.name,
            "targetId" to choiceId,
            "tombstoneId" to newIds.first(),
        ),
    )
    val linkedIds = linkedEvents.mapTo(mutableSetOf()) { it.eventId }
    return state.copy(
        choices = state.choices.filterNot { it.id == choiceId },
        events = state.events.filterNot { it.eventId in linkedIds } + audit,
        tombstones = state.tombstones + tombstones,
    )
}
