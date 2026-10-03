package org.jianyu.app.ui

import java.time.LocalDate
import org.jianyu.core.model.Child
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssessmentFormValidationTest {
    private val child = Child(
        id = "synthetic-child",
        memberId = "synthetic-member",
        displayName = "测试孩子",
        birthYear = 2014,
        createdAt = "2026-09-01T00:00:00Z",
        birthDate = "2014-04-08",
    )
    private val today = LocalDate.parse("2026-09-24")
    private val validDraft = AssessmentFormDraft(
        childId = child.id,
        subject = "数学",
        assessmentKind = "一次测验",
        score = "82",
        maximum = "100",
        occurredOn = "2026-09-20",
        classAverage = "",
        percentile = "",
        topics = "",
        notes = "",
        childConfirmed = false,
    )

    @Test
    fun `valid draft is ready for the local save action`() {
        assertNull(assessmentDraftValidationMessage(child, validDraft, today))
    }

    @Test
    fun `score above maximum gets the domain correction before save`() {
        assertEquals(
            "得分必须在 0 到满分之间",
            assessmentDraftValidationMessage(child, validDraft.copy(score = "120"), today),
        )
    }

    @Test
    fun `maximum upper bound is stated accurately`() {
        assertEquals(
            "满分必须大于 0 且不超过 10000",
            assessmentDraftValidationMessage(child, validDraft.copy(maximum = "10001"), today),
        )
    }

    @Test
    fun `optional comparison also blocks an invalid draft`() {
        assertEquals(
            "百分位必须在 0 到 100 之间",
            assessmentDraftValidationMessage(child, validDraft.copy(percentile = "120"), today),
        )
    }

    @Test
    fun `calendar fact cannot predate the child's birth`() {
        assertEquals(
            "日期必须在孩子出生后且不晚于今天",
            assessmentDraftValidationMessage(child, validDraft.copy(occurredOn = "2013-09-20"), today),
        )
    }
}
