package org.jianyu.core.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
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
import java.io.ByteArrayOutputStream

/** Android Storage Access Framework transport for already encrypted, opaque sync objects. */
class DocumentTreeSyncProvider(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    override val id: String = "org.foe.sync.android-document-tree/v1",
) : SyncProvider {
    private val rootDocumentUri: Uri by lazy {
        require(treeUri.scheme == ContentResolver.SCHEME_CONTENT) { "Sync folder must use a content URI" }
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
    }

    override suspend fun listOpaqueObjects(cursor: String?, limit: Int): OpaqueSyncObjectPage = withContext(Dispatchers.IO) {
        require(limit in 1..SyncProviderLimits.MAX_PAGE_SIZE) { "Sync page size is outside supported range" }
        val after = cursor?.let(OpaqueSyncObjectId::parse)?.value
        val documents = listChildren(objectsDirectory())
            .filter { it.name.endsWith(OBJECT_SUFFIX) }
            .map { child ->
                require(child.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) { "Sync object must be a file" }
                val objectId = OpaqueSyncObjectId.parse(child.name.removeSuffix(OBJECT_SUFFIX))
                require(child.size in 1..SyncProviderLimits.MAX_OBJECT_BYTES.toLong()) { "Sync object size is invalid" }
                objectId to child
            }
            .sortedBy { it.first.value }
        val remaining = if (after == null) documents else documents.dropWhile { it.first.value <= after }
        val page = remaining.take(limit)
        OpaqueSyncObjectPage(
            objects = page.map { (objectId, child) -> OpaqueSyncObjectDescriptor(objectId, child.size) },
            nextCursor = page.lastOrNull()?.first?.value?.takeIf { remaining.size > page.size },
        )
    }

    override suspend fun putEncryptedObject(
        objectId: OpaqueSyncObjectId,
        encryptedEnvelope: ByteArray,
    ): SyncPutResult = withContext(Dispatchers.IO) {
        require(encryptedEnvelope.size in 1..SyncProviderLimits.MAX_OBJECT_BYTES) { "Encrypted sync object size is invalid" }
        val directory = objectsDirectory()
        val name = objectId.value + OBJECT_SUFFIX
        findUniqueChild(directory, name)?.let { existing ->
            val bytes = readChecked(existing)
            try {
                require(bytes.contentEquals(encryptedEnvelope)) { "Immutable sync object ID already contains different ciphertext" }
                return@withContext SyncPutResult(SyncPutStatus.IDEMPOTENT)
            } finally {
                bytes.fill(0)
            }
        }
        val created = requireNotNull(
            DocumentsContract.createDocument(resolver, directory, MIME_TYPE, name),
        ) { "The selected folder refused to create a sync object" }
        try {
            val metadata = metadata(created)
            require(metadata.name == name) { "The selected folder changed the opaque sync object name" }
            require(metadata.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) { "Sync object was created as a directory" }
            requireNotNull(resolver.openOutputStream(created, "wt")) { "The selected folder refused sync object output" }
                .use { stream ->
                    stream.write(encryptedEnvelope)
                    stream.flush()
                }
            val written = readChecked(metadata(created))
            try {
                require(written.contentEquals(encryptedEnvelope)) { "Sync object verification failed after writing" }
            } finally {
                written.fill(0)
            }
            SyncPutResult(SyncPutStatus.CREATED)
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw error
        }
    }

    override suspend fun getEncryptedObject(objectId: OpaqueSyncObjectId): ByteArray = withContext(Dispatchers.IO) {
        val child = requireNotNull(findUniqueChild(objectsDirectory(), objectId.value + OBJECT_SUFFIX)) {
            "Sync object does not exist"
        }
        readChecked(child)
    }

    override suspend fun deleteEncryptedObject(objectId: OpaqueSyncObjectId): SyncDeleteResult = withContext(Dispatchers.IO) {
        val child = findUniqueChild(objectsDirectory(), objectId.value + OBJECT_SUFFIX)
            ?: return@withContext SyncDeleteResult(SyncDeleteStatus.NOT_FOUND)
        require(DocumentsContract.deleteDocument(resolver, child.uri)) { "The selected folder refused sync object deletion" }
        SyncDeleteResult(SyncDeleteStatus.DELETED)
    }

    /** Forces tree and write-access validation without exposing family data. */
    suspend fun validateAccess() = withContext(Dispatchers.IO) {
        objectsDirectory()
        Unit
    }

    private fun objectsDirectory(): Uri {
        val existing = listChildren(rootDocumentUri).filter { it.name == OBJECTS_DIRECTORY }
        require(existing.size <= 1) { "Selected folder contains duplicate sync directory names" }
        existing.singleOrNull()?.let { child ->
            require(child.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) { "Sync directory name is occupied by a file" }
            return child.uri
        }
        val created = requireNotNull(
            DocumentsContract.createDocument(
                resolver,
                rootDocumentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                OBJECTS_DIRECTORY,
            ),
        ) { "The selected folder refused the sync directory" }
        val metadata = metadata(created)
        require(
            metadata.name == OBJECTS_DIRECTORY && metadata.mimeType == DocumentsContract.Document.MIME_TYPE_DIR,
        ) { "The selected folder changed the sync directory name or type" }
        return created
    }

    private fun findUniqueChild(parent: Uri, name: String): ChildDocument? {
        val matches = listChildren(parent).filter { it.name == name }
        require(matches.size <= 1) { "Selected folder contains duplicate opaque object names" }
        return matches.singleOrNull()
    }

    private fun listChildren(parent: Uri): List<ChildDocument> {
        val documentId = DocumentsContract.getDocumentId(parent)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val result = mutableListOf<ChildDocument>()
        resolver.query(childrenUri, PROJECTION, null, null, null).use { cursor ->
            requireNotNull(cursor) { "The selected folder refused to list documents" }
            while (cursor.moveToNext()) {
                require(result.size < MAX_DIRECTORY_ENTRIES) { "Selected folder contains too many entries" }
                val childId = cursor.getString(0)
                val name = cursor.getString(1)
                val mimeType = cursor.getString(2)
                val size = if (cursor.isNull(3)) 0L else cursor.getLong(3)
                result += ChildDocument(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                    name = name,
                    mimeType = mimeType,
                    size = size,
                )
            }
        }
        return result
    }

    private fun metadata(uri: Uri): ChildDocument {
        resolver.query(uri, PROJECTION, null, null, null).use { cursor ->
            require(cursor != null && cursor.moveToFirst()) { "The selected folder refused document metadata" }
            return ChildDocument(
                uri = uri,
                name = cursor.getString(1),
                mimeType = cursor.getString(2),
                size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
            )
        }
    }

    private fun readChecked(child: ChildDocument): ByteArray {
        require(child.mimeType != DocumentsContract.Document.MIME_TYPE_DIR) { "Sync object must be a file" }
        require(child.size in 1..SyncProviderLimits.MAX_OBJECT_BYTES.toLong()) { "Sync object size is invalid" }
        val stream = requireNotNull(resolver.openInputStream(child.uri)) { "The selected folder refused sync object input" }
        return stream.use { input ->
            val output = ByteArrayOutputStream(child.size.toInt())
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= SyncProviderLimits.MAX_OBJECT_BYTES) { "Sync object grew beyond the size limit" }
                output.write(buffer, 0, count)
            }
            require(total.toLong() == child.size) { "Sync object size changed while reading" }
            output.toByteArray()
        }
    }

    private data class ChildDocument(val uri: Uri, val name: String, val mimeType: String, val size: Long)

    private companion object {
        const val OBJECTS_DIRECTORY = "objects"
        const val OBJECT_SUFFIX = ".foe-sync"
        const val MIME_TYPE = "application/octet-stream"
        const val MAX_DIRECTORY_ENTRIES = 4_000
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
    }
}
