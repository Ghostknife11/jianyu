package org.jianyu.core.domain

import org.jianyu.core.model.Evidence
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyChoice
import org.jianyu.core.model.FamilyEvent
import java.time.Duration
import java.time.Instant

data class RecentEvidenceProjection(
    val summaries: List<String>,
    val omittedCount: Int,
)

/** Local count offered only with per-call approval; restricted choices cannot influence it. */
fun countRecentShareableSelections(
    choices: List<FamilyChoice>,
    events: List<FamilyEvent>,
    childId: String,
    now: Instant = Instant.now(),
): Int = choices.count { choice ->
    choice.childId == childId && choice.status !in setOf("nothing", "child-vetoed") &&
        !isLegacyDemoChoice(choice, events) && !choice.hasRestrictedLinkedEvent(events) &&
        runCatching { Duration.between(Instant.parse(choice.chosenAt), now).toDays() in 0..6 }
            .getOrDefault(false)
}

/**
 * Produces a small, deterministic preview for one opportunity request.
 * It never includes private, inferred, assessment, teacher, or imported records.
 */
fun projectRecentEvidence(
    evidence: List<Evidence>,
    childId: String,
    now: Instant = Instant.now(),
    limit: Int = 3,
): RecentEvidenceProjection {
    require(limit in 1..5) { "Recent evidence limit must be between 1 and 5" }
    val eligible = evidence.asSequence()
        .filter { it.childId == childId }
        .filter { it.visibility != EvidenceVisibility.CHILD_PRIVATE }
        .filter { it.kind in SAFE_KINDS }
        .mapNotNull { item ->
            val occurredAt = runCatching { Instant.parse(item.occurredAt) }.getOrNull() ?: return@mapNotNull null
            val age = Duration.between(occurredAt, now).toDays()
            if (age !in 0..180) return@mapNotNull null
            val expression = item.expression
                .replace(URL, "[已隐藏链接]")
                .replace(EMAIL, "[已隐藏邮箱]")
                .replace(LONG_NUMBER, "[已隐藏号码]")
                .replace(WHITESPACE, " ")
                .trim()
                .take(160)
            if (expression.isBlank()) return@mapNotNull null
            Projected(occurredAt, "${item.kind.safeLabel()}（${ageLabel(age)}）：$expression")
        }
        .sortedByDescending { it.occurredAt }
        .toList()
    return RecentEvidenceProjection(
        summaries = eligible.take(limit).map { it.summary },
        omittedCount = (eligible.size - limit).coerceAtLeast(0),
    )
}

/**
 * Builds the optional, user-visible memory sent for one recommendation request.
 *
 * A previous family selection is not treated as proof of child preference. Only
 * an explicit child veto or a sourced later view is eligible to re-enter the
 * recommendation loop. Neither a selection nor a view proves participation.
 */
fun projectRecentRecommendationContext(
    evidence: List<Evidence>,
    choices: List<FamilyChoice>,
    childId: String,
    now: Instant = Instant.now(),
    limit: Int = 5,
    events: List<FamilyEvent> = emptyList(),
): RecentEvidenceProjection {
    require(limit in 1..8) { "Recent context limit must be between 1 and 8" }
    val evidenceItems = evidence.asSequence()
        .filter { it.childId == childId }
        .filterNot { isLegacyDemoEvidence(it, events) }
        .filter { it.visibility != EvidenceVisibility.CHILD_PRIVATE }
        .filter { it.kind in SAFE_KINDS }
        .mapNotNull { item ->
            val occurredAt = runCatching { Instant.parse(item.occurredAt) }.getOrNull() ?: return@mapNotNull null
            val age = Duration.between(occurredAt, now).toDays()
            if (age !in 0..180) return@mapNotNull null
            val expression = sanitize(item.expression, 160)
            if (expression.isBlank()) return@mapNotNull null
            Projected(occurredAt, "${item.kind.safeLabel()}（${ageLabel(age)}）：$expression")
        }

    val outcomeItems = choices.asSequence()
        .filter { it.childId == childId && !it.opportunity.isNothing }
        .filterNot { it.hasRestrictedLinkedEvent(events) }
        // Older app versions allowed demonstration picks to be saved; never train on those templates.
        .filterNot { isLegacyDemoChoice(it, events) }
        .mapNotNull { choice ->
            val chosenAt = runCatching { Instant.parse(choice.chosenAt) }.getOrNull() ?: return@mapNotNull null
            val attribution = if (choice.status == "reflected") feedbackAttribution(choice, events) else null
            if (choice.status == "reflected" && attribution == null) return@mapNotNull null
            val occurredAt = attribution?.occurredAt ?: chosenAt
            val age = Duration.between(occurredAt, now).toDays()
            if (age !in 0..180) return@mapNotNull null
            val title = sanitize(choice.opportunity.title, 100)
            if (title.isBlank()) return@mapNotNull null
            val ecosystem = sanitize(choice.opportunity.ecosystem, 30).ifBlank { "其他" }
            val summary = when (choice.status) {
                "child-vetoed" -> "孩子明确不要（${ageLabel(age)}）：$title［$ecosystem］"
                "reflected" -> choice.feedback.asKnownFeedback()?.let { feedback ->
                    val source = when (attribution?.provenance) {
                        FeedbackProvenance.CHILD_SIGNED -> "孩子署名的后续看法"
                        FeedbackProvenance.CAREGIVER_RELAYED_CHILD_VIEW -> "家长代记的孩子看法"
                        else -> return@mapNotNull null
                    }
                    "$source（${ageLabel(age)}）：$feedback · $title［$ecosystem］"
                }
                else -> null
            } ?: return@mapNotNull null
            Projected(occurredAt, summary)
        }

    val eligible = (evidenceItems + outcomeItems)
        .sortedByDescending { it.occurredAt }
        .toList()
    return RecentEvidenceProjection(
        summaries = eligible.take(limit).map { it.summary },
        omittedCount = (eligible.size - limit).coerceAtLeast(0),
    )
}

private data class Projected(val occurredAt: Instant, val summary: String)
private fun EvidenceKind.safeLabel() = when (this) {
    EvidenceKind.CHILD_STATED -> "孩子自己说"
    EvidenceKind.CHILD_CHOICE -> "孩子自己选"
    EvidenceKind.DIRECT_OBSERVATION -> "家庭直接观察"
    else -> error("Unsafe evidence kind cannot be projected")
}

private fun ageLabel(days: Long) = when (days) {
    0L -> "今天"
    1L -> "昨天"
    else -> "$days 天前"
}

private fun String?.asKnownFeedback(): String? = when (this?.trim()) {
    "喜欢" -> "喜欢"
    "一般" -> "一般"
    "不合适" -> "不合适"
    else -> null
}

private fun sanitize(value: String, limit: Int) = value
    .replace(URL, "[已隐藏链接]")
    .replace(EMAIL, "[已隐藏邮箱]")
    .replace(LONG_NUMBER, "[已隐藏号码]")
    .replace(WHITESPACE, " ")
    .trim()
    .take(limit)

private val SAFE_KINDS = setOf(
    EvidenceKind.CHILD_STATED,
    EvidenceKind.CHILD_CHOICE,
    EvidenceKind.DIRECT_OBSERVATION,
)
private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
private val EMAIL = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
private val LONG_NUMBER = Regex("(?<!\\d)\\+?\\d[\\d -]{6,}\\d(?!\\d)")
private val WHITESPACE = Regex("\\s+")
