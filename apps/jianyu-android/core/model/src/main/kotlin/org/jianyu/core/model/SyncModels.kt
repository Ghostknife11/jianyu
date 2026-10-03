package org.jianyu.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TombstoneTarget {
    EVIDENCE,
    HYPOTHESIS,
    CHOICE,
    EVENT,
    SUBJECT_CONTENT,
    SUBJECT,
}

@Serializable
data class DeletionTombstone(
    val schema: String = "org.foe.deletion-tombstone/v1",
    val tombstoneId: String,
    val householdId: String,
    val targetType: TombstoneTarget,
    val targetId: String,
    val subjectId: String? = null,
    val authorId: String,
    val deviceId: String,
    val deletedAt: String,
    val reasonCode: String = "family-request",
)

@Serializable
data class DeviceSyncCursor(
    val deviceId: String,
    val lastSequence: Long,
    val lastFrameHash: String,
)

@Serializable
data class FamilySyncState(
    val schema: String = "org.foe.family-sync-state/v1",
    val cursors: List<DeviceSyncCursor> = emptyList(),
    val retiredDeviceIds: List<String> = emptyList(),
)

@Serializable
data class SyncFrameBody(
    val schema: String = "org.foe.sync-frame-body/v1",
    val frameId: String,
    val household: Household,
    val deviceId: String,
    val sequence: Long,
    val previousFrameHash: String? = null,
    val createdAt: String,
    val members: List<FamilyMember> = emptyList(),
    val children: List<Child> = emptyList(),
    val evidence: List<Evidence> = emptyList(),
    val hypotheses: List<Hypothesis> = emptyList(),
    val choices: List<FamilyChoice> = emptyList(),
    val events: List<FamilyEvent> = emptyList(),
    val tombstones: List<DeletionTombstone> = emptyList(),
    val retiredDeviceIds: List<String> = emptyList(),
)

@Serializable
data class SyncFrame(
    val schema: String = "org.foe.sync-frame/v1",
    val body: SyncFrameBody,
    val contentHash: String,
)
