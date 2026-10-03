package org.jianyu.core.domain

import java.security.SecureRandom
import java.util.Base64

@JvmInline
value class OpaqueSyncObjectId private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        private val CANONICAL = Regex("[A-Za-z0-9_-]{24}")

        fun generate(random: SecureRandom = SecureRandom()): OpaqueSyncObjectId {
            val bytes = ByteArray(18).also(random::nextBytes)
            val value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            bytes.fill(0)
            return OpaqueSyncObjectId(value)
        }

        fun parse(value: String): OpaqueSyncObjectId {
            require(CANONICAL.matches(value)) { "Sync object ID is not canonical opaque Base64URL" }
            return OpaqueSyncObjectId(value)
        }
    }
}

data class OpaqueSyncObjectDescriptor(
    val objectId: OpaqueSyncObjectId,
    val sizeBytes: Long,
)

data class OpaqueSyncObjectPage(
    val objects: List<OpaqueSyncObjectDescriptor>,
    val nextCursor: String?,
)

enum class SyncPutStatus { CREATED, IDEMPOTENT }
enum class SyncDeleteStatus { DELETED, NOT_FOUND }

data class SyncPutResult(val status: SyncPutStatus)
data class SyncDeleteResult(val status: SyncDeleteStatus)

/**
 * Replaceable ciphertext-only transport. It has no key, frame parser, Family State, or merge authority.
 */
interface SyncProvider {
    val id: String

    suspend fun listOpaqueObjects(cursor: String? = null, limit: Int = 100): OpaqueSyncObjectPage
    suspend fun putEncryptedObject(objectId: OpaqueSyncObjectId, encryptedEnvelope: ByteArray): SyncPutResult
    suspend fun getEncryptedObject(objectId: OpaqueSyncObjectId): ByteArray
    suspend fun deleteEncryptedObject(objectId: OpaqueSyncObjectId): SyncDeleteResult
}

object SyncProviderLimits {
    const val MAX_OBJECT_BYTES: Int = 24 * 1024 * 1024
    const val MAX_PAGE_SIZE: Int = 100
}
