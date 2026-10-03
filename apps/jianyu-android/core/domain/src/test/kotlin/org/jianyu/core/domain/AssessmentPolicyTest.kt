package org.jianyu.core.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jianyu.core.model.LifecycleStage

class AssessmentPolicyTest {
    private val birthDate = LocalDate.parse("2014-04-08")
    private val today = LocalDate.parse("2026-09-20")

    @Test
    fun `scored exams follow the age-stage boundary rather than appearing in co-play`() {
        assertFalse(canRecordScoredAssessment(LifecycleStage.CO_PLAY))
        assertTrue(canRecordScoredAssessment(LifecycleStage.ACCOMPANY))
        assertTrue(canRecordScoredAssessment(LifecycleStage.CO_SELECT))
        assertTrue(canRecordScoredAssessment(LifecycleStage.HAND_OVER))
        assertFalse(canRecordScoredAssessment(LifecycleStage.GRADUATION))
    }

    @Test
    fun `normalizes a sourced school signal without deriving a child score`() {
        val entry = normalizeAssessmentEntry(
            subject = " 数学 ",
            assessmentKind = "期中考试",
            score = "92",
            maximum = "100",
            occurredOn = "2026-09-18",
            classAverage = "81",
            percentile = "",
            topics = "函数，几何; 应用题",
            notes = "试卷难度未知",
            earliestDate = birthDate,
            today = today,
        )

        assertEquals("数学 · 期中考试：92/100", entry.summary())
        assertEquals(listOf("函数", "几何", "应用题"), entry.topics)
        assertEquals(81.0, entry.classAverage!!, 0.0)
        assertNull(entry.percentile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a score above its maximum`() {
        normalizeAssessmentEntry(
            subject = "数学",
            assessmentKind = "测验",
            score = "101",
            maximum = "100",
            occurredOn = "2026-09-18",
            earliestDate = birthDate,
            today = today,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a future date`() {
        normalizeAssessmentEntry(
            subject = "数学",
            assessmentKind = "测验",
            score = "90",
            maximum = "100",
            occurredOn = "2026-09-21",
            earliestDate = birthDate,
            today = today,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects an invalid percentile`() {
        normalizeAssessmentEntry(
            subject = "数学",
            assessmentKind = "测验",
            score = "90",
            maximum = "100",
            occurredOn = "2026-09-18",
            percentile = "120",
            earliestDate = birthDate,
            today = today,
        )
    }
}
