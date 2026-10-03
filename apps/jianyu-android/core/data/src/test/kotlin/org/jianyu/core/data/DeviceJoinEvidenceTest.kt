package org.jianyu.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant

class DeviceJoinEvidenceTest {
    private val codec = DeviceJoinEvidenceCodec()
    private val now = Instant.parse("2026-10-02T08:00:00Z")
    private val inviter = keyPair()
    private val candidate = keyPair()
    private val inviterPublic = DeviceProofSignatures.publicIdentity(inviter.public).publicKeySpki
    private val candidatePublic = DeviceProofSignatures.publicIdentity(candidate.public).publicKeySpki

    @Test
    fun `signed invitation request and approval bind the same household keys and transcript`() {
        val invitation = invitation()
        val request = request(invitation)
        val approval = approval(invitation, request)

        codec.verifyTranscript(invitation, request, approval, "household_demo", inviterPublic, now.plusSeconds(180))
        assertEquals(invitation, codec.decodeInvitation(codec.encodeInvitation(invitation)))
        assertEquals(request, codec.decodeRequest(codec.encodeRequest(request)))
        assertEquals(approval, codec.decodeApproval(codec.encodeApproval(approval)))
        assertTrue(invitation.challenge != request.candidateChallenge)
        assertTrue(invitation.inviterPublicKeySpki != request.candidatePublicKeySpki)
    }

    @Test
    fun `changed household key challenge attribution or digest cannot validate`() {
        val invitation = invitation()
        val request = request(invitation)
        val approval = approval(invitation, request)
        val at = now.plusSeconds(180)

        assertFails { codec.verifyTranscript(invitation, request, approval, "other_household", inviterPublic, at) }
        assertFails { codec.verifyTranscript(invitation, request, approval, "household_demo", candidatePublic, at) }
        assertFails { codec.verifyTranscript(invitation.copy(householdId = "other_household"), request, approval, "other_household", inviterPublic, at) }
        assertFails { codec.verifyTranscript(invitation, request.copy(candidateChallenge = invitation.challenge), approval, "household_demo", inviterPublic, at) }
        assertFails { codec.verifyTranscript(invitation, request.copy(invitationDigest = "0".repeat(64)), approval, "household_demo", inviterPublic, at) }
        assertFails { codec.verifyTranscript(invitation, request.copy(candidatePublicKeySpki = inviterPublic), approval, "household_demo", inviterPublic, at) }
        assertFails { codec.verifyTranscript(invitation, request, approval.copy(attributedActorId = "someone_else"), "household_demo", inviterPublic, at) }
        assertFails { codec.verifyTranscript(invitation, request, approval.copy(requestDigest = "0".repeat(64)), "household_demo", inviterPublic, at) }
    }

    @Test
    fun `expired future malformed and cross-session evidence fails closed`() {
        val invitation = invitation()
        val request = request(invitation)
        val approval = approval(invitation, request)
        val anotherInvitation = invitation()

        assertFails { codec.verifyTranscript(invitation, request, approval, "household_demo", inviterPublic, now.plusSeconds(601)) }
        assertFails { codec.verifyTranscript(invitation, request, approval, "household_demo", inviterPublic, now.minusSeconds(301)) }
        assertFails { codec.verifyTranscript(anotherInvitation, request, approval, "household_demo", inviterPublic, now.plusSeconds(180)) }
        val futureRequest = codec.createRequest(
            invitation, candidatePublic, now.plusSeconds(540),
        ) { DeviceProofSignatures.sign(candidate.private, it) }
        val futureApproval = codec.createApprovalEvidence(
            invitation, futureRequest, "caregiver_demo", now.plusSeconds(550),
        ) { DeviceProofSignatures.sign(inviter.private, it) }
        assertFails { codec.verifyTranscript(invitation, futureRequest, futureApproval, "household_demo", inviterPublic, now.plusSeconds(60)) }
        assertFails { codec.decodeInvitation(codec.encodeInvitation(invitation).replace("org.foe.device-join-invitation/v1", "org.foe.device-join-invitation/v2"))
            .also { codec.verifyTranscript(it, request, approval, "household_demo", inviterPublic, now.plusSeconds(180)) } }
        assertFails { codec.decodeInvitation(codec.encodeInvitation(invitation).dropLast(1) + ",\"unknown\":true}") }
        assertFails { codec.decodeInvitation(codec.encodeInvitation(invitation).dropLast(1) + ",\"householdId\":\"other_household\"}") }
        assertFails { codec.decodeInvitation("  " + codec.encodeInvitation(invitation)) }
        assertFails { codec.decodeRequest("x".repeat(9_000)) }
    }

    private fun invitation() = codec.createInvitation(
        "household_demo", inviterPublic, now, now.plusSeconds(600),
    ) { DeviceProofSignatures.sign(inviter.private, it) }

    private fun request(invitation: DeviceJoinInvitation) = codec.createRequest(
        invitation, candidatePublic, now.plusSeconds(60),
    ) { DeviceProofSignatures.sign(candidate.private, it) }

    private fun approval(invitation: DeviceJoinInvitation, request: DeviceJoinRequest) = codec.createApprovalEvidence(
        invitation, request, "caregiver_demo", now.plusSeconds(120),
    ) { DeviceProofSignatures.sign(inviter.private, it) }

    private fun keyPair() = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun assertFails(block: () -> Unit) {
        assertTrue("Expected device join evidence to fail closed", runCatching(block).isFailure)
    }
}
