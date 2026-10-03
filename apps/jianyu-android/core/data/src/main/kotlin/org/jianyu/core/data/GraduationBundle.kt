package org.jianyu.core.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jianyu.core.domain.lifecycleStage
import org.jianyu.core.domain.feedbackProvenance
import org.jianyu.core.domain.FeedbackProvenance
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.GraduationArchive
import org.jianyu.core.model.GraduationAuthor
import org.jianyu.core.model.GraduationSubject
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.TombstoneTarget
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.util.Base64
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class GraduationAuthorization(
    val subjectId: String,
    val confirmedAt: String,
    val statementVersion: String = "org.foe.graduation-export-consent/v1",
)

data class GraduationExport(
    val bytes: ByteArray,
    val recoveryCode: String,
)

class GraduationBundleCodec(
    private val secureRandom: SecureRandom = SecureRandom(),
    private val clock: () -> Instant = Instant::now,
    private val currentYear: () -> Int = { Year.now().value },
    private val currentDate: () -> LocalDate = LocalDate::now,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun create(state: FamilyState, childId: String, authorization: GraduationAuthorization): GraduationExport {
        val child = requireNotNull(state.children.firstOrNull { it.id == childId }) { "Graduation subject does not exist" }
        val stage = if (child.birthDate == null) lifecycleStage(child.birthYear, currentYear()) else lifecycleStage(child, currentDate())
        require(stage == LifecycleStage.GRADUATION) {
            "Graduation export is available only at 16+"
        }
        require(authorization.statementVersion == AUTHORIZATION_SCHEMA && authorization.subjectId == child.id) {
            "Fresh authorization from the graduating person is required"
        }
        Instant.parse(authorization.confirmedAt)
        val subjectMember = requireNotNull(state.members.firstOrNull {
            it.id == child.memberId && it.role == MemberRole.CHILD && it.subjectId == child.id
        }) { "Graduation subject member link is invalid" }

        val evidence = state.evidence.filter { it.childId == child.id }
        val hypotheses = state.hypotheses.filter { it.childId == child.id }
        val choices = state.choices.filter { it.childId == child.id }
        val events = state.events.filter { it.subjectId == child.id }
        val includedIds = buildSet {
            add(child.id)
            evidence.forEach { add(it.id) }
            hypotheses.forEach { add(it.id) }
            choices.forEach { add(it.id) }
            events.forEach { add(it.eventId) }
        }
        val tombstones = state.tombstones.filter {
            it.subjectId == child.id ||
                (it.targetType == TombstoneTarget.SUBJECT && it.targetId == child.id) ||
                it.targetId in includedIds
        }
        val referencedAuthorIds = buildSet {
            evidence.forEach { add(it.authorId) }
            events.forEach { add(it.authorId) }
            tombstones.forEach { add(it.authorId) }
            add(subjectMember.id)
        }
        val authors = state.members
            .filter { it.id in referencedAuthorIds }
            .map { GraduationAuthor(it.id, it.role) }
            .sortedBy(GraduationAuthor::memberId)

        val createdAt = clock().toString()
        val bundleId = UUID.randomUUID().toString()
        val archive = GraduationArchive(
            archiveId = bundleId,
            createdAt = createdAt,
            subject = GraduationSubject(
                subjectId = child.id,
                memberId = subjectMember.id,
                displayName = child.displayName,
                birthYear = child.birthYear,
                birthDate = child.birthDate,
            ),
            authors = authors,
            evidence = evidence,
            hypotheses = hypotheses,
            choices = choices,
            events = events,
            tombstones = tombstones,
            humanReadableMarkdown = renderMarkdown(child.displayName, createdAt, evidence, hypotheses, choices, events, authors),
        )
        val plaintext = json.encodeToString(archive).encodeToByteArray()
        require(plaintext.size <= MAX_ARCHIVE_BYTES) { "Graduation archive is too large" }
        val key = ByteArray(KEY_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val ciphertext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(bundleId, createdAt))
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val envelope = GraduationEnvelope(
            bundleId = bundleId,
            createdAt = createdAt,
            nonce = nonce.base64Url(),
            ciphertext = ciphertext.base64Url(),
        )
        return try {
            GraduationExport(
                bytes = json.encodeToString(envelope).encodeToByteArray(),
                recoveryCode = key.base64Url(),
            )
        } finally {
            key.fill(0)
        }
    }

    fun open(bytes: ByteArray, recoveryCode: String): GraduationArchive {
        require(bytes.size in 1..MAX_BUNDLE_BYTES) { "Graduation bundle size is invalid" }
        val text = strictUtf8(bytes)
        val envelope = runCatching { json.decodeFromString<GraduationEnvelope>(text) }
            .getOrElse { throw IllegalArgumentException("Graduation bundle format is invalid") }
        require(envelope.format == FORMAT && envelope.cipher == CIPHER_ID) { "Unsupported Graduation bundle version" }
        runCatching { UUID.fromString(envelope.bundleId) }
            .getOrElse { throw IllegalArgumentException("Graduation bundle ID is invalid") }
        runCatching { Instant.parse(envelope.createdAt) }
            .getOrElse { throw IllegalArgumentException("Graduation bundle time is invalid") }
        val key = recoveryCode.trim().decodeBase64Url("recovery code")
        require(key.size == KEY_BYTES) { "Graduation recovery code is invalid" }
        val nonce = envelope.nonce.decodeBase64Url("nonce")
        require(nonce.size == NONCE_BYTES) { "Graduation bundle nonce is invalid" }
        val ciphertext = envelope.ciphertext.decodeBase64Url("ciphertext")
        require(ciphertext.size in TAG_BYTES..(MAX_ARCHIVE_BYTES + TAG_BYTES)) { "Graduation ciphertext size is invalid" }
        val plaintext = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(envelope.bundleId, envelope.createdAt))
            try {
                cipher.doFinal(ciphertext)
            } catch (_: AEADBadTagException) {
                throw IllegalArgumentException("Graduation recovery code is wrong or the bundle was modified")
            }
        } finally {
            key.fill(0)
        }
        return try {
            val archive = runCatching { json.decodeFromString<GraduationArchive>(strictUtf8(plaintext)) }
                .getOrElse { throw IllegalArgumentException("Graduation archive content is invalid") }
            require(archive.schema in SUPPORTED_ARCHIVE_SCHEMAS && archive.archiveId == envelope.bundleId && archive.createdAt == envelope.createdAt) {
                "Graduation archive identity does not match its envelope"
            }
            archive
        } finally {
            plaintext.fill(0)
        }
    }

    private fun renderMarkdown(
        displayName: String,
        createdAt: String,
        evidence: List<org.jianyu.core.model.Evidence>,
        hypotheses: List<org.jianyu.core.model.Hypothesis>,
        choices: List<org.jianyu.core.model.FamilyChoice>,
        events: List<org.jianyu.core.model.FamilyEvent>,
        authors: List<GraduationAuthor>,
    ): String = buildString {
        val authorRoles = authors.associate { it.memberId to it.role }
        appendLine("# ${displayName.markdownText()} 的家庭机会资料")
        appendLine()
        appendLine("导出时间：$createdAt")
        appendLine()
        appendLine("> 这是按来源保留的经历与表达，不是能力、性格、潜力或发展评分。")
        appendLine()
        appendLine("## 留下的线索")
        if (evidence.isEmpty()) appendLine("没有保存的线索。")
        evidence.sortedBy { it.occurredAt }.forEach {
            val author = authorRoles[it.authorId]?.asGraduationAuthorLabel() ?: "未知作者（${it.authorId.markdownText()}）"
            appendLine("- ${it.occurredAt} · ${it.stream.name} · ${it.kind.name} · $author")
            appendLine("  ${it.expression.markdownText()}")
        }
        appendLine()
        appendLine("## 家庭选择与后续看法")
        if (choices.isEmpty()) appendLine("没有保存的选择。")
        choices.sortedBy { it.chosenAt }.forEach {
            val feedbackLabel = it.feedback?.let { value ->
                val source = when (feedbackProvenance(it, events)) {
                    FeedbackProvenance.CHILD_SIGNED -> "孩子署名"
                    FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW -> "家长代记"
                    FeedbackProvenance.UNKNOWN -> "旧记录，来源未确认"
                }
                " · 后续看法（$source）：${value.markdownText()}"
            } ?: ""
            appendLine("- ${it.chosenAt} · ${it.opportunity.title.markdownText()} · ${it.status}$feedbackLabel")
        }
        appendLine()
        appendLine("## 曾经出现的 AI 假设")
        appendLine("这些是假设，不是事实；请结合其依据、反证和时间理解。")
        if (hypotheses.isEmpty()) appendLine("没有保存的 AI 假设。")
        hypotheses.sortedBy { it.derivedAt }.forEach {
            appendLine("- ${it.derivedAt} · ${it.statement.markdownText()} · 置信度 ${it.confidence} · 状态 ${it.status}")
        }
    }

    private fun String.markdownText() = lineSequence().joinToString(" ") { line -> line.trim() }
        .replace("\\", "\\\\")
        .replace("`", "\\`")

    private fun MemberRole.asGraduationAuthorLabel() = when (this) {
        MemberRole.CHILD -> "本人记录"
        MemberRole.CAREGIVER -> "照护者记录"
        MemberRole.GUARDIAN -> "监护人记录"
        MemberRole.OBSERVER -> "受邀观察者记录"
    }

    private fun aad(bundleId: String, createdAt: String) = "$FORMAT|$bundleId|$createdAt|graduation-archive".encodeToByteArray()
    private fun ByteArray.base64Url() = Base64.getUrlEncoder().withoutPadding().encodeToString(this)
    private fun String.decodeBase64Url(label: String) = runCatching { Base64.getUrlDecoder().decode(this) }
        .getOrElse { throw IllegalArgumentException("Graduation $label is invalid") }
    private fun strictUtf8(bytes: ByteArray): String = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse { throw IllegalArgumentException("Graduation bundle is not valid UTF-8") }

    @Serializable
    private data class GraduationEnvelope(
        val format: String = FORMAT,
        val cipher: String = CIPHER_ID,
        val bundleId: String,
        val createdAt: String,
        val nonce: String,
        val ciphertext: String,
    )

    private companion object {
        const val FORMAT = "org.foe.encrypted-graduation-bundle/v1"
        val SUPPORTED_ARCHIVE_SCHEMAS = setOf(
            "org.foe.graduation-archive/v1",
            "org.foe.graduation-archive/v2",
        )
        const val AUTHORIZATION_SCHEMA = "org.foe.graduation-export-consent/v1"
        const val CIPHER_ID = "AES-256-GCM"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val TAG_BITS = 128
        const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024
        const val MAX_BUNDLE_BYTES = 24 * 1024 * 1024
    }
}
