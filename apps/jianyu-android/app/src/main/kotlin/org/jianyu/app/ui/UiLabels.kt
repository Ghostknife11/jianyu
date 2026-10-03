package org.jianyu.app.ui

import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.MemberRole
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.domain.OpportunityEcosystems
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun String.asStatus(adultSubject: Boolean = false) = when (this) {
    "chosen" -> "已选择"
    "nothing" -> "选择留白"
    "child-vetoed" -> if (adultSubject) "本人当时拒绝" else "孩子已拒绝"
    "reflected" -> "已留下看法"
    else -> "状态待确认"
}

internal fun String.asEventLabel(adultSubject: Boolean = false) = when (this) {
    "family.created" -> "建立家庭保险箱"
    "child.added" -> "添加孩子"
    "family.member-added" -> "添加家庭成员"
    "interest.observed" -> "记录一次当下线索"
    "provider.disclosure-approved" -> "请求前确认的信息范围"
    "provider.context-disclosed" -> "请求后记录的信息范围"
    "opportunity.chosen" -> "选择一个入口"
    "opportunity.child-vetoed" -> if (adultSubject) "本人当时拒绝一个入口" else "孩子拒绝一个入口"
    "opportunity.nothing-chosen" -> "这次选择留白"
    "opportunity.reflected", "opportunity.feedback-recorded" -> "补充一次后来的看法"
    "evidence.child-corrected" -> if (adultSubject) "本人当时纠正一条旧观察" else "孩子纠正一条旧观察"
    "evidence.deleted" -> "删除了一条家庭记录"
    "opportunity.choice-deleted" -> "删除一条选择与相关看法"
    "assessment.recorded" -> "记录一次学校情况"
    "graduation.archive-exported" -> "本人导出一份成年交接加密资料包"
    else -> "本机记录"
}

/** Category-only audit detail. Never render raw prompt values or machine category IDs here. */
internal fun FamilyEvent.disclosureAuditLines(): List<String> {
    if (eventType != "provider.disclosure-approved" && eventType != "provider.context-disclosed") return emptyList()
    val recipient = payload["provider"]?.trim()?.take(80)?.takeIf {
        it.isNotEmpty() && it != "configured-provider" && it != "configured-world-brief-provider"
    } ?: "已设置的外部服务"
    val categories = payload["includedCategories"]
        ?.split(',')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.distinct()
        ?.joinToString("、") { it.asDisclosureLabel() }
        ?.takeIf(String::isNotEmpty)
        ?: "未列出家庭线索类别"
    return listOf(
        "外部服务：$recipient",
        "信息类别：$categories",
        "这条记录不能证明服务已收到信息；填写的文字中仍可能有个人信息。",
    )
}

internal fun formatInstant(
    value: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm")
        .withZone(zoneId)
        .format(Instant.parse(value))
}.getOrDefault(value)

