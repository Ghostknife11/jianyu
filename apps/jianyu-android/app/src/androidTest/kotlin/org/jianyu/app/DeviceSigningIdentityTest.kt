package org.jianyu.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.jianyu.core.data.AndroidKeystoreDeviceSigningIdentity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** Synthetic proof bytes only; runs on the disposable Android test AVD. */
@RunWith(AndroidJUnit4::class)
class DeviceSigningIdentityTest {
    @Test
    fun explicitCreationPersistsNonExportableKeyAndVerifiesOnlyTheExactProof() {
        val identity = AndroidKeystoreDeviceSigningIdentity()
        val alias = "org.jianyu.device-signing-identity.v1"
        check(identity.load() == null) { "Disposable test AVD unexpectedly has a device signing identity" }
        try {
            val first = identity.createIfAbsent()
            assertEquals(first, identity.load())
            assertEquals(first, identity.createIfAbsent())
            assertEquals("P-256-SHA256-ECDSA", first.algorithm)
            assertEquals(64, first.fingerprintSha256.length)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            assertNull(keyStore.getKey(alias, null).encoded)

            val proof = ByteArray(32) { it.toByte() }
            val original = proof.copyOf()
            val signature = identity.signProof(proof)
            assertArrayEquals(original, proof)
            assertTrue(AndroidKeystoreDeviceSigningIdentity.verifyProof(first.publicKeySpki, proof, signature))
            assertFalse(AndroidKeystoreDeviceSigningIdentity.verifyProof(first.publicKeySpki, proof.copyOf().also {
                it[0] = (it[0].toInt() xor 1).toByte()
            }, signature))
            assertFalse(AndroidKeystoreDeviceSigningIdentity.verifyProof(first.publicKeySpki, proof, signature.copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }))
            assertFalse(AndroidKeystoreDeviceSigningIdentity.verifyProof("not-base64!", proof, signature))
            assertFalse(AndroidKeystoreDeviceSigningIdentity.verifyProof(first.publicKeySpki, byteArrayOf(), signature))

            val otherCurve = KeyPairGenerator.getInstance("EC").run {
                initialize(ECGenParameterSpec("secp384r1"))
                generateKeyPair()
            }
            val otherSpki = Base64.getUrlEncoder().withoutPadding().encodeToString(otherCurve.public.encoded)
            assertFalse(AndroidKeystoreDeviceSigningIdentity.verifyProof(otherSpki, proof, signature))
        } finally {
            identity.erase()
        }
        assertNull(identity.load())
        assertTrue(runCatching { identity.signProof(ByteArray(32)) }.isFailure)
        assertNull(identity.load())
    }
}
