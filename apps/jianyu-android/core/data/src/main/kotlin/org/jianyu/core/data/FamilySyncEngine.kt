package org.jianyu.core.data

import org.jianyu.core.domain.FamilyStateMerger
import org.jianyu.core.model.DeviceSyncCursor
import org.jianyu.core.model.FamilyState

enum class SyncApplyStatus { APPLIED, IDEMPOTENT }

data class SyncApplyResult(
    val state: FamilyState,
    val status: SyncApplyStatus,
    val addedObjectCount: Int = 0,
    val removedByTombstoneCount: Int = 0,
)

/** Applies already decrypted frames. Transport and encryption remain separate provider concerns. */
class FamilySyncEngine(
    private val codec: SyncFrameCodec = SyncFrameCodec(),
    private val merger: FamilyStateMerger = FamilyStateMerger(),
    private val envelopeCodec: SyncEnvelopeCodec = SyncEnvelopeCodec(codec),
) {
    fun apply(local: FamilyState, encodedFrame: ByteArray): SyncApplyResult {
        val frame = codec.decodeAndVerify(encodedFrame)
        return applyVerified(local, frame)
    }

    fun applyEncrypted(local: FamilyState, encryptedEnvelope: ByteArray, key: HouseholdSyncKey): SyncApplyResult {
        val frame = envelopeCodec.open(encryptedEnvelope, key)
        return applyVerified(local, frame)
    }

    internal fun applyVerified(local: FamilyState, frame: org.jianyu.core.model.SyncFrame): SyncApplyResult {
        val body = frame.body
        require(body.household.id == local.household.id) { "Sync frame belongs to another household" }
        require(body.deviceId !in local.syncState.retiredDeviceIds) { "Sync frame device is retired" }

        val cursor = local.syncState.cursors.firstOrNull { it.deviceId == body.deviceId }
        if (cursor != null && body.sequence == cursor.lastSequence && frame.contentHash == cursor.lastFrameHash) {
            return SyncApplyResult(local, SyncApplyStatus.IDEMPOTENT)
        }

        if (cursor == null) {
            require(body.sequence == 1L && body.previousFrameHash == null) {
                "First frame from a device must start at sequence 1"
            }
        } else {
            require(body.sequence == cursor.lastSequence + 1) { "Sync frame sequence gap, fork, or rollback" }
            require(body.previousFrameHash == cursor.lastFrameHash) { "Sync frame chain mismatch" }
        }

        val merged = merger.merge(local, body)
        require(body.deviceId !in merged.state.syncState.retiredDeviceIds) { "Sync frame retires its own device" }
        val nextCursor = DeviceSyncCursor(body.deviceId, body.sequence, frame.contentHash)
        val nextCursors = (merged.state.syncState.cursors.filterNot { it.deviceId == body.deviceId } + nextCursor)
            .sortedBy(DeviceSyncCursor::deviceId)
        val next = merged.state.copy(
            syncState = merged.state.syncState.copy(cursors = nextCursors),
        )
        return SyncApplyResult(
            state = next,
            status = SyncApplyStatus.APPLIED,
            addedObjectCount = merged.addedObjectCount,
            removedByTombstoneCount = merged.removedByTombstoneCount,
        )
    }
}
