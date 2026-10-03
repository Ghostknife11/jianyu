package org.jianyu.app.ui

import org.jianyu.core.model.LifecycleStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleUiTest {
    @Test
    fun `every lifecycle stage explains authority and child rights`() {
        LifecycleStage.entries.forEach { stage ->
            val model = lifecycleUiModel(stage)
            assertTrue(model.ageRange.isNotBlank())
            assertTrue(model.relationship.isNotBlank())
            assertTrue(model.decision.isNotBlank())
            assertTrue(model.childRight.isNotBlank())
        }
    }

    @Test
    fun `unfinished cryptographic boundaries are explicit in handover and graduation`() {
        assertNotNull(lifecycleUiModel(LifecycleStage.HAND_OVER).unfinishedBoundary)
        assertNotNull(lifecycleUiModel(LifecycleStage.GRADUATION).unfinishedBoundary)
        assertEquals(null, lifecycleUiModel(LifecycleStage.CO_SELECT).unfinishedBoundary)
    }

    @Test
    fun `handover source and person switch copy address the child without claiming identity enforcement`() {
        val handover = aiSourceSetupUiModel(LifecycleStage.HAND_OVER)
        assertEquals("请家长设置 AI", handover.actionLabel)
        assertTrue(handover.description.contains("这台共享设备需由家长连接 AI"))
        assertTrue(handover.description.contains("不发送或保存你的输入"))
        assertEquals("切换查看人", subjectSwitchLabel(LifecycleStage.HAND_OVER))
        assertEquals("切换查看人", subjectSwitchLabel(LifecycleStage.GRADUATION))
        assertEquals("切换孩子", subjectSwitchLabel(LifecycleStage.ACCOMPANY))
        assertEquals("连接 AI 服务", aiSourceSetupUiModel(LifecycleStage.ACCOMPANY).actionLabel)
    }

    @Test
    fun `veto acknowledgement keeps child authority without promising perfect AI avoidance`() {
        val caregiverFacing = childVetoAcknowledgement(LifecycleStage.ACCOMPANY)
        val childFacing = childVetoAcknowledgement(LifecycleStage.HAND_OVER)
        assertTrue(caregiverFacing.contains("孩子仍可以说不要"))
        assertTrue(childFacing.contains("你可以再次说不要"))
        assertTrue(caregiverFacing.contains("若允许 AI 参考近期足迹，这条记录可作为线索"))
        assertFalse(caregiverFacing.contains("会避开重复"))
        assertFalse(childFacing.contains("会避开重复"))
        assertFalse(caregiverFacing.contains("它会把这次拒绝"))
    }
}