internal fun formatCalendarDate(
    value: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = runCatching {
    DateTimeFormatter.ofPattern("yyyy年M月d日")
        .withZone(zoneId)
        .format(Instant.parse(value))
}.getOrDefault(value)

internal fun org.jianyu.core.model.GoalOwner.asGoalLabel(stage: LifecycleStage) = when (this) {
    org.jianyu.core.model.GoalOwner.CHILD -> if (stage == LifecycleStage.HAND_OVER) "主要回应你想做的事" else "主要回应孩子想做的事"
    org.jianyu.core.model.GoalOwner.CAREGIVER -> "主要回应家长的期待"
    org.jianyu.core.model.GoalOwner.SHARED -> if (stage == LifecycleStage.HAND_OVER) "主要回应你们共同想做的事" else "主要回应共同想做的事"
}

internal fun org.jianyu.core.model.Verification.asVerificationLabel() = when (this) {
    org.jianyu.core.model.Verification.VERIFIED -> "来源方称已核验；请再核实"
    org.jianyu.core.model.Verification.LIKELY -> "有来源，仍需确认"
    org.jianyu.core.model.Verification.IDEA -> "这是一个想法，不是事实"
}

internal fun ContextStream.asStreamLabel(adultSubject: Boolean = false) = when (this) {
    ContextStream.CHILD -> if (adultSubject) "本人" else "孩子"
    ContextStream.SCHOOL -> "学校"
    ContextStream.LIFE -> "生活"
    ContextStream.WORLD -> "世界"
}

internal fun org.jianyu.core.model.EvidenceKind.asEvidenceKindLabel(adultSubject: Boolean = false) = when (this) {
    org.jianyu.core.model.EvidenceKind.CHILD_STATED -> if (adultSubject) "本人当时说" else "孩子自己说"
    org.jianyu.core.model.EvidenceKind.CHILD_CHOICE -> if (adultSubject) "本人当时选" else "孩子自己选"
    org.jianyu.core.model.EvidenceKind.DIRECT_OBSERVATION -> "直接观察"
    org.jianyu.core.model.EvidenceKind.CAREGIVER_INTERPRETATION -> "家长理解"
    org.jianyu.core.model.EvidenceKind.TEACHER_FEEDBACK -> "教师反馈"
    org.jianyu.core.model.EvidenceKind.ASSESSMENT -> "考试或测验"
    org.jianyu.core.model.EvidenceKind.IMPORTED_CLAIM -> "外部资料"
    org.jianyu.core.model.EvidenceKind.AI_INFERENCE -> "AI 推测"
}

internal fun org.jianyu.core.model.EvidenceVisibility.asVisibilityLabel() = when (this) {
    org.jianyu.core.model.EvidenceVisibility.GUARDIANS -> "监护人可见"
    org.jianyu.core.model.EvidenceVisibility.SHARED_WITH_CHILD -> "共享足迹可见"
    org.jianyu.core.model.EvidenceVisibility.CHILD_PRIVATE -> "不在共享足迹显示"
}

internal fun MemberRole.asMemberRoleLabel(adultSubject: Boolean = false) = when (this) {
    MemberRole.CAREGIVER -> "家长"
    MemberRole.GUARDIAN -> "监护人"
    MemberRole.OBSERVER -> "家庭观察者"
    MemberRole.CHILD -> if (adultSubject) "本人" else "孩子本人"
}

internal fun String.asSourceLabel(adultSubject: Boolean = false) = when {
    this == "family-input" -> "家庭直接记录"
    this == "caregiver-assessment" -> "家长录入的学校记录"
    startsWith("child-correction:") -> if (adultSubject) "本人纠正" else "孩子纠正"
    else -> "已标注来源"
}

internal fun sourceDisplayLabel(kind: String, title: String?): String {
    val name = title?.trim()?.replace(Regex("\\s+"), " ")?.take(180)?.takeIf(String::isNotEmpty)
    return when (kind) {
        "byok-llm" -> name?.let { "AI 生成 · $it" } ?: "由已连接的 AI 生成"
        "world-brief" -> name?.let { "世界信息 · $it" } ?: "世界信息"
        "pack" -> name?.let { "内容包 · $it" } ?: "内容包"
        "offline-demo-template" -> "离线演示模板"
        "family-choice" -> "家庭自主选择"
        else -> name?.let { "其他来源 · $it" } ?: "来源待确认"
    }
}

internal fun String.asGateLabel() = when (this) {
    "reserved-nothing-option" -> "留白由家庭决定，外部来源不能代选"
    "no-available-time" -> "这次没有可用时间，可以留白"
    "invalid-duration" -> "入口所需时间填写不合理"
    "exceeds-time" -> "超出可用时间"
    "exceeds-travel" -> "距离不合适"
    "exceeds-cost" -> "超出预算"
    "exceeds-caregiver-energy" -> "家长精力不足"
    "caregiver-goal-without-child-pull", "insufficient-child-pull" -> "缺少孩子意愿"
    "high-risk" -> "风险过高"
    "age-out-of-range" -> "不符合当前年龄边界"
    "forced-educational-connection" -> "知识连接过于生硬"
    "excessive-intervention-pressure" -> "干预压力过高"
    "daily-task-pressure" -> "像每日任务，可能增加压力"
    "recent-intervention-load" -> "近 7 天已选入口较多，可以考虑留白；点选不代表已经参与"
    "missing-explanation" -> "解释不足"
    "invalid-confidence" -> "置信度格式异常"
    "missing-ecosystem" -> "生态信息缺失"
    "ai-world-event-without-source" -> "AI 提到的具体活动缺少可核实来源"
    "verification-idea" -> "需要家庭核实"
    "verification-likely" -> "尚未完全核实"
    "risk-unknown" -> "风险仍需家长判断"
    "intervention-pressure-medium" -> "需要留意干预压力"
    "verified-without-source" -> "缺少可追溯来源"
    "invalid-expiry" -> "来源的有效期格式无效"
    "expired-source" -> "来源标注的有效期已过"
    "invalid-world-retrieval-time" -> "世界信息缺少有效的获取时间"
    "stale-world-brief" -> "世界信息超过 14 天未更新"
    "future-world-retrieval-time" -> "世界信息的获取时间晚于当前时间"
    "unsafe-source-url" -> "来源链接不是安全的 HTTPS 地址"
    "verified-without-source-url" -> "已核验状态缺少原始来源链接"
    "sponsored-content" -> "这是已披露的赞助内容，不获得排序优待"
    "source-tracking-warning" -> "打开来源可能进入带追踪的外部页面"
    "booking-required" -> "需要预约或报名"
    else -> "需要进一步确认"
}

internal fun String.asDisclosureLabel() = when (this) {
    "age-band" -> "年龄段"
    "lifecycle-stage" -> "这次的参与和决定方式"
    "current-interest" -> "这次的兴趣描述"
    "declared-goals" -> "孩子/家长/共同目标"
    "practical-constraints" -> "时间、预算、距离与精力"
    "school-window" -> "可选学校情况"
    "life-context" -> "可选家庭安排"
    "coarse-region" -> "填写的地区"
    "recent-evidence-summaries" -> "少量近期足迹与结果摘要"
    "recent-intervention-count" -> "近 7 天已选入口次数（不代表实际参与）"
    "public-world-briefs" -> "公开的世界信息"
    "public-time-window" -> "未来 14 天时间范围"
    "language" -> "语言"
    "public-categories" -> "公共活动类别"
    "names" -> "姓名"
    "household-id" -> "家庭编号"
    "child-id" -> "孩子编号"
    "member-ids" -> "成员编号"
    "exact-location" -> "精确位置"
    "full-family-history" -> "完整家庭历史"
    "provider-secrets" -> "API 密钥"
    else -> "其他信息类别"
}

internal fun String.asEcosystemLabel() = when (OpportunityEcosystems.canonical(this)) {
    OpportunityEcosystems.EXISTING_INTEREST -> "顺着兴趣"
    OpportunityEcosystems.DIGITAL_GAME -> "游戏"
    OpportunityEcosystems.MEDIA -> "影视与内容"
    OpportunityEcosystems.READING -> "阅读"
    OpportunityEcosystems.SPORT -> "运动"
    OpportunityEcosystems.MAKING -> "动手创造"
    OpportunityEcosystems.FAMILY_LIFE -> "家庭生活"
    OpportunityEcosystems.NATURE -> "自然"
    OpportunityEcosystems.TRAVEL -> "出行"
    OpportunityEcosystems.PLACE -> "场所与展览"
    OpportunityEcosystems.PEOPLE -> "真实人物"
    OpportunityEcosystems.REAL_WORLD -> "现实观察"
    OpportunityEcosystems.WORLD_EVENT -> "世界线索"
    OpportunityEcosystems.DIGITAL_MAKING -> "数字创作"
    OpportunityEcosystems.REAL_PROJECT -> "真实项目"
    OpportunityEcosystems.WORKSHEET -> "练习材料"
    OpportunityEcosystems.NOTHING -> "留白"
    else -> "其他入口"
}
