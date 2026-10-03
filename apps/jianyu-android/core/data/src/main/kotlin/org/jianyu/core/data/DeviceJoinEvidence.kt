package org.jianyu.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

@Serializable
data class DeviceJoinInvitation(
    val schema: String = "org.foe.device-join-invitation/v1",
    val householdId: String,
    val inviterPublicKeySpki: String,
    val challenge: String,
    val createdAt: String,
    val expiresAt: String,
    val signature: String,
)

@Serializable
data class DeviceJoinRequest(
    val schema: String = "org.foe.device-join-request/v1",
    val invitationDigest: String,
    val candidatePublicKeySpki: String,
    val candidateChallenge: String,
    val createdAt: String,
    val signature: String,
)

@Serializable
data class DeviceJoinApprovalEvidence(
    val schema: String = "org.foe.device-join-approval-evidence/v1",
    val requestDigest: String,
    val inviterPublicKeySpki: String,
    val attributedActorId: String,
    val approvedAt: String,
    val signature: String,
)

/** Validates signed evidence only. It never enrolls a device or releases a household key. */
class DeviceJoinEvidenceCodec(
    private val random: SecureRandom = SecureRandom(),
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    fun createInvitation(
        householdId: String,
        inviterPublicKeySpki: String,
        createdAt: Instant,
        expiresAt: Instant,
        sign: (ByteArray) -> ByteArray,
    ): DeviceJoinInvitation {
        val challenge = ByteArray(CHALLENGE_BYTES).also(random::nextBytes).toBase64Url()
        val unsigned = DeviceJoinInvitation(
            householdId = householdId,
            inviterPublicKeySpki = inviterPublicKeySpki,
            challenge = challenge,
            createdAt = createdAt.toString(),
            expiresAt = expiresAt.toString(),
            signature = "",
        )
        validateInvitationFields(unsigned)
        return unsigned.copy(signature = sign(invitationBytes(unsigned)).toBase64Url()).also {
            verifyInvitation(it, householdId, inviterPublicKeySpki, createdAt)
        }
    }

    fun createRequest(
        invitation: DeviceJoinInvitation,
        candidatePublicKeySpki: String,
        createdAt: Instant,
        sign: (ByteArray) -> ByteArray,
    ): DeviceJoinRequest {
        verifyInvitation(invitation, invitation.householdId, invitation.inviterPublicKeySpki, createdAt)
        val candidateChallenge = ByteArray(CHALLENGE_BYTES).also(random::nextBytes).toBase64Url()
        val unsigned = DeviceJoinRequest(
            invitationDigest = invitationDigest(invitation),
            candidatePublicKeySpki = candidatePublicKeySpki,
            candidateChallenge = candidateChallenge,
            createdAt = createdAt.toString(),
            signature = "",
        )
        validateRequestFields(invitation, unsigned)
        return unsigned.copy(signature = sign(requestBytes(unsigned)).toBase64Url()).also {
            verifyRequest(invitation, it, createdAt)
        }
    }

    fun createApprovalEvidence(
        invitation: DeviceJoinInvitation,
        request: DeviceJoinRequest,
        attributedActorId: String,
        approvedAt: Instant,
        sign: (ByteArray) -> ByteArray,
    ): DeviceJoinApprovalEvidence {
        verifyRequest(invitation, request, approvedAt)
        val unsigned = DeviceJoinApprovalEvidence(
            requestDigest = requestDigest(request),
            inviterPublicKeySpki = invitation.inviterPublicKeySpki,
            attributedActorId = attributedActorId,
            approvedAt = approvedAt.toString(),
            signature = "",
        )
        validateApprovalFields(invitation, request, unsigned)
        return unsigned.copy(signature = sign(approvalBytes(unsigned)).toBase64Url()).also {
            verifyTranscript(invitation, request, it, invitation.householdId, invitation.inviterPublicKeySpki, approvedAt)
        }
    }

    fun verifyTranscript(
        invitation: DeviceJoinInvitation,
        request: DeviceJoinRequest,
        approval: DeviceJoinApprovalEvidence,
        expectedHouseholdId: String,
        expectedInviterPublicKeySpki: String,
        now: Instant,
    ) {
        verifyInvitation(invitation, expectedHouseholdId, expectedInviterPublicKeySpki, now)
        verifyRequest(invitation, request, now)
        validateApprovalFields(invitation, request, approval)
        require(!now.isBefore(Instant.parse(approval.approvedAt).minus(CLOCK_SKEW))) {
            "Device join approval is from the future"
        }
        require(verify(approval.inviterPublicKeySpki, approvalBytes(approval), approval.signature)) {
            "Device join approval signature is invalid"
        }
    }

    fun encodeInvitation(value: DeviceJoinInvitation): String = json.encodeToString(value)
    fun encodeRequest(value: DeviceJoinRequest): String = json.encodeToString(value)
    fun encodeApproval(value: DeviceJoinApprovalEvidence): String = json.encodeToString(value)

    fun decodeInvitation(value: String): DeviceJoinInvitation = boundedJson(value) {
        json.decodeFromString<DeviceJoinInvitation>(value).also {
            require(encodeInvitation(it) == value) { "Device join invitation JSON is not canonical" }
        }
    }
    fun decodeRequest(value: String): DeviceJoinRequest = boundedJson(value) {
        json.decodeFromString<DeviceJoinRequest>(value).also {
            require(encodeRequest(it) == value) { "Device join request JSON is not canonical" }
        }
    }
    fun decodeApproval(value: String): DeviceJoinApprovalEvidence = boundedJson(value) {
        json.decodeFromString<DeviceJoinApprovalEvidence>(value).also {
            require(encodeApproval(it) == value) { "Device join approval JSON is not canonical" }
        }
    }

    private fun verifyInvitation(
        invitation: DeviceJoinInvitation,
        expectedHouseholdId: String,
        expectedInviterPublicKeySpki: String,
        now: Instant,
    ) {
        validateInvitationFields(invitation)
        require(invitation.householdId == expectedHouseholdId) { "Device join household mismatch" }
        require(invitation.inviterPublicKeySpki == expectedInviterPublicKeySpki) { "Device join inviter key mismatch" }
        val created = Instant.parse(invitation.createdAt)
        val expires = Instant.parse(invitation.expiresAt)
        require(!now.isBefore(created.minus(CLOCK_SKEW)) && !now.isAfter(expires)) {
            "Device join invitation is not current"
        }
        require(verify(invitation.inviterPublicKeySpki, invitationBytes(invitation), invitation.signature)) {
            "Device join invitation signature is invalid"
        }
    }

    private fun verifyRequest(invitation: DeviceJoinInvitation, request: DeviceJoinRequest, now: Instant) {
        verifyInvitation(invitation, invitation.householdId, invitation.inviterPublicKeySpki, now)
        validateRequestFields(invitation, request)
        require(!now.isBefore(Instant.parse(request.createdAt).minus(CLOCK_SKEW))) {
            "Device join request is from the future"
        }
        require(verify(request.candidatePublicKeySpki, requestBytes(request), request.signature)) {
            "Device join request signature is invalid"
        }
    }

    private fun validateInvitationFields(value: DeviceJoinInvitation) {
        require(value.schema == INVITATION_SCHEMA) { "Unsupported device join invitation" }
        require(ID.matches(value.householdId)) { "Device join household ID is invalid" }
        require(value.inviterPublicKeySpki.length in 100..350) { "Device join inviter key size is invalid" }
        require(value.challenge.decodeBase64Url()?.size == CHALLENGE_BYTES) { "Device join challenge is invalid" }
        val created = canonicalInstant(value.createdAt)
        val expires = canonicalInstant(value.expiresAt)
        val lifetime = Duration.between(created, expires)
        require(lifetime > Duration.ZERO && lifetime <= MAX_LIFETIME) { "Device join invitation lifetime is invalid" }
    }

    private fun validateRequestFields(invitation: DeviceJoinInvitation, value: DeviceJoinRequest) {
        require(value.schema == REQUEST_SCHEMA) { "Unsupported device join request" }
        require(value.invitationDigest == invitationDigest(invitation)) { "Device join invitation digest mismatch" }
        require(value.candidatePublicKeySpki.length in 100..350) { "Device join candidate key size is invalid" }
        require(value.candidatePublicKeySpki != invitation.inviterPublicKeySpki) { "A device cannot join itself" }
        require(value.candidateChallenge.decodeBase64Url()?.size == CHALLENGE_BYTES) {
            "Device join candidate challenge is invalid"
        }
        require(value.candidateChallenge != invitation.challenge) { "Device join challenges must differ" }
        val created = canonicalInstant(value.createdAt)
        require(!created.isBefore(Instant.parse(invitation.createdAt).minus(CLOCK_SKEW)) &&
            !created.isAfter(Instant.parse(invitation.expiresAt))) { "Device join request time is invalid" }
    }

    private fun validateApprovalFields(
        invitation: DeviceJoinInvitation,
        request: DeviceJoinRequest,
        value: DeviceJoinApprovalEvidence,
    ) {
        require(value.schema == APPROVAL_SCHEMA) { "Unsupported device join approval evidence" }
        require(value.requestDigest == requestDigest(request)) { "Device join request digest mismatch" }
        require(value.inviterPublicKeySpki == invitation.inviterPublicKeySpki) { "Device join approver key mismatch" }
        require(ID.matches(value.attributedActorId)) { "Device join actor attribution is invalid" }
        val approved = canonicalInstant(value.approvedAt)
        require(!approved.isBefore(Instant.parse(request.createdAt).minus(CLOCK_SKEW)) &&
            !approved.isAfter(Instant.parse(invitation.expiresAt))) { "Device join approval time is invalid" }
    }

    private fun invitationBytes(value: DeviceJoinInvitation) = signedBytes(
        INVITATION_SCHEMA, value.householdId, value.inviterPublicKeySpki,
        value.challenge, value.createdAt, value.expiresAt,
    )

    private fun requestBytes(value: DeviceJoinRequest) = signedBytes(
        REQUEST_SCHEMA, value.invitationDigest, value.candidatePublicKeySpki,
        value.candidateChallenge, value.createdAt,
    )

    private fun approvalBytes(value: DeviceJoinApprovalEvidence) = signedBytes(
        APPROVAL_SCHEMA, value.requestDigest, value.inviterPublicKeySpki,
        value.attributedActorId, value.approvedAt,
    )

    private fun invitationDigest(value: DeviceJoinInvitation) = digest(invitationBytes(value), value.signature)
    private fun requestDigest(value: DeviceJoinRequest) = digest(requestBytes(value), value.signature)

    private fun digest(bytes: ByteArray, signature: String): String {
        val signatureBytes = requireNotNull(signature.decodeBase64Url()) { "Device join signature is invalid" }
        require(signatureBytes.size in 8..256) { "Device join signature size is invalid" }
        return MessageDigest.getInstance("SHA-256").digest(signedBytes("org.foe.device-join-digest/v1", bytes.toBase64Url(), signature))
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun verify(publicKey: String, bytes: ByteArray, signature: String): Boolean {
        val signatureBytes = signature.decodeBase64Url() ?: return false
        return DeviceProofSignatures.verify(publicKey, bytes, signatureBytes)
    }

    private fun signedBytes(type: String, vararg fields: String): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { writer ->
            (listOf(type) + fields).forEach { value ->
                val bytes = value.encodeToByteArray()
                require(bytes.size <= MAX_FIELD_BYTES) { "Device join field is too large" }
                writer.writeInt(bytes.size)
                writer.write(bytes)
            }
        }
        output.toByteArray()
    }

    private fun canonicalInstant(value: String): Instant {
        val parsed = Instant.parse(value)
        require(parsed.toString() == value) { "Device join time must be canonical UTC" }
        return parsed
    }

    private fun String.decodeBase64Url(): ByteArray? {
        if (length !in 11..350) return null
        return runCatching { Base64.getUrlDecoder().decode(this) }.getOrNull()?.takeIf {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it) == this
        }
    }

    private fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun <T> boundedJson(value: String, decode: () -> T): T {
        require(value.length <= MAX_JSON_BYTES) { "Device join JSON size is invalid" }
        require(value.encodeToByteArray().size in 1..MAX_JSON_BYTES) { "Device join JSON size is invalid" }
        return decode()
    }

    private companion object {
        const val INVITATION_SCHEMA = "org.foe.device-join-invitation/v1"
        const val REQUEST_SCHEMA = "org.foe.device-join-request/v1"
        const val APPROVAL_SCHEMA = "org.foe.device-join-approval-evidence/v1"
        const val CHALLENGE_BYTES = 32
        const val MAX_FIELD_BYTES = 1024
        const val MAX_JSON_BYTES = 8 * 1024
        val MAX_LIFETIME: Duration = Duration.ofMinutes(10)
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)
        val ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}
