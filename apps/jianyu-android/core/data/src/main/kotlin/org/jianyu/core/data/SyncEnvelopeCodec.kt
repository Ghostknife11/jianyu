package org.jianyu.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jianyu.core.model.SyncFrame
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** High-entropy household key material. The opaque key ID may be exposed to a storage provider; the key may not. */
class HouseholdSyncKey private constructor(
    val keyId: String,
    keyMaterial: ByteArray,
) {
    private val material = keyMaterial.copyOf()

    internal fun copyMaterial(): ByteArray = material.copyOf()

    override fun toString(): String = "HouseholdSyncKey(keyId=$keyId, keyMaterial=[REDACTED])"

    companion object {
        fun fromBytes(keyId: String, keyMaterial: ByteArray): HouseholdSyncKey {
            require(KEY_ID.matches(keyId)) { "Sync key ID must be an opaque base64url-like identifier" }
            require(keyMaterial.size == KEY_BYTES) { "Household sync key must contain 256 bits" }
            return HouseholdSyncKey(keyId, keyMaterial)
        }

        fun generate(secureRandom: SecureRandom = SecureRandom()): HouseholdSyncKey {
            val keyId = ByteArray(16).also(secureRandom::nextBytes).base64Url()
            val material = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
            return HouseholdSyncKey(keyId, material).also { material.fill(0) }
        }

        private val KEY_ID = Regex("[A-Za-z0-9_-]{16,128}")
        private const val KEY_BYTES = 32
        private fun ByteArray.base64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
    }
}

/**
 * Seals one verified sync frame before a replaceable transport can observe it.
 * The envelope deliberately contains no household, child, device, frame, or tombstone identifier.
 */
class SyncEnvelopeCodec(
    private val frameCodec: SyncFrameCodec = SyncFrameCodec(),
    private val secureRandom: SecureRandom = SecureRandom(),
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    fun seal(frame: SyncFrame, key: HouseholdSyncKey): ByteArray {
        val plaintext = frameCodec.encode(frame)
        require(plaintext.size <= MAX_FRAME_BYTES) { "Sync frame exceeds encrypted-envelope limit" }
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val keyMaterial = key.copyMaterial()
        val ciphertext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyMaterial, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(key.keyId))
            cipher.doFinal(plaintext)
        } finally {
            keyMaterial.fill(0)
            plaintext.fill(0)
        }
        val envelope = EncryptedSyncEnvelope(
            keyId = key.keyId,
            nonce = nonce.base64Url(),
            ciphertext = ciphertext.base64Url(),
        )
        return json.encodeToString(envelope).encodeToByteArray()
    }

    fun open(bytes: ByteArray, key: HouseholdSyncKey): SyncFrame {
        require(bytes.size in 1..MAX_ENVELOPE_BYTES) { "Encrypted sync envelope size is invalid" }
        val text = strictUtf8(bytes)
        val envelope = runCatching { json.decodeFromString<EncryptedSyncEnvelope>(text) }
            .getOrElse { throw IllegalArgumentException("Encrypted sync envelope is invalid") }
        require(envelope.format == FORMAT) { "Unsupported encrypted sync envelope format" }
        require(envelope.cipher == CIPHER_ID) { "Unsupported encrypted sync cipher" }
        require(envelope.keyId == key.keyId) { "Encrypted sync envelope uses another key" }

        val nonce = envelope.nonce.decodeBase64Url("nonce")
        require(nonce.size == NONCE_BYTES) { "Encrypted sync envelope nonce is invalid" }
        val ciphertext = envelope.ciphertext.decodeBase64Url("ciphertext")
        require(ciphertext.size in TAG_BYTES..(MAX_FRAME_BYTES + TAG_BYTES)) { "Encrypted sync ciphertext size is invalid" }
        val keyMaterial = key.copyMaterial()
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyMaterial, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(envelope.keyId))
            try {
                cipher.doFinal(ciphertext)
            } catch (_: AEADBadTagException) {
                throw IllegalArgumentException("Encrypted sync key is wrong or the envelope was modified")
            }
        } finally {
            keyMaterial.fill(0)
        }
        return try {
            frameCodec.decodeAndVerify(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun aad(keyId: String) = "$FORMAT|$CIPHER_ID|$keyId|sync-frame".encodeToByteArray()

    private fun strictUtf8(bytes: ByteArray): String = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse { throw IllegalArgumentException("Encrypted sync envelope is not valid UTF-8") }

    private fun ByteArray.base64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun String.decodeBase64Url(field: String): ByteArray = runCatching { Base64.getUrlDecoder().decode(this) }
        .getOrElse { throw IllegalArgumentException("Encrypted sync $field is invalid") }

    @Serializable
    private data class EncryptedSyncEnvelope(
        val format: String = FORMAT,
        val cipher: String = CIPHER_ID,
        val keyId: String,
        val nonce: String,
        val ciphertext: String,
    )

    private companion object {
        const val FORMAT = "org.foe.encrypted-sync-envelope/v1"
        const val CIPHER_ID = "AES-256-GCM"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024
        const val MAX_ENVELOPE_BYTES = 24 * 1024 * 1024
    }
}
