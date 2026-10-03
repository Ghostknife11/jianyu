package org.jianyu.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jianyu.core.domain.OpaqueSyncObjectDescriptor
import org.jianyu.core.domain.OpaqueSyncObjectId
import org.jianyu.core.domain.OpaqueSyncObjectPage
import org.jianyu.core.domain.SyncDeleteResult
import org.jianyu.core.domain.SyncDeleteStatus
import org.jianyu.core.domain.SyncProvider
import org.jianyu.core.domain.SyncProviderLimits
import org.jianyu.core.domain.SyncPutResult
import org.jianyu.core.domain.SyncPutStatus
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.io.IOException

/** Filesystem reference transport for already encrypted sync envelopes. Not Android UI orchestration. */
class FolderSyncProvider(
    root: Path,
    override val id: String = "org.foe.sync.folder-reference/v1",
) : SyncProvider {
    private val objectsDirectory: Path = Files.createDirectories(root.toAbsolutePath().normalize().resolve("objects"))
        .toRealPath(LinkOption.NOFOLLOW_LINKS)

    override suspend fun listOpaqueObjects(cursor: String?, limit: Int): OpaqueSyncObjectPage = withContext(Dispatchers.IO) {
        require(limit in 1..SyncProviderLimits.MAX_PAGE_SIZE) { "Sync page size is outside supported range" }
        val after = cursor?.let(OpaqueSyncObjectId::parse)?.value
        val ids = buildList {
            Files.newDirectoryStream(objectsDirectory, "*$OBJECT_SUFFIX").use { entries ->
                entries.forEach { path ->
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Sync object is not a regular file" }
                    val filename = path.fileName.toString()
                    add(OpaqueSyncObjectId.parse(filename.removeSuffix(OBJECT_SUFFIX)))
                }
            }
        }.sortedBy(OpaqueSyncObjectId::value)
        val remaining = if (after == null) ids else ids.dropWhile { it.value <= after }
        val pageIds = remaining.take(limit)
        OpaqueSyncObjectPage(
            objects = pageIds.map { objectId ->
                val path = pathFor(objectId)
                OpaqueSyncObjectDescriptor(objectId, checkedSize(path))
            },
            nextCursor = pageIds.lastOrNull()?.value?.takeIf { remaining.size > pageIds.size },
        )
    }

    override suspend fun putEncryptedObject(
        objectId: OpaqueSyncObjectId,
        encryptedEnvelope: ByteArray,
    ): SyncPutResult {
        require(encryptedEnvelope.size in 1..SyncProviderLimits.MAX_OBJECT_BYTES) { "Encrypted sync object size is invalid" }
        val snapshot = encryptedEnvelope.copyOf()
        return try {
            withContext(Dispatchers.IO) {
                val destination = pathFor(objectId)
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    return@withContext existingPutResult(destination, snapshot)
                }
                val staging = Files.createTempFile(objectsDirectory, ".staging-", ".tmp")
                try {
                    FileChannel.open(staging, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                        val buffer = ByteBuffer.wrap(snapshot)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(true)
                    }
                    commitWithoutReplacement(staging, destination, snapshot)
                } finally {
                    Files.deleteIfExists(staging)
                }
            }
        } finally {
            snapshot.fill(0)
        }
    }

    override suspend fun getEncryptedObject(objectId: OpaqueSyncObjectId): ByteArray = withContext(Dispatchers.IO) {
        readChecked(pathFor(objectId))
    }

    override suspend fun deleteEncryptedObject(objectId: OpaqueSyncObjectId): SyncDeleteResult = withContext(Dispatchers.IO) {
        val path = pathFor(objectId)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            SyncDeleteResult(SyncDeleteStatus.NOT_FOUND)
        } else {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Sync object is not a regular file" }
            Files.delete(path)
            SyncDeleteResult(SyncDeleteStatus.DELETED)
        }
    }

    private fun existingPutResult(destination: Path, expected: ByteArray): SyncPutResult {
        val existing = readChecked(destination)
        try {
            require(existing.contentEquals(expected)) { "Immutable sync object ID already contains different ciphertext" }
            return SyncPutResult(SyncPutStatus.IDEMPOTENT)
        } finally {
            existing.fill(0)
        }
    }

    private fun commitWithoutReplacement(staging: Path, destination: Path, bytes: ByteArray): SyncPutResult {
        try {
            Files.createLink(destination, staging)
            return SyncPutResult(SyncPutStatus.CREATED)
        } catch (_: FileAlreadyExistsException) {
            return existingPutResult(destination, bytes)
        } catch (_: UnsupportedOperationException) {
            // A non-replacing CREATE_NEW write below preserves immutability on filesystems without hard links.
        } catch (_: IOException) {
            // Some document-backed or emulated filesystems reject hard links even when ordinary files work.
        }

        return try {
            FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            SyncPutResult(SyncPutStatus.CREATED)
        } catch (_: FileAlreadyExistsException) {
            existingPutResult(destination, bytes)
        }
    }

    private fun readChecked(path: Path): ByteArray {
        checkedSize(path)
        return Files.readAllBytes(path).also {
            require(it.size in 1..SyncProviderLimits.MAX_OBJECT_BYTES) { "Encrypted sync object size changed while reading" }
        }
    }

    private fun checkedSize(path: Path): Long {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Sync object does not exist or is not a regular file" }
        return Files.size(path).also {
            require(it in 1..SyncProviderLimits.MAX_OBJECT_BYTES.toLong()) { "Encrypted sync object size is invalid" }
        }
    }

    private fun pathFor(objectId: OpaqueSyncObjectId): Path {
        val path = objectsDirectory.resolve(objectId.value + OBJECT_SUFFIX).normalize()
        require(path.parent == objectsDirectory) { "Sync object path escaped provider root" }
        return path
    }

    private companion object {
        const val OBJECT_SUFFIX = ".foe-sync"
    }
}
