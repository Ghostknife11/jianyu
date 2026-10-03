package org.jianyu.core.data

import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

data class DeviceSigningPublicIdentity(
    val algorithm: String,
    val publicKeySpki: String,
    val fingerprintSha256: String,
)

/** Portable verification/signing-byte rules; private keys are supplied by a platform key store. */
object DeviceProofSignatures {
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val MAX_PROOF_BYTES = 8 * 1024
    private val PROOF_DOMAIN = "org.foe.device-enrollment-proof/v1\u0000".encodeToByteArray()

    fun publicIdentity(key: PublicKey): DeviceSigningPublicIdentity {
        val encoded = requireP256(key).encoded
        require(encoded.size in 80..256) { "Device public key encoding is invalid" }
        return DeviceSigningPublicIdentity(
            algorithm = "P-256-SHA256-ECDSA",
            publicKeySpki = Base64.getUrlEncoder().withoutPadding().encodeToString(encoded),
            fingerprintSha256 = MessageDigest.getInstance("SHA-256")
                .digest(encoded)
                .joinToString("") { byte -> "%02x".format(byte) },
        )
    }

    fun sign(privateKey: PrivateKey, proofBytes: ByteArray): ByteArray {
        require(proofBytes.size in 1..MAX_PROOF_BYTES) { "Device proof size is invalid" }
        return Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(PROOF_DOMAIN)
            update(proofBytes)
            sign()
        }
    }

    fun verify(publicKeySpki: String, proofBytes: ByteArray, signatureBytes: ByteArray): Boolean {
        if (publicKeySpki.length !in 100..350 ||
            proofBytes.size !in 1..MAX_PROOF_BYTES ||
            signatureBytes.size !in 8..256
        ) return false
        return try {
            val encoded = Base64.getUrlDecoder().decode(publicKeySpki)
            if (encoded.size !in 80..256 ||
                Base64.getUrlEncoder().withoutPadding().encodeToString(encoded) != publicKeySpki
            ) return false
            val publicKey = requireP256(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)))
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(publicKey)
                update(PROOF_DOMAIN)
                update(proofBytes)
                verify(signatureBytes)
            }
        } catch (_: java.security.GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private fun requireP256(key: PublicKey): ECPublicKey {
        val ec = key as? ECPublicKey ?: throw IllegalArgumentException("Device public key must be EC")
        val expected = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
        val actual = ec.params
        val actualField = actual.curve.field as? ECFieldFp
        val expectedField = expected.curve.field as ECFieldFp
        require(
            actualField?.p == expectedField.p &&
                actual.curve.a == expected.curve.a &&
                actual.curve.b == expected.curve.b &&
                actual.generator == expected.generator &&
                actual.order == expected.order &&
                actual.cofactor == expected.cofactor,
        ) { "Device public key must use P-256" }
        return ec
    }
}
