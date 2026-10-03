package org.jianyu.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.migrateFamilyState
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class PortableFamilyExport(
    val bytes: ByteArray,
    val recoveryCode: String,
)

class PortableFamilyBundleCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val clock: () -> Instant = Instant::now,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun create(state: FamilyState): PortableFamilyExport {
        val key = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val bundleId = UUID.randomUUID().toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(bundleId))
        val ciphertext = cipher.doFinal(json.encodeToString(state).encodeToByteArray())
        val envelope = PortableEnvelope(
            bundleId = bundleId,
            createdAt = clock().toString(),
            nonce = nonce.base64Url(),
            ciphertext = ciphertext.base64Url(),
        )
        return PortableFamilyExport(
            bytes = json.encodeToString(envelope).encodeToByteArray(),
            recoveryCode = key.base64Url(),
        )
    }

    fun open(bytes: ByteArray, recoveryCode: String): FamilyState {
        require(bytes.size in 1..MAX_BUNDLE_BYTES) { "恢复包大小无效" }
        val envelope = runCatching { json.decodeFromString<PortableEnvelope>(bytes.decodeToString()) }
            .getOrElse { throw IllegalArgumentException("恢复包格式无效") }
        require(envelope.format == FORMAT && envelope.cipher == CIPHER_ID) { "不支持的恢复包版本" }
        val key = runCatching { recoveryCode.trim().decodeBase64Url() }
            .getOrElse { throw IllegalArgumentException("恢复码格式无效") }
        require(key.size == KEY_BYTES) { "恢复码格式无效" }
        val nonce = runCatching { envelope.nonce.decodeBase64Url() }
            .getOrElse { throw IllegalArgumentException("恢复包随机数无效") }
        require(nonce.size == NONCE_BYTES) { "恢复包随机数无效" }
        val ciphertext = runCatching { envelope.ciphertext.decodeBase64Url() }
            .getOrElse { throw IllegalArgumentException("恢复包密文无效") }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(envelope.bundleId))
        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("恢复码错误或恢复包已被修改")
        }
        val state = runCatching { json.decodeFromString<FamilyState>(plaintext.decodeToString()) }
            .getOrElse { throw IllegalArgumentException("恢复包中的家庭数据无效") }
        return migrateFamilyState(state)
    }

    private fun aad(bundleId: String) = "$FORMAT|$bundleId|family-state-json".encodeToByteArray()
    private fun ByteArray.base64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
    private fun String.decodeBase64Url() = Base64.getUrlDecoder().decode(this)

    @Serializable
    private data class PortableEnvelope(
        val format: String = FORMAT,
        val cipher: String = CIPHER_ID,
        val bundleId: String,
        val createdAt: String,
        val nonce: String,
        val ciphertext: String,
    )

    private companion object {
        const val FORMAT = "org.foe.portable-family-bundle/v1"
        const val CIPHER_ID = "AES-256-GCM"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_BUNDLE_BYTES = 16 * 1024 * 1024
    }
}
