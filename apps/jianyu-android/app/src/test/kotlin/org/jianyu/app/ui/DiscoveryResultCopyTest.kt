package org.jianyu.app.ui

import org.jianyu.core.domain.DiscoverySourceIssue
import org.jianyu.core.domain.SourceFailureReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryResultCopyTest {
    @Test
    fun incompleteSourceNeverClaimsNothingIsBest() {
        val copy = emptyDiscoveryDescription(hasIncompleteSource = true, childFacing = false)
        assertTrue(copy.contains("来源未完成"))
        assertTrue(copy.contains("调整后再试"))
        assertTrue(copy.contains("不能据此判断"))
        assertFalse(copy.contains("最合适"))
    }

    @Test
    fun completedEmptySearchStillLeavesFamilyChoiceOpen() {
        val copy = emptyDiscoveryDescription(hasIncompleteSource = false, childFacing = false)
        assertTrue(copy.contains("可以换个线索"))
        assertTrue(copy.contains("留白"))
        assertFalse(copy.contains("最合适"))
    }

    @Test
    fun caregiverOnlyEligibleCandidatesExplainWhyNoChildDoorIsShown() {
        val copy = emptyDiscoveryDescription(
            hasIncompleteSource = false,
            childFacing = false,
            hasCaregiverOnlyCandidates = true,
        )
        assertTrue(copy.contains("家长期待"))
        assertTrue(copy.contains("没有把它们当作孩子的入口展示"))
        assertTrue(copy.contains("换个线索"))
        assertTrue(copy.contains("留白"))
        assertFalse(copy.contains("被本机拒绝") || copy.contains("最合适"))

        val interrupted = emptyDiscoveryDescription(
            hasIncompleteSource = true,
            childFacing = false,
            hasCaregiverOnlyCandidates = true,
        )
        assertTrue(interrupted.contains("来源未完成"))
        assertFalse(interrupted.contains("没有把它们当作孩子的入口展示"))

        val teen = emptyDiscoveryDescription(
            hasIncompleteSource = false,
            childFacing = true,
            hasCaregiverOnlyCandidates = true,
        )
        assertTrue(teen.contains("你的入口"))
        assertFalse(teen.contains("孩子的入口"))
    }

    @Test
    fun unsourcedAiWorldEventExplainsTheLocalExclusionWithoutCallingItVerified() {
        val copy = emptyDiscoveryDescription(
            hasIncompleteSource = false,
            childFacing = false,
            hasUnsourcedAiWorldEvent = true,
        )
        assertTrue(copy.contains("缺少原始来源"))
        assertTrue(copy.contains("本机未展示"))
        assertTrue(copy.contains("换个线索"))
        assertTrue(copy.contains("留白"))
        assertFalse(copy.contains("已核验") || copy.contains("最合适"))
    }

    @Test
    fun handOverKeepsTheChildAsDecisionMaker() {
        val copy = emptyDiscoveryDescription(hasIncompleteSource = true, childFacing = true)
        assertTrue(copy.contains("你可以调整后再试"))
        assertFalse(copy.contains("你们可以调整后再试"))
    }

    @Test
    fun allFailedSourcesDoNotPromiseAnotherUsableEntrance() {
        val messages = discoverySourceIssueMessages(listOf(
            DiscoverySourceIssue("ai", "byok-llm"),
            DiscoverySourceIssue("world", "world-brief"),
            DiscoverySourceIssue("pack", "pack"),
        ))
        assertTrue(messages.any { it.contains("AI 服务未完成") })
        assertTrue(messages.any { it.contains("世界信息服务未完成") })
        assertTrue(messages.any { it.contains("本地内容包无法读取") })
        assertFalse(messages.any { it.contains("其他入口仍可使用") || it.contains("其他来源未受影响") })
    }

    @Test
    fun approvalScopeFailureIsNotBlamedOnTheNetwork() {
        val messages = discoverySourceIssueMessages(listOf(
            DiscoverySourceIssue("ai", "byok-llm", "outside-approved-scope"),
        ))
        assertTrue(messages.any { it.contains("超出这次确认的范围") && it.contains("本机已拦下") })
        assertFalse(messages.any { it.contains("网络") || it.contains("返回格式") })
    }

    @Test
    fun aiFailureCopyUsesKnownReasonAndNeverExposesRawProviderText() {
        val cases = listOf(
            SourceFailureReason.RESPONSE_INCOMPLETE to "没有完整结束",
            SourceFailureReason.RESPONSE_TIMED_OUT to "响应超时",
            SourceFailureReason.AUTHENTICATION_REJECTED to "API 密钥或账户权限",
            SourceFailureReason.RATE_LIMITED to "暂时限制请求",
            SourceFailureReason.REQUEST_REJECTED to "核对模型名称、服务地址",
            SourceFailureReason.INVALID_RESPONSE to "入口格式无法读取",
        )
        cases.forEach { (reason, expected) ->
            val message = discoverySourceIssueMessages(listOf(
                DiscoverySourceIssue("synthetic-ai", "byok-llm", reason.code),
            )).single()
            assertTrue(message.contains(expected))
            assertFalse(message.contains("synthetic-ai"))
        }
    }

    @Test
    fun worldFailureCopyGivesARelevantNextStepWithoutProviderText() {
        val cases = listOf(
            SourceFailureReason.RESPONSE_TIMED_OUT to "响应超时",
            SourceFailureReason.AUTHENTICATION_REJECTED to "API 密钥或服务权限",
            SourceFailureReason.RATE_LIMITED to "稍后再试",
            SourceFailureReason.REQUEST_REJECTED to "服务地址与接口格式",
            SourceFailureReason.INVALID_RESPONSE to "信息格式无法读取",
        )
        cases.forEach { (reason, expected) ->
            val message = discoverySourceIssueMessages(listOf(
                DiscoverySourceIssue("synthetic-world", "world-brief", reason.code),
            )).single()
            assertTrue(message.contains(expected))
            assertFalse(message.contains("synthetic-world"))
            assertFalse(message.contains("PRIVATE RESPONSE BODY"))
        }
        val providerAndDependentSource = discoverySourceIssueMessages(listOf(
            DiscoverySourceIssue("provider", "world-brief", SourceFailureReason.AUTHENTICATION_REJECTED.code),
            DiscoverySourceIssue("dependent-source", "world-brief"),
        ))
        assertTrue(providerAndDependentSource.single().contains("API 密钥或服务权限"))
        assertFalse(providerAndDependentSource.single().contains("可稍后重试"))
    }
}
