package org.jianyu.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-local encrypted settings with one purpose-specific Android Keystore key. */
internal class AndroidKeystoreJsonStore<T>(
    context: Context,
    fileName: String,
    private val format: String,
    private val keyAlias: String,
    private val serializer: KSerializer<T>,
    private val validate: (T) -> Unit,
) {
    private val file = File(context.filesDir, fileName)
    private val pending = File(context.filesDir, "$fileName.pending")
    private val envelopeJson = Json { ignoreUnknownKeys = false }
    private val payloadJson = Json { ignoreUnknownKeys = true }
    private val aad = format.encodeToByteArray()

    fun load(): T? {
        if (!file.exists()) return null
        require(file.length() in 1..MAX_SETTINGS_FILE_BYTES) { "Encrypted device settings size is invalid" }
        val envelope = envelopeJson.decodeFromString(SecretEnvelope.serializer(), file.readText())
        require(envelope.format == format) { "Unsupported encrypted device settings format" }
        val nonce = envelope.iv.decode64()
        require(nonce.size == GCM_NONCE_BYTES) { "Encrypted device settings nonce is invalid" }
        val ciphertext = envelope.ciphertext.decode64()
        require(ciphertext.size in GCM_TAG_BYTES..MAX_SETTINGS_FILE_BYTES.toInt()) { "Encrypted device settings ciphertext size is invalid" }
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(ciphertext)
        } finally {
            nonce.fill(0)
            ciphertext.fill(0)
        }
        return try {
            payloadJson.decodeFromString(serializer, plaintext.decodeToString(throwOnInvalidSequence = true)).also(validate)
        } finally {
            plaintext.fill(0)
        }
    }

    fun save(value: T) {
        validate(value)
        val plaintext = payloadJson.encodeToString(serializer, value).encodeToByteArray()
        require(plaintext.size in 1..MAX_SETTINGS_PAYLOAD_BYTES) { "Device settings payload size is invalid" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val ciphertext = try {
            cipher.init(Cipher.ENCRYPT_MODE, keyForWrite())
            cipher.updateAAD(aad)
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val envelope = SecretEnvelope(format, cipher.iv.encode64(), ciphertext.encode64())
        ciphertext.fill(0)
        FileOutputStream(pending).use { stream ->
            stream.write(envelopeJson.encodeToString(SecretEnvelope.serializer(), envelope).encodeToByteArray())
            stream.fd.sync()
        }
        try {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun erase() {
        pending.delete()
        file.delete()
        keyStore().let { if (it.containsAlias(keyAlias)) it.deleteEntry(keyAlias) }
    }

    private fun existingKey(): SecretKey =
        keyStore().getKey(keyAlias, null) as? SecretKey
            ?: throw IllegalStateException("Encrypted device settings key is unavailable")

    private fun keyForWrite(): SecretKey {
        val store = keyStore()
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun ByteArray.encode64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decode64() = runCatching { Base64.decode(this, Base64.NO_WRAP) }
        .getOrElse { throw IllegalArgumentException("Encrypted device settings Base64 is invalid") }

    @Serializable
    private data class SecretEnvelope(val format: String, val iv: String, val ciphertext: String)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
        const val GCM_TAG_BITS = 128
        const val MAX_SETTINGS_PAYLOAD_BYTES = 256 * 1024
        const val MAX_SETTINGS_FILE_BYTES = 512L * 1024L
    }
}
