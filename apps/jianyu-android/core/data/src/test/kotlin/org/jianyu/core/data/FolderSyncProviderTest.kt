package org.jianyu.core.data

import kotlinx.coroutines.runBlocking
import org.jianyu.core.domain.OpaqueSyncObjectId
import org.jianyu.core.domain.SyncDeleteStatus
import org.jianyu.core.domain.SyncPutStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class FolderSyncProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `folder provider round trips only opaque object names and bytes`() = runBlocking {
        val root = temporaryFolder.newFolder("provider").toPath()
        val provider = FolderSyncProvider(root)
        val objectId = OpaqueSyncObjectId.generate()
        val encryptedEnvelope = "{\"format\":\"org.foe.encrypted-sync-envelope/v1\",\"ciphertext\":\"opaque\"}".encodeToByteArray()

        assertEquals(SyncPutStatus.CREATED, provider.putEncryptedObject(objectId, encryptedEnvelope).status)
        assertArrayEquals(encryptedEnvelope, provider.getEncryptedObject(objectId))
        assertEquals(listOf(objectId), provider.listOpaqueObjects().objects.map { it.objectId })

        val visibleNames = Files.list(root.resolve("objects")).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("${objectId.value}.foe-sync"), visibleNames)
        assertTrue(visibleNames.none { it.contains("family", ignoreCase = true) || it.contains("child", ignoreCase = true) })
    }

    @Test
    fun `same ciphertext retry is idempotent but replacement fails closed`() = runBlocking {
        val provider = FolderSyncProvider(temporaryFolder.newFolder("immutable").toPath())
        val objectId = OpaqueSyncObjectId.generate()
        val first = "encrypted-one".encodeToByteArray()

        assertEquals(SyncPutStatus.CREATED, provider.putEncryptedObject(objectId, first).status)
        assertEquals(SyncPutStatus.IDEMPOTENT, provider.putEncryptedObject(objectId, first).status)
        val failure = runCatching { provider.putEncryptedObject(objectId, "encrypted-two".encodeToByteArray()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertArrayEquals(first, provider.getEncryptedObject(objectId))
    }

    @Test
    fun `listing is deterministic bounded and cursor based`() = runBlocking {
        val provider = FolderSyncProvider(temporaryFolder.newFolder("paging").toPath())
        val ids = listOf(
            OpaqueSyncObjectId.parse("AAAAAAAAAAAAAAAAAAAAAAAA"),
            OpaqueSyncObjectId.parse("BBBBBBBBBBBBBBBBBBBBBBBB"),
            OpaqueSyncObjectId.parse("CCCCCCCCCCCCCCCCCCCCCCCC"),
        )
        ids.reversed().forEach { provider.putEncryptedObject(it, "cipher-${it.value}".encodeToByteArray()) }

        val first = provider.listOpaqueObjects(limit = 2)
        assertEquals(ids.take(2), first.objects.map { it.objectId })
        assertEquals(ids[1].value, first.nextCursor)
        val second = provider.listOpaqueObjects(cursor = first.nextCursor, limit = 2)
        assertEquals(listOf(ids[2]), second.objects.map { it.objectId })
        assertEquals(null, second.nextCursor)
    }

    @Test
    fun `delete is exact and repeatable`() = runBlocking {
        val provider = FolderSyncProvider(temporaryFolder.newFolder("delete").toPath())
        val objectId = OpaqueSyncObjectId.generate()
        provider.putEncryptedObject(objectId, "encrypted".encodeToByteArray())

        assertEquals(SyncDeleteStatus.DELETED, provider.deleteEncryptedObject(objectId).status)
        assertEquals(SyncDeleteStatus.NOT_FOUND, provider.deleteEncryptedObject(objectId).status)
        assertTrue(provider.listOpaqueObjects().objects.isEmpty())
    }
}
