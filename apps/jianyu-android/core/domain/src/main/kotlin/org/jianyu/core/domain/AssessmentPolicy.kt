package org.jianyu.core.domain

import org.jianyu.core.model.LifecycleStage
import java.time.LocalDate

/** The reference policy keeps numeric exams out of co-play and stops caregiver entry at Graduation. */
fun canRecordScoredAssessment(stage: LifecycleStage): Boolean = when (stage) {
    LifecycleStage.ACCOMPANY, LifecycleStage.CO_SELECT, LifecycleStage.HAND_OVER -> true
    LifecycleStage.CO_PLAY, LifecycleStage.GRADUATION -> false
}

data class AssessmentEntry(
    val subject: String,
    val assessmentKind: String,
    val score: Double,
    val maximum: Double,
    val occurredOn: LocalDate,
    val classAverage: Double? = null,
    val percentile: Double? = null,
    val topics: List<String> = emptyList(),
    val notes: String? = null,
) {
    fun summary(): String = "$subject · $assessmentKind：${score.compactNumber()}/${maximum.compactNumber()}"
}

/**
 * Validates the optional school signal before it becomes an authored Evidence/Event pair.
 * Scores remain observations: this policy deliberately exposes no child score, trend verdict,
 * or causal claim about Jianyu.
 */
fun normalizeAssessmentEntry(
    subject: String,
    assessmentKind: String,
    score: String,
    maximum: String,
    occurredOn: String,
    classAverage: String = "",
    percentile: String = "",
    topics: String = "",
    notes: String = "",
    earliestDate: LocalDate,
    today: LocalDate = LocalDate.now(),
): AssessmentEntry {
    val normalizedSubject = subject.trim().take(80)
    val normalizedKind = assessmentKind.trim().take(80)
    require(normalizedSubject.isNotEmpty()) { "请填写科目" }
    require(normalizedKind.isNotEmpty()) { "请填写考试或测验名称" }

    val parsedScore = score.trim().toDoubleOrNull()
        ?: throw IllegalArgumentException("请填写有效得分")
    val parsedMaximum = maximum.trim().toDoubleOrNull()
        ?: throw IllegalArgumentException("请填写有效满分")
    require(parsedMaximum > 0.0 && parsedMaximum <= 10000.0) { "满分必须大于 0 且不超过 10000" }
    require(parsedScore in 0.0..parsedMaximum) { "得分必须在 0 到满分之间" }

    val parsedDate = runCatching { LocalDate.parse(occurredOn.trim()) }
        .getOrElse { throw IllegalArgumentException("请选择考试或测验日期") }
    require(!parsedDate.isBefore(earliestDate) && !parsedDate.isAfter(today)) { "日期必须在孩子出生后且不晚于今天" }

    val parsedClassAverage = classAverage.trim().takeIf(String::isNotEmpty)?.let {
        it.toDoubleOrNull() ?: throw IllegalArgumentException("请填写有效班级平均分")
    }
    parsedClassAverage?.let { require(it in 0.0..parsedMaximum) { "班级平均分必须在 0 到满分之间" } }

    val parsedPercentile = percentile.trim().takeIf(String::isNotEmpty)?.let {
        it.toDoubleOrNull() ?: throw IllegalArgumentException("请填写有效百分位")
    }
    parsedPercentile?.let { require(it in 0.0..100.0) { "百分位必须在 0 到 100 之间" } }

    val normalizedTopics = topics
        .split(TOPIC_SEPARATOR)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .take(8)
        .map { it.take(40) }

    return AssessmentEntry(
        subject = normalizedSubject,
        assessmentKind = normalizedKind,
        score = parsedScore,
        maximum = parsedMaximum,
        occurredOn = parsedDate,
        classAverage = parsedClassAverage,
        percentile = parsedPercentile,
        topics = normalizedTopics,
        notes = notes.trim().take(500).takeIf(String::isNotEmpty),
    )
}

private fun Double.compactNumber(): String = if (this % 1.0 == 0.0) toLong().toString() else toString().trimEnd('0').trimEnd('.')

private val TOPIC_SEPARATOR = Regex("[,，;；]")
