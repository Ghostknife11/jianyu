package org.jianyu.core.domain

import java.util.Locale

private val interestClauseBoundary = Regex("[。！？!?；;，,\\n]|但是|不过|可是|而是|但|\\bbut\\b")
private val explicitRefusalCues = listOf(
    "不想", "不要", "不喜欢", "不愿", "不再", "不感兴趣", "没兴趣", "拒绝", "讨厌",
    "别看", "别玩", "别去", "别再", "避免",
    "don't want", "do not want", "doesn't want", "does not want", "doesn't like",
    "not interested", "no more", "hate", "avoid",
)
private val explicitPullCues = listOf(
    "想", "喜欢", "好奇", "主动", "问", "研究", "试试", "尝试", "探索", "关注", "迷上", "感兴趣",
    "want", "like", "curious", "ask", "explore", "try", "interested",
)
private val caregiverLedCues = listOf(
    "家长想", "家长希望", "父母想", "父母希望", "爸爸想", "妈妈想", "老师想", "监护人想",
    "parent wants", "caregiver wants", "teacher wants",
)
private val externalBackgroundCues = listOf(
    "天气预报说", "天气预报显示", "新闻说", "新闻报道", "学校通知",
    "weather forecast says", "news says", "school notice",
)
private val caregiverRefusalPattern = Regex(
    "(家长|父母|爸爸|妈妈|老师|监护人)(说)?(不想|不要|不喜欢|不愿|别看|别玩|别去|避免)|" +
        "(parent|caregiver|teacher) (does not want|doesn't want|is not interested|doesn't like|wants to avoid)",
)

/** Conservative lexical match for optional Pack/World sources, not a natural-language understanding claim. */
internal fun currentInterestMentionsTerm(expression: String, term: String): Boolean {
    val normalizedTerm = term.trim().lowercase(Locale.ROOT)
    if (normalizedTerm.length < 2) return false
    return clauses(expression).any { clause ->
        normalizedTerm in clause && !clause.hasExplicitRefusal() && !clause.isOnlyExternalAgenda()
    }
}

internal fun currentInterestRefusesTerm(expression: String, term: String): Boolean {
    val normalizedTerm = term.trim().lowercase(Locale.ROOT)
    if (normalizedTerm.length < 2) return false
    return clauses(expression).any { clause ->
        normalizedTerm in clause && clause.hasExplicitRefusal() && !clause.hasCaregiverRefusal()
    }
}

/** Neither an isolated refusal nor a clearly adult-led/background clause supplies child pull. */
fun currentInterestHasNonRefusalClue(expression: String): Boolean {
    val parts = clauses(expression).filter(String::isNotBlank)
    val hasRefusal = parts.any(String::hasExplicitRefusal)
    return parts.any { clause ->
        !clause.hasExplicitRefusal() && !clause.isOnlyExternalAgenda() &&
            (!hasRefusal || clause.hasExplicitPullCue())
    }
}

private fun clauses(expression: String) = expression.lowercase(Locale.ROOT).split(interestClauseBoundary)
private fun String.hasExplicitRefusal() = explicitRefusalCues.any { it in this }
private fun String.hasCaregiverRefusal() = caregiverRefusalPattern.containsMatchIn(this)
private fun String.hasExplicitPullCue() =
    caregiverLedCues.none { it in this } && explicitPullCues.any { it in this }
private fun String.isOnlyExternalAgenda() =
    caregiverLedCues.any { it in this } ||
        (externalBackgroundCues.any { it in this } && !hasExplicitPullCue())
