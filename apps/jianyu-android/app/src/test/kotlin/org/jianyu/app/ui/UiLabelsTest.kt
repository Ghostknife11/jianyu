package org.jianyu.app.ui

import java.time.ZoneId
import org.jianyu.core.model.ContextStream
import org.jianyu.core.model.EvidenceKind
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.model.FamilyEvent
import org.jianyu.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Test

class UiLabelsTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `calendar records do not expose an artificial midnight`() {
        assertEquals(
            "2026年9月20日",
            formatCalendarDate("2026-09-19T16:00:00Z", shanghai),
        )
    }

    @Test
    fun `actual instants retain local date and time`() {
        assertEquals(
            "2026年9月20日 00:00",
            formatInstant("2026-09-19T16:00:00Z", shanghai),
        )
    }

    @Test
    fun `invalid legacy values remain visible instead of crashing`() {
        assertEquals("unknown-date", formatCalendarDate("unknown-date", shanghai))
    }

    @Test
    fun `unknown extension codes never leak into family-facing labels`() {
        assertEquals("状态待确认", "provider-internal-state".asStatus())
        assertEquals("本机记录", "vendor.experimental-event".asEventLabel())
        assertEquals("需要进一步确认", "vendor-gate-reason".asGateLabel())
        assertEquals("其他信息类别", "vendor-private-field".asDisclosureLabel())
        assertEquals("其他入口", "vendor-new-ecosystem".asEcosystemLabel())
    }

    @Test
    fun `external service receipt and family choice use accurate family language`() {
        assertEquals("留白由家庭决定，外部来源不能代选", "reserved-nothing-option".asGateLabel())
        assertEquals("这次没有可用时间，可以留白", "no-available-time".asGateLabel())
        assertEquals("入口所需时间填写不合理", "invalid-duration".asGateLabel())
        assertEquals("AI 提到的具体活动缺少可核实来源", "ai-world-event-without-source".asGateLabel())
        assertEquals("像每日任务，可能增加压力", "daily-task-pressure".asGateLabel())
        assertEquals("请求前确认的信息范围", "provider.disclosure-approved".asEventLabel())
        assertEquals("请求后记录的信息范围", "provider.context-disclosed".asEventLabel())
        assertEquals("这次的参与和决定方式", "lifecycle-stage".asDisclosureLabel())
        assertEquals("这次的兴趣描述", "current-interest".asDisclosureLabel())
        assertEquals("公开的世界信息", "public-world-briefs".asDisclosureLabel())
        assertEquals("填写的地区", "coarse-region".asDisclosureLabel())
        assertEquals("未来 14 天时间范围", "public-time-window".asDisclosureLabel())
        assertEquals("语言", "language".asDisclosureLabel())
        assertEquals("公共活动类别", "public-categories".asDisclosureLabel())
        assertEquals("选择一个入口", "opportunity.chosen".asEventLabel())
        assertEquals("这次选择留白", "opportunity.nothing-chosen".asEventLabel())
        assertEquals("补充一次后来的看法", "opportunity.reflected".asEventLabel())
        assertEquals("补充一次后来的看法", "opportunity.feedback-recorded".asEventLabel())
        assertEquals("已选择", "chosen".asStatus())
        assertEquals("已留下看法", "reflected".asStatus())
    }

    @Test
    fun `disclosure audit shows translated categories without claiming delivery`() {
        val event = FamilyEvent(
            eventId = "synthetic-approval",
            eventType = "provider.disclosure-approved",
            householdId = "synthetic-household",
            authorId = "synthetic-caregiver",
            actorRole = MemberRole.CAREGIVER,
            deviceId = "synthetic-device",
            occurredAt = "2026-09-27T10:00:00Z",
            recordedAt = "2026-09-27T10:00:00Z",
            visibility = "guardians",
            payload = mapOf(
                "provider" to "合成 AI",
                "includedCategories" to "current-interest,coarse-region,vendor-extra",
                "deliveryStatus" to "not-confirmed",
            ),
        )
        assertEquals(
            listOf(
                "外部服务：合成 AI",
                "信息类别：这次的兴趣描述、填写的地区、其他信息类别",
                "这条记录不能证明服务已收到信息；填写的文字中仍可能有个人信息。",
            ),
            event.disclosureAuditLines(),
        )
        assertEquals(emptyList<String>(), event.copy(eventType = "interest.observed").disclosureAuditLines())
    }

    @Test
    fun `restricted visibility names the current display boundary rather than child-only secrecy`() {
        assertEquals("不在共享足迹显示", EvidenceVisibility.CHILD_PRIVATE.asVisibilityLabel())
        assertEquals("共享足迹可见", EvidenceVisibility.SHARED_WITH_CHILD.asVisibilityLabel())
    }

    @Test
    fun `graduation timeline calls adult corrections their own words`() {
        assertEquals("孩子纠正", "child-correction:evidence-1".asSourceLabel())
        assertEquals("本人纠正", "child-correction:evidence-1".asSourceLabel(adultSubject = true))
        assertEquals("孩子", ContextStream.CHILD.asStreamLabel())
        assertEquals("本人", ContextStream.CHILD.asStreamLabel(adultSubject = true))
        assertEquals("孩子自己说", EvidenceKind.CHILD_STATED.asEvidenceKindLabel())
        assertEquals("本人当时说", EvidenceKind.CHILD_STATED.asEvidenceKindLabel(adultSubject = true))
        assertEquals("本人当时选", EvidenceKind.CHILD_CHOICE.asEvidenceKindLabel(adultSubject = true))
        assertEquals("本人当时拒绝", "child-vetoed".asStatus(adultSubject = true))
        assertEquals("本人当时拒绝一个入口", "opportunity.child-vetoed".asEventLabel(adultSubject = true))
        assertEquals("本人当时纠正一条旧观察", "evidence.child-corrected".asEventLabel(adultSubject = true))
        assertEquals("本人", MemberRole.CHILD.asMemberRoleLabel(adultSubject = true))
        assertEquals("孩子已拒绝", "child-vetoed".asStatus())
        assertEquals("孩子拒绝一个入口", "opportunity.child-vetoed".asEventLabel())
        assertEquals("孩子本人", MemberRole.CHILD.asMemberRoleLabel())
    }

    @Test
    fun `opportunity provenance never falls back to a raw extension code`() {
        assertEquals("AI 生成 · 家庭连接的模型", sourceDisplayLabel("byok-llm", "家庭连接的模型"))
        assertEquals("内容包 · 从兴趣到现实：赛车", sourceDisplayLabel("pack", "从兴趣到现实：赛车"))
        assertEquals("世界信息", sourceDisplayLabel("world-brief", null))
        assertEquals("离线演示模板", sourceDisplayLabel("offline-demo-template", "unused"))
        assertEquals("来源待确认", sourceDisplayLabel("vendor-private-code", null))
        assertEquals("其他来源 · 公开资料", sourceDisplayLabel("vendor-private-code", "公开资料"))
        assertEquals("其他来源 · 公开 资料", sourceDisplayLabel("vendor-private-code", "公开\n资料"))
    }

    @Test
    fun `world freshness gate reasons are readable to a family`() {
        assertEquals("来源标注的有效期已过", "expired-source".asGateLabel())
        assertEquals("来源的有效期格式无效", "invalid-expiry".asGateLabel())
        assertEquals("世界信息超过 14 天未更新", "stale-world-brief".asGateLabel())
        assertEquals("世界信息的获取时间晚于当前时间", "future-world-retrieval-time".asGateLabel())
    }

    @Test
    fun `ecosystem aliases share one family-facing vocabulary`() {
        assertEquals("顺着兴趣", "已有兴趣".asEcosystemLabel())
        assertEquals("动手创造", "making".asEcosystemLabel())
        assertEquals("动手创造", "家庭实验".asEcosystemLabel())
        assertEquals("真实人物", "身边的人".asEcosystemLabel())
        assertEquals("现实观察", "real-world".asEcosystemLabel())
        assertEquals("世界线索", "world-event".asEcosystemLabel())
    }
}
