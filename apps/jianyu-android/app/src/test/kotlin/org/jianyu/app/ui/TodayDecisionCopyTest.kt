package org.jianyu.app.ui

import org.jianyu.app.currentInterestClueMessage
import org.jianyu.app.discoveryBlankInterestMessage
import org.jianyu.app.discoveryMissingAiMessage
import org.jianyu.core.model.LifecycleStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayDecisionCopyTest {
    @Test
    fun `discovery input prompts keep the hand over reader and caregiver reader distinct`() {
        val childFacing = currentInterestClueMessage(LifecycleStage.HAND_OVER)
        val caregiverFacing = currentInterestClueMessage(LifecycleStage.CO_SELECT)

        assertTrue(childFacing.contains("你可以先留白"))
        assertFalse(childFacing.contains("补充孩子的想法"))
        assertTrue(caregiverFacing.contains("补充孩子的想法"))
        assertTrue(discoveryBlankInterestMessage(LifecycleStage.HAND_OVER).contains("你这次"))
        assertFalse(discoveryBlankInterestMessage(LifecycleStage.HAND_OVER).contains("孩子当前"))
        assertTrue(discoveryMissingAiMessage(LifecycleStage.HAND_OVER).contains("共享设备"))
        assertFalse(discoveryMissingAiMessage(LifecycleStage.HAND_OVER).contains("自己的 AI"))
    }

    @Test
    fun `nothing acknowledgement keeps the right reader and no task pressure`() {
        val childFacing = nothingChoiceAcknowledgement(LifecycleStage.HAND_OVER)
        val caregiverFacing = nothingChoiceAcknowledgement(LifecycleStage.CO_SELECT)

        assertTrue(childFacing.contains("等你想继续探索时再说"))
        assertTrue(caregiverFacing.contains("等孩子有新想法时再说"))
        assertFalse(childFacing.contains("任务"))
        assertFalse(caregiverFacing.contains("进度"))
    }

    @Test
    fun `interest input only shows character count near the cap`() {
        assertEquals("有线索时写一句就够了", interestSupportingText(0))
        assertEquals("有线索时写一句就够了", interestSupportingText(699))
        assertEquals("700/800 · 快到字数上限", interestSupportingText(700))
        assertEquals("800/800 · 快到字数上限", interestSupportingText(800))
    }
}
