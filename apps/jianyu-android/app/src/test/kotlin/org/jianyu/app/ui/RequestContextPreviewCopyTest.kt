package org.jianyu.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestContextPreviewCopyTest {
    @Test
    fun offlineDemoNamesLocalTemplateAndGateWithoutSuggestingAnExternalCall() {
        val copy = requestContextBoundaryCopy(
            useOfflineDemo = true,
            worldBriefConfigured = true,
            regionProvided = true,
        )
        assertTrue(copy.contains("固定模板只替换关键词"))
        assertTrue(copy.contains("现实条件仍由本机检查"))
        assertTrue(copy.contains("不调用 AI 或世界信息服务"))
        assertTrue(copy.contains("不保存演示输入或点选"))
    }

    @Test
    fun formalDiscoveryDisclosesTheVerbatimRegionBoundary() {
        val copy = requestContextBoundaryCopy(
            useOfflineDemo = false,
            worldBriefConfigured = true,
            regionProvided = true,
        )
        assertTrue(copy.contains("才会尝试向 AI 发送"))
        assertTrue(copy.contains("地区原文（可能含姓名或地址）"))
        assertTrue(copy.contains("不带孩子兴趣或家庭生活描述"))
        assertFalse(copy.contains("只接收粗略地区"))
    }

    @Test
    fun omittedRegionAndUnconfiguredWorldDoNotClaimARegionDisclosure() {
        val noRegion = requestContextBoundaryCopy(false, true, false)
        val noWorld = requestContextBoundaryCopy(false, false, true)
        assertTrue(noRegion.contains("不带地区"))
        assertFalse(noRegion.contains("地区原文"))
        assertFalse(noWorld.contains("世界信息请求"))
    }
}
