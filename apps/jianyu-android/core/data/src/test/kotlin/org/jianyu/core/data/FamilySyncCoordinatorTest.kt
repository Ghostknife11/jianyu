package org.jianyu.core.data

import kotlinx.coroutines.runBlocking
import org.jianyu.core.domain.OpaqueSyncObjectDescriptor
import org.jianyu.core.domain.OpaqueSyncObjectId
import org.jianyu.core.domain.OpaqueSyncObjectPage
import org.jianyu.core.domain.SyncDeleteResult
import org.jianyu.core.domain.SyncDeleteStatus
import org.jianyu.core.domain.SyncProvider
import org.jianyu.core.domain.SyncPutResult
import org.jianyu.core.domain.SyncPutStatus
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.FamilySyncState
import org.jianyu.core.model.Household
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilySyncCoordinatorTest {
    private val key = HouseholdSyncKey.fromBytes("test_household_key_1", ByteArray(32) { (it + 1).toByte() })
    private val household = Household("household", "合成家庭", "2026-01-01T00:00:00Z")

    @Test
    fun `two clients merge only through opaque encrypted provider objects`() = runBlocking {
        val provider = MemorySyncProvider()
        val first = coordinator("AAAAAAAAAAAAAAAAAAAAAAAA").synchronize(
            state(evidence("a", "赛车")), provider, key, "device_AAAAAAAAAA",
        )
        val second = coordinator("BBBBBBBBBBBBBBBBBBBBBBBB").synchronize(
            state(evidence("b", "天文")), provider, key, "device_BBBBBBBBBB",
        )
        val converged = coordinator("CCCCCCCCCCCCCCCCCCCCCCCC").synchronize(
            first.state, provider, key, "device_AAAAAAAAAA",
        )

        assertEquals(setOf("a", "b"), second.state.evidence.map(Evidence::id).toSet())
        assertEquals(setOf("a", "b"), converged.state.evidence.map(Evidence::id).toSet())
        assertEquals(3, provider.objects.size)
        assertTrue(provider.objects.keys.all { it.length == 24 })
        assertTrue(provider.objects.values.none { bytes -> bytes.decodeToString().contains("赛车") || bytes.decodeToString().contains("天文") })
    }

    @Test
    fun `rechecking unchanged folder does not consume another history slot`() = runBlocking {
        val provider = MemorySyncProvider()
        val first = coordinator("AAAAAAAAAAAAAAAAAAAAAAAA").synchronize(
            state(evidence("a", "赛车")), provider, key, "device_AAAAAAAAAA",
        )
        val unchanged = coordinator("BBBBBBBBBBBBBBBBBBBBBBBB").synchronize(
            first.state, provider, key, "device_AAAAAAAAAA",
        )

        assertEquals(1, provider.objects.size)
        assertEquals(0, unchanged.appliedFrameCount)
        assertEquals(null, unchanged.uploadedObjectId)
        assertEquals(first.state, unchanged.state)

        val changed = coordinator("CCCCCCCCCCCCCCCCCCCCCCCC").synchronize(
            first.state.copy(evidence = first.state.evidence + evidence("b", "天文")),
            provider, key, "device_AAAAAAAAAA",
        )
        assertEquals(2, provider.objects.size)
        assertEquals("CCCCCCCCCCCCCCCCCCCCCCCC", changed.uploadedObjectId?.value)

        val unchangedAgain = coordinator("DDDDDDDDDDDDDDDDDDDDDDDD").synchronize(
            changed.state, provider, key, "device_AAAAAAAAAA",
        )
        assertEquals(2, provider.objects.size)
        assertEquals(null, unchangedAgain.uploadedObjectId)
    }

    @Test
    fun `tampered ciphertext stops before another object is uploaded`() = runBlocking {
        val provider = MemorySyncProvider()
        coordinator("AAAAAAAAAAAAAAAAAAAAAAAA").synchronize(
            state(evidence("a", "赛车")), provider, key, "device_AAAAAAAAAA",
        )
        provider.objects.values.single().let { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        val before = provider.objects.size

        var failed = false
        try {
            coordinator("BBBBBBBBBBBBBBBBBBBBBBBB").synchronize(
                state(), provider, key, "device_BBBBBBBBBB",
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(before, provider.objects.size)
    }

    @Test
    fun `remote history disappearance is treated as rollback`() = runBlocking {
        val provider = MemorySyncProvider()
        val first = coordinator("AAAAAAAAAAAAAAAAAAAAAAAA").synchronize(
            state(evidence("a", "赛车")), provider, key, "device_AAAAAAAAAA",
        )
        provider.objects.clear()

        var failed = false
        try {
            coordinator("BBBBBBBBBBBBBBBBBBBBBBBB").synchronize(
                first.state, provider, key, "device_AAAAAAAAAA",
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(provider.objects.isEmpty())
    }

    @Test
    fun `remote retirement blocks the current device before any new upload`() = runBlocking {
        val provider = MemorySyncProvider()
        val first = coordinator("AAAAAAAAAAAAAAAAAAAAAAAA").synchronize(
            state(evidence("a", "赛车")), provider, key, "device_AAAAAAAAAA",
        )
        val retirement = SyncFrameCodec().create(
            state().copy(syncState = FamilySyncState(retiredDeviceIds = listOf("device_AAAAAAAAAA"))),
            deviceId = "device_BBBBBBBBBB",
            sequence = 1,
            previousFrameHash = null,
        )
        provider.objects["BBBBBBBBBBBBBBBBBBBBBBBB"] = SyncEnvelopeCodec().seal(retirement, key)
        val before = provider.objects.size

        var failed = false
        try {
            coordinator("CCCCCCCCCCCCCCCCCCCCCCCC").synchronize(
                first.state, provider, key, "device_AAAAAAAAAA",
            )
        } catch (_: IllegalArgumentException) {
            failed = true
        }

        assertTrue(failed)
        assertEquals(before, provider.objects.size)
    }

    private fun coordinator(objectId: String) = FamilySyncCoordinator(
        objectIdFactory = { OpaqueSyncObjectId.parse(objectId) },
    )

    private fun state(vararg evidence: Evidence) = FamilyState(
        household = household,
        members = emptyList(),
        children = emptyList(),
        evidence = evidence.toList(),
    )

    private fun evidence(id: String, expression: String) = Evidence(
        id = id,
        childId = "child",
        authorId = "member",
        stream = ContextStream.CHILD,
        kind = EvidenceKind.DIRECT_OBSERVATION,
        expression = expression,
        occurredAt = "2026-09-14T00:00:00Z",
        recordedAt = "2026-09-14T00:00:00Z",
        ownerId = "child-member",
        visibility = EvidenceVisibility.GUARDIANS,
    )

    private class MemorySyncProvider : SyncProvider {
        override val id = "memory"
        val objects = sortedMapOf<String, ByteArray>()

        override suspend fun listOpaqueObjects(cursor: String?, limit: Int): OpaqueSyncObjectPage {
            val ids = objects.keys.filter { cursor == null || it > cursor }.take(limit)
            return OpaqueSyncObjectPage(
                ids.map { OpaqueSyncObjectDescriptor(OpaqueSyncObjectId.parse(it), objects.getValue(it).size.toLong()) },
                ids.lastOrNull()?.takeIf { objects.keys.any { key -> key > it } },
            )
        }

        override suspend fun putEncryptedObject(objectId: OpaqueSyncObjectId, encryptedEnvelope: ByteArray): SyncPutResult {
            val existing = objects[objectId.value]
            if (existing != null) {
                require(existing.contentEquals(encryptedEnvelope))
                return SyncPutResult(SyncPutStatus.IDEMPOTENT)
            }
            objects[objectId.value] = encryptedEnvelope.copyOf()
            return SyncPutResult(SyncPutStatus.CREATED)
        }

        override suspend fun getEncryptedObject(objectId: OpaqueSyncObjectId): ByteArray =
            objects.getValue(objectId.value).copyOf()

        override suspend fun deleteEncryptedObject(objectId: OpaqueSyncObjectId): SyncDeleteResult =
            if (objects.remove(objectId.value) != null) SyncDeleteResult(SyncDeleteStatus.DELETED)
            else SyncDeleteResult(SyncDeleteStatus.NOT_FOUND)
    }
}
