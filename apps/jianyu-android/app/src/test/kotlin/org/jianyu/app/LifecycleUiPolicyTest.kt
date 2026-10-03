package org.jianyu.app

import org.jianyu.app.ui.opportunityComposerUiModel
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.EvidenceVisibility
import org.jianyu.core.domain.lifecycleEvidenceVisibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleUiPolicyTest {
    @Test
    fun `co play keeps shared experience language and local history by default`() {
        val copy = opportunityComposerUiModel(LifecycleStage.CO_PLAY, "小朋友")

        assertTrue(copy.title.contains("一起玩"))
        assertTrue(copy.persistContextByDefault)
    }

    @Test
    fun `hand over addresses the child and does not retain the prompt by default`() {
        val copy = opportunityComposerUiModel(LifecycleStage.HAND_OVER, "小见")

        assertTrue(copy.title.contains("由你决定"))
        assertTrue(copy.description.contains("是否发送"))
        assertTrue(copy.description.contains("是否保存"))
        assertTrue(copy.description.contains("由你决定"))
        assertFalse(copy.description.contains("小见"))
        assertFalse(copy.persistContextByDefault)
    }

    @Test
    fun `graduation cannot default to new family history`() {
        val copy = opportunityComposerUiModel(LifecycleStage.GRADUATION, "小见")

        assertFalse(copy.persistContextByDefault)
        assertTrue(copy.title.contains("停止"))
    }

    @Test
    fun `only hand over can retain an observation in the child private projection`() {
        assertEquals(
            EvidenceVisibility.CHILD_PRIVATE,
            lifecycleEvidenceVisibility(LifecycleStage.HAND_OVER, childPrivateRequested = true),
        )
        assertEquals(
            EvidenceVisibility.SHARED_WITH_CHILD,
            lifecycleEvidenceVisibility(LifecycleStage.CO_SELECT, childPrivateRequested = true),
        )
        assertEquals(
            EvidenceVisibility.GUARDIANS,
            lifecycleEvidenceVisibility(LifecycleStage.ACCOMPANY, childPrivateRequested = true),
        )
    }
}
