package org.jianyu.core.domain

import org.jianyu.core.model.Child
import org.jianyu.core.model.LifecycleStage
import java.time.LocalDate
import java.time.Period
import java.time.Year

fun lifecycleStage(birthYear: Int, currentYear: Int = Year.now().value): LifecycleStage {
    return lifecycleStageForAge(currentYear - birthYear)
}

/** Uses an exact birthday when available and falls back to legacy year-only vault data. */
fun Child.ageAt(referenceDate: LocalDate = LocalDate.now()): Int {
    val exactBirthDate = birthDate?.let { stored -> runCatching { LocalDate.parse(stored) }.getOrNull() }
    return if (exactBirthDate != null) {
        Period.between(exactBirthDate, referenceDate).years.coerceAtLeast(0)
    } else {
        (referenceDate.year - birthYear).coerceAtLeast(0)
    }
}

fun lifecycleStage(child: Child, referenceDate: LocalDate = LocalDate.now()): LifecycleStage =
    lifecycleStageForAge(child.ageAt(referenceDate))

private fun lifecycleStageForAge(age: Int): LifecycleStage {
    return when {
        age <= 6 -> LifecycleStage.CO_PLAY
        age <= 9 -> LifecycleStage.ACCOMPANY
        age <= 12 -> LifecycleStage.CO_SELECT
        age <= 15 -> LifecycleStage.HAND_OVER
        else -> LifecycleStage.GRADUATION
    }
}
