package org.jianyu.core.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jianyu.core.domain.VaultRepository
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.migrateFamilyState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreVaultRepository(
    context: Context,
) : VaultRepository {
    private val vaultFile = File(context.filesDir, "family.vault")
    private val temporaryFile = File(context.filesDir, "family.vault.pending")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun load(): FamilyState? {
        // An interrupted first write can leave only this file. It is not proof of an empty vault.
        // Keep both files untouched until an explicit recovery path can resolve the ambiguity.
        check(!temporaryFile.exists()) { "Unfinished vault write requires recovery" }
        if (!vaultFile.exists()) return null
        val envelope = json.decodeFromString<EncryptedEnvelope>(vaultFile.readText())
        require(envelope.format == FORMAT) { "Unsupported vault format" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, envelope.iv.decodeBase64()))
        cipher.updateAAD(ASSOCIATED_DATA)
        val plaintext = cipher.doFinal(envelope.ciphertext.decodeBase64())
        return migrateFamilyState(json.decodeFromString(plaintext.decodeToString()))
    }

    override suspend fun save(state: FamilyState) {
        require(state.schema.startsWith("org.jianyu.family-vault/v")) { "Invalid family state schema" }
        check(!temporaryFile.exists()) { "Unfinished vault write requires recovery" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(ASSOCIATED_DATA)
        val ciphertext = cipher.doFinal(json.encodeToString(state).encodeToByteArray())
        val envelope = EncryptedEnvelope(
            format = FORMAT,
            cipher = "AES-GCM-256",
            iv = cipher.iv.encodeBase64(),
            ciphertext = ciphertext.encodeBase64(),
        )
        FileOutputStream(temporaryFile).use { stream ->
            stream.write(json.encodeToString(envelope).encodeToByteArray())
            stream.fd.sync()
        }
        Files.move(
            temporaryFile.toPath(),
            vaultFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    override suspend fun erase() {
        temporaryFile.delete()
        vaultFile.delete()
        val keyStore = keyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    override suspend fun exportEncrypted(): ByteArray {
        check(!temporaryFile.exists()) { "Unfinished vault write requires recovery" }
        check(vaultFile.exists()) { "No family vault exists" }
        return vaultFile.readBytes()
    }

    override suspend fun importEncrypted(bytes: ByteArray) {
        check(!temporaryFile.exists()) { "Unfinished vault write requires recovery" }
        val envelope = json.decodeFromString<EncryptedEnvelope>(bytes.decodeToString())
        require(envelope.format == FORMAT && envelope.ciphertext.isNotBlank()) { "Unsupported encrypted vault" }
        FileOutputStream(temporaryFile).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        Files.move(
            temporaryFile.toPath(),
            vaultFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = keyStore()
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ByteArray.encodeBase64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.decodeBase64() = Base64.decode(this, Base64.NO_WRAP)

    @Serializable
    private data class EncryptedEnvelope(
        val format: String,
        val cipher: String,
        val iv: String,
        val ciphertext: String,
    )

    private companion object {
        const val FORMAT = "org.jianyu.android-vault/v1"
        const val KEY_ALIAS = "org.jianyu.family-vault.primary"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        val ASSOCIATED_DATA = FORMAT.encodeToByteArray()
    }
}
