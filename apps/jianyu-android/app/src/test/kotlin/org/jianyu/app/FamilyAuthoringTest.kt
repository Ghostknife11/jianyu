package org.jianyu.app

import org.jianyu.core.model.Child
import org.jianyu.core.model.FamilyMember
import org.jianyu.core.model.FamilyState
import org.jianyu.core.model.Household
import org.jianyu.core.model.LifecycleStage
import org.jianyu.core.model.MemberRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyAuthoringTest {
    private val caregiverA = FamilyMember("adult-a", "照护者 A", MemberRole.CAREGIVER, createdAt = "2026-01-01T00:00:00Z")
    private val caregiverB = FamilyMember("adult-b", "照护者 B", MemberRole.GUARDIAN, createdAt = "2026-01-01T00:00:00Z")
    private val child = FamilyMember("child", "孩子", MemberRole.CHILD, subjectId = "subject", createdAt = "2026-01-01T00:00:00Z")
    private val subject = Child("subject", "child", "孩子", 2012, "2026-01-01T00:00:00Z")
    private val family = FamilyState(
        household = Household("house", "测试家庭", "2026-01-01T00:00:00Z"),
        members = listOf(caregiverA, caregiverB, child),
        children = emptyList(),
    )

    @Test
    fun `preferred eligible caregiver remains the author`() {
        assertEquals("adult-b", family.resolveCaregiverAuthor("adult-b").id)
    }

    @Test
    fun `missing or child preference falls back to a caregiver`() {
        assertEquals("adult-a", family.resolveCaregiverAuthor("missing").id)
        assertEquals("adult-a", family.resolveCaregiverAuthor("child").id)
    }

    @Test
    fun `child cannot author caregiver actions by switching a shared-device label`() {
        assertTrue(caregiverA.canAuthorCaregiverActions())
        assertTrue(caregiverB.canAuthorCaregiverActions())
        assertFalse(child.canAuthorCaregiverActions())
    }

    @Test
    fun `hand over discoveries require the subject child member as author`() {
        assertEquals("child", family.resolveDiscoveryAuthor(subject, LifecycleStage.HAND_OVER, "adult-b").id)
        assertEquals("adult-b", family.resolveDiscoveryAuthor(subject, LifecycleStage.CO_SELECT, "adult-b").id)

        val missingChildMember = family.copy(members = listOf(caregiverA, caregiverB))
        assertTrue(runCatching {
            missingChildMember.resolveDiscoveryAuthor(subject, LifecycleStage.HAND_OVER, "adult-b")
        }.exceptionOrNull() is IllegalStateException)

        val wrongSubjectMember = family.copy(members = listOf(caregiverA, caregiverB, child.copy(subjectId = "other")))
        assertTrue(runCatching {
            wrongSubjectMember.resolveDiscoveryAuthor(subject, LifecycleStage.HAND_OVER, "adult-b")
        }.exceptionOrNull() is IllegalStateException)
    }
}
