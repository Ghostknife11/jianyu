package org.jianyu.core.domain

import org.jianyu.core.model.DeletionTombstone
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.TombstoneTarget
import java.time.Instant

/** Removes an evidence projection and appends a content-free deletion marker. */
fun deleteEvidenceWithTombstone(
    state: FamilyState,
    evidenceId: String,
    authorId: String,
    actorRole: MemberRole,
    deviceId: String,
    deletedAt: String,
    tombstoneId: String,
    eventId: String,
): FamilyState {
    require(evidenceId.isNotBlank() && authorId.isNotBlank() && deviceId.isNotBlank()) { "Deletion identity must not be blank" }
    require(tombstoneId.isNotBlank() && eventId.isNotBlank()) { "Deletion record IDs must not be blank" }
    Instant.parse(deletedAt)
    val evidence = state.evidence.firstOrNull { it.id == evidenceId } ?: return state
    if (state.tombstones.any { it.targetType == TombstoneTarget.EVIDENCE && it.targetId == evidenceId }) return state

    val tombstone = DeletionTombstone(
        tombstoneId = tombstoneId,
        householdId = state.household.id,
        targetType = TombstoneTarget.EVIDENCE,
        targetId = evidence.id,
        subjectId = evidence.childId,
        authorId = authorId,
        deviceId = deviceId,
        deletedAt = deletedAt,
    )
    val event = FamilyEvent(
        eventId = eventId,
        eventType = "evidence.deleted",
        householdId = state.household.id,
        authorId = authorId,
        actorRole = actorRole,
        subjectId = evidence.childId,
        deviceId = deviceId,
        occurredAt = deletedAt,
        recordedAt = deletedAt,
        visibility = "guardians",
        payload = mapOf(
            "tombstoneId" to tombstoneId,
            "targetType" to TombstoneTarget.EVIDENCE.name,
            "targetId" to evidence.id,
        ),
    )
    return state.copy(
        evidence = state.evidence.filterNot { it.id == evidenceId },
        tombstones = state.tombstones + tombstone,
        events = state.events + event,
    )
}
