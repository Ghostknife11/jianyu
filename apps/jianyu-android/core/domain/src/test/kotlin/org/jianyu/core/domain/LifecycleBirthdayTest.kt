package org.jianyu.core.domain

import org.jianyu.core.model.Child
import org.jianyu.core.model.LifecycleStage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class LifecycleBirthdayTest {
    private val today = LocalDate.of(2026, 9, 15)

    @Test
    fun `stage changes on the exact birthday rather than January first`() {
        assertEquals(LifecycleStage.CO_SELECT, child("2013-09-16").let { lifecycleStage(it, today) })
        assertEquals(LifecycleStage.HAND_OVER, child("2013-09-15").let { lifecycleStage(it, today) })
        assertEquals(LifecycleStage.HAND_OVER, child("2010-09-16").let { lifecycleStage(it, today) })
        assertEquals(LifecycleStage.GRADUATION, child("2010-09-15").let { lifecycleStage(it, today) })
    }

    @Test
    fun `exact age and legacy year-only records are both supported`() {
        assertEquals(12, child("2013-09-16").ageAt(today))
        assertEquals(13, child("2013-09-15").ageAt(today))
        assertEquals(13, child(null, birthYear = 2013).ageAt(today))
    }

    @Test
    fun `graduation remains open ended after age sixteen`() {
        val adult = child("1986-09-15")
        assertEquals(40, adult.ageAt(today))
        assertEquals(LifecycleStage.GRADUATION, lifecycleStage(adult, today))
    }

    @Test
    fun `leap day birthday advances on March first in a non leap year`() {
        val leapDayChild = child("2012-02-29")
        val dayBefore = LocalDate.of(2025, 2, 28)
        val birthday = LocalDate.of(2025, 3, 1)
        assertEquals(12, leapDayChild.ageAt(dayBefore))
        assertEquals(LifecycleStage.CO_SELECT, lifecycleStage(leapDayChild, dayBefore))
        assertEquals(13, leapDayChild.ageAt(birthday))
        assertEquals(LifecycleStage.HAND_OVER, lifecycleStage(leapDayChild, birthday))
    }

    private fun child(birthDate: String?, birthYear: Int = birthDate?.take(4)?.toInt() ?: 2013) = Child(
        id = "child",
        memberId = "member-child",
        displayName = "孩子",
        birthYear = birthYear,
        createdAt = "2026-01-01T00:00:00Z",
        birthDate = birthDate,
    )
}
