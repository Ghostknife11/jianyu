package org.jianyu.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/**
 * Device-local proof-of-possession primitive. A public key has no household authority until a
 * separate, explicitly approved enrollment protocol admits it. No sync frame uses this yet.
 */
class AndroidKeystoreDeviceSigningIdentity {
    fun load(): DeviceSigningPublicIdentity? {
        val store = keyStore()
        if (!store.containsAlias(KEY_ALIAS)) return null
        require(store.getKey(KEY_ALIAS, null) is java.security.PrivateKey) {
            "Device signing identity has no private key"
        }
        val publicKey = requireNotNull(store.getCertificate(KEY_ALIAS)) {
            "Device signing identity has no public certificate"
        }.publicKey
        return DeviceProofSignatures.publicIdentity(publicKey)
    }

    fun createIfAbsent(): DeviceSigningPublicIdentity = synchronized(CREATION_LOCK) {
        load()?.let { return@synchronized it }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        generator.generateKeyPair()
        requireNotNull(load()) { "Device signing identity could not be loaded after creation" }
    }

    fun signProof(proofBytes: ByteArray): ByteArray {
        val privateKey = keyStore().getKey(KEY_ALIAS, null) as? java.security.PrivateKey
            ?: throw IllegalStateException("Device signing identity is unavailable")
        return DeviceProofSignatures.sign(privateKey, proofBytes)
    }

    fun erase() {
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) store.deleteEntry(KEY_ALIAS)
    }

    companion object {
        private const val KEY_ALIAS = "org.jianyu.device-signing-identity.v1"
        private val CREATION_LOCK = Any()

        fun verifyProof(publicKeySpki: String, proofBytes: ByteArray, signatureBytes: ByteArray): Boolean =
            DeviceProofSignatures.verify(publicKeySpki, proofBytes, signatureBytes)

        private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }
}
