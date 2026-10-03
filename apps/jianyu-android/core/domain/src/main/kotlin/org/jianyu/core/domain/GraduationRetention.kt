package org.jianyu.core.domain

import org.jianyu.core.model.DeletionTombstone
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.TombstoneTarget
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Year

enum class GraduationRetentionMode {
    RELATIONSHIP_ONLY,
    ERASE_SUBJECT,
}

data class GraduationRetentionAuthorization(
    val subjectId: String,
    val confirmedAt: String,
    val statementVersion: String = AUTHORIZATION_SCHEMA,
)

/**
 * Applies a graduating person's explicit retention decision without creating a new profile event.
 * The content-free tombstone is the durable, syncable receipt that prevents stale copies from
 * resurrecting either the person's history or the complete subject record.
 */
fun applyGraduationRetention(
    state: FamilyState,
    childId: String,
    mode: GraduationRetentionMode,
    authorization: GraduationRetentionAuthorization,
    actionAt: Instant,
    tombstoneId: String,
    deviceId: String,
    currentYear: Int = Year.now().value,
    referenceDate: LocalDate = LocalDate.now(),
): FamilyState {
    val child = requireNotNull(state.children.firstOrNull { it.id == childId }) { "Graduation subject does not exist" }
    val stage = if (child.birthDate == null) lifecycleStage(child.birthYear, currentYear) else lifecycleStage(child, referenceDate)
    require(stage == LifecycleStage.GRADUATION) {
        "Graduation retention control is available only at 16+"
    }
    require(authorization.statementVersion == AUTHORIZATION_SCHEMA && authorization.subjectId == child.id) {
        "Fresh authorization from the graduating person is required"
    }
    val confirmedAt = runCatching { Instant.parse(authorization.confirmedAt) }
        .getOrElse { throw IllegalArgumentException("Graduation retention authorization time is invalid") }
    val age = Duration.between(confirmedAt, actionAt)
    require(!age.isNegative && age <= AUTHORIZATION_MAX_AGE) { "Graduation retention authorization is no longer fresh" }
    require(tombstoneId.isNotBlank() && deviceId.isNotBlank()) { "Graduation retention receipt identity is invalid" }
    val subjectMember = requireNotNull(state.members.firstOrNull {
        it.id == child.memberId && it.role == MemberRole.CHILD && it.subjectId == child.id
    }) { "Graduation subject member link is invalid" }

    val targetType = when (mode) {
        GraduationRetentionMode.RELATIONSHIP_ONLY -> TombstoneTarget.SUBJECT_CONTENT
        GraduationRetentionMode.ERASE_SUBJECT -> TombstoneTarget.SUBJECT
    }
    require(state.tombstones.none { it.targetType == targetType && it.targetId == child.id }) {
        "Graduation retention decision was already applied"
    }
    val tombstone = DeletionTombstone(
        schema = "org.foe.deletion-tombstone/v2",
        tombstoneId = tombstoneId,
        householdId = state.household.id,
        targetType = targetType,
        targetId = child.id,
        subjectId = child.id,
        authorId = subjectMember.id,
        deviceId = deviceId,
        deletedAt = actionAt.toString(),
        reasonCode = when (mode) {
            GraduationRetentionMode.RELATIONSHIP_ONLY -> "graduation-relationship-only"
            GraduationRetentionMode.ERASE_SUBJECT -> "graduation-subject-erasure"
        },
    )
    val keepSubject = mode == GraduationRetentionMode.RELATIONSHIP_ONLY
    return state.copy(
        members = if (keepSubject) state.members else state.members.filterNot { it.subjectId == child.id },
        children = if (keepSubject) state.children else state.children.filterNot { it.id == child.id },
        evidence = state.evidence.filterNot { it.childId == child.id },
        hypotheses = state.hypotheses.filterNot { it.childId == child.id },
        choices = state.choices.filterNot { it.childId == child.id },
        events = state.events.filterNot { it.subjectId == child.id },
        tombstones = state.tombstones + tombstone,
    )
}

private const val AUTHORIZATION_SCHEMA = "org.foe.graduation-retention-consent/v1"
private val AUTHORIZATION_MAX_AGE: Duration = Duration.ofMinutes(5)
