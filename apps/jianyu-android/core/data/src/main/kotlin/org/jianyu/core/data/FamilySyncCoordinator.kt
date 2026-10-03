package org.jianyu.core.data

import org.jianyu.core.domain.OpaqueSyncObjectId
import org.jianyu.core.domain.SyncProvider
import org.jianyu.core.domain.SyncProviderLimits
import org.jianyu.core.model.CURRENT_FAMILY_STATE_SCHEMA
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.SyncFrame
import org.jianyu.core.model.SyncFrameBody

data class FamilySyncReport(
    val state: FamilyState,
    val inspectedObjectCount: Int,
    val appliedFrameCount: Int,
    val uploadedObjectId: OpaqueSyncObjectId?,
)

/**
 * Provider-neutral client orchestration. A transport sees only opaque IDs and encrypted envelopes;
 * decryption, chain verification, deletion-dominant merge, and new-frame creation stay on device.
 *
 * This v1 coordinator deliberately requires the complete append-only history for every visible
 * device. Checkpoints and safe compaction belong to a later protocol version.
 */
class FamilySyncCoordinator(
    private val frameCodec: SyncFrameCodec = SyncFrameCodec(),
    private val envelopeCodec: SyncEnvelopeCodec = SyncEnvelopeCodec(frameCodec),
    private val engine: FamilySyncEngine = FamilySyncEngine(frameCodec, envelopeCodec = envelopeCodec),
    private val objectIdFactory: () -> OpaqueSyncObjectId = OpaqueSyncObjectId::generate,
) {
    suspend fun synchronize(
        local: FamilyState,
        provider: SyncProvider,
        key: HouseholdSyncKey,
        deviceId: String,
    ): FamilySyncReport {
        require(DEVICE_ID.matches(deviceId)) { "Sync device ID is invalid" }
        require(local.schema == CURRENT_FAMILY_STATE_SCHEMA) { "Family state must be migrated before synchronization" }
        require(deviceId !in local.syncState.retiredDeviceIds) { "This sync device is retired" }

        val descriptors = listAll(provider)
        val frames = descriptors.map { descriptor ->
            val envelope = provider.getEncryptedObject(descriptor.objectId)
            try {
                require(envelope.size.toLong() == descriptor.sizeBytes) { "Sync object size changed while reading" }
                envelopeCodec.open(envelope, key)
            } finally {
                envelope.fill(0)
            }
        }
        val verifiedHistories = verifyCompleteHistories(frames, local)

        var merged = local
        var appliedCount = 0
        verifiedHistories.forEach { frame ->
            val cursor = merged.syncState.cursors.firstOrNull { it.deviceId == frame.body.deviceId }
            if (cursor == null || frame.body.sequence >= cursor.lastSequence) {
                val result = engine.applyVerified(merged, frame)
                merged = result.state
                if (result.status == SyncApplyStatus.APPLIED) appliedCount += 1
            }
        }

        require(deviceId !in merged.syncState.retiredDeviceIds) { "This sync device was retired by remote history" }
        val lastOwnFrame = verifiedHistories.lastOrNull { it.body.deviceId == deviceId }
        if (lastOwnFrame != null && sameSnapshot(merged, lastOwnFrame.body)) {
            return FamilySyncReport(
                state = merged,
                inspectedObjectCount = descriptors.size,
                appliedFrameCount = appliedCount,
                uploadedObjectId = null,
            )
        }

        val localCursor = merged.syncState.cursors.firstOrNull { it.deviceId == deviceId }
        val outgoing = frameCodec.create(
            state = merged,
            deviceId = deviceId,
            sequence = (localCursor?.lastSequence ?: 0L) + 1L,
            previousFrameHash = localCursor?.lastFrameHash,
        )
        val encrypted = envelopeCodec.seal(outgoing, key)
        val objectId = objectIdFactory()
        try {
            provider.putEncryptedObject(objectId, encrypted)
        } finally {
            encrypted.fill(0)
        }
        merged = engine.applyVerified(merged, outgoing).state

        return FamilySyncReport(
            state = merged,
            inspectedObjectCount = descriptors.size,
            appliedFrameCount = appliedCount,
            uploadedObjectId = objectId,
        )
    }

    private fun sameSnapshot(state: FamilyState, body: SyncFrameBody): Boolean =
        state.household == body.household &&
            state.members == body.members &&
            state.children == body.children &&
            state.evidence == body.evidence &&
            state.hypotheses == body.hypotheses &&
            state.choices == body.choices &&
            state.events == body.events &&
            state.tombstones == body.tombstones &&
            state.syncState.retiredDeviceIds == body.retiredDeviceIds

    private suspend fun listAll(provider: SyncProvider): List<org.jianyu.core.domain.OpaqueSyncObjectDescriptor> {
        val objects = linkedMapOf<String, org.jianyu.core.domain.OpaqueSyncObjectDescriptor>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = provider.listOpaqueObjects(cursor, SyncProviderLimits.MAX_PAGE_SIZE)
            page.objects.forEach { descriptor ->
                require(objects.putIfAbsent(descriptor.objectId.value, descriptor) == null) {
                    "Sync provider returned a duplicate object ID"
                }
                require(descriptor.sizeBytes in 1..SyncProviderLimits.MAX_OBJECT_BYTES.toLong()) {
                    "Sync provider returned an invalid object size"
                }
            }
            require(objects.size <= MAX_HISTORY_OBJECTS) { "Sync history exceeds the v1 safety limit" }
            require(objects.values.sumOf { it.sizeBytes } <= MAX_HISTORY_BYTES) {
                "Sync history exceeds the v1 total-size safety limit"
            }
            val next = page.nextCursor
            require(next == null || next != cursor) { "Sync provider cursor did not advance" }
            require(next == null || seenCursors.add(next)) { "Sync provider cursor repeated" }
            cursor = next
        } while (cursor != null)
        return objects.values.toList()
    }

    private fun verifyCompleteHistories(frames: List<SyncFrame>, local: FamilyState): List<SyncFrame> {
        val histories = frames.groupBy { it.body.deviceId }.toSortedMap().mapValues { (_, deviceFrames) ->
            val canonical = deviceFrames
                .groupBy { it.body.sequence }
                .toSortedMap()
                .map { (sequence, duplicates) ->
                    require(duplicates.map(SyncFrame::contentHash).distinct().size == 1) {
                        "Sync history contains a fork at sequence $sequence"
                    }
                    duplicates.first()
                }
            canonical.forEachIndexed { index, frame ->
                val expectedSequence = index + 1L
                require(frame.body.sequence == expectedSequence) { "Sync history is incomplete" }
                val expectedPrevious = canonical.getOrNull(index - 1)?.contentHash
                require(frame.body.previousFrameHash == expectedPrevious) { "Sync history chain is broken" }
                require(frame.body.household.id == local.household.id) { "Sync frame belongs to another household" }
            }
            val localCursor = local.syncState.cursors.firstOrNull { it.deviceId == canonical.first().body.deviceId }
            if (localCursor != null) {
                val matching = canonical.getOrNull((localCursor.lastSequence - 1L).toInt())
                require(matching?.contentHash == localCursor.lastFrameHash) { "Sync provider history rolled back or forked" }
            }
            canonical
        }
        local.syncState.cursors.forEach { cursor ->
            require(histories.containsKey(cursor.deviceId)) { "Sync provider history rolled back or disappeared" }
        }
        return histories.values.flatten()
    }

    private companion object {
        val DEVICE_ID = Regex("[A-Za-z0-9_-]{16,128}")
        const val MAX_HISTORY_OBJECTS = 256
        const val MAX_HISTORY_BYTES = 48L * 1024L * 1024L
    }
}
