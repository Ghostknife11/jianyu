package org.jianyu.core.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jianyu.core.model.CURRENT_FAMILY_STATE_SCHEMA
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.SyncFrame
import org.jianyu.core.model.SyncFrameBody
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

private const val SYNC_FRAME_SCHEMA = "org.foe.sync-frame/v1"
private const val SYNC_FRAME_BODY_SCHEMA = "org.foe.sync-frame-body/v1"

/** Canonical cleartext codec used only inside an authorized device before encryption. */
class SyncFrameCodec(
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    fun create(
        state: FamilyState,
        deviceId: String,
        sequence: Long,
        previousFrameHash: String?,
        createdAt: Instant = Instant.now(),
        frameId: String = UUID.randomUUID().toString(),
    ): SyncFrame {
        require(state.schema == CURRENT_FAMILY_STATE_SCHEMA) { "Family state must be migrated before synchronization" }
        val body = SyncFrameBody(
            frameId = frameId,
            household = state.household,
            deviceId = deviceId,
            sequence = sequence,
            previousFrameHash = previousFrameHash,
            createdAt = createdAt.toString(),
            members = state.members,
            children = state.children,
            evidence = state.evidence,
            hypotheses = state.hypotheses,
            choices = state.choices,
            events = state.events,
            tombstones = state.tombstones,
            retiredDeviceIds = state.syncState.retiredDeviceIds,
        )
        validateBody(body)
        return SyncFrame(body = body, contentHash = hash(body))
    }

    fun encode(frame: SyncFrame): ByteArray {
        verify(frame)
        return json.encodeToString(frame).encodeToByteArray()
    }

    fun decodeAndVerify(bytes: ByteArray): SyncFrame {
        require(bytes.isNotEmpty()) { "Sync frame is empty" }
        require(bytes.size <= MAX_FRAME_BYTES) { "Sync frame exceeds size limit" }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return json.decodeFromString<SyncFrame>(text).also(::verify)
    }

    fun verify(frame: SyncFrame) {
        require(frame.schema == SYNC_FRAME_SCHEMA) { "Unsupported sync frame schema" }
        validateBody(frame.body)
        require(HASH.matches(frame.contentHash)) { "Invalid sync frame hash encoding" }
        val actual = hash(frame.body)
        require(
            MessageDigest.isEqual(
                actual.encodeToByteArray(),
                frame.contentHash.lowercase().encodeToByteArray(),
            ),
        ) { "Sync frame content hash mismatch" }
    }

    private fun validateBody(body: SyncFrameBody) {
        require(body.schema == SYNC_FRAME_BODY_SCHEMA) { "Unsupported sync frame body schema" }
        require(body.frameId.isNotBlank()) { "Sync frame ID must not be blank" }
        require(body.household.id.isNotBlank()) { "Sync frame household ID must not be blank" }
        require(body.deviceId.isNotBlank()) { "Sync frame device ID must not be blank" }
        require(body.sequence > 0) { "Sync frame sequence must be positive" }
        if (body.sequence == 1L) {
            require(body.previousFrameHash == null) { "First sync frame must not have a previous hash" }
        } else {
            val previousFrameHash = body.previousFrameHash
            require(previousFrameHash != null && HASH.matches(previousFrameHash)) {
                "Chained sync frame requires a valid previous hash"
            }
        }
        Instant.parse(body.createdAt)
        require(body.retiredDeviceIds.none(String::isBlank)) { "Retired device ID must not be blank" }
    }

    private fun hash(body: SyncFrameBody): String = MessageDigest.getInstance("SHA-256")
        .digest(json.encodeToString(body).encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_FRAME_BYTES = 16 * 1024 * 1024
        val HASH = Regex("[0-9a-fA-F]{64}")
    }
}
