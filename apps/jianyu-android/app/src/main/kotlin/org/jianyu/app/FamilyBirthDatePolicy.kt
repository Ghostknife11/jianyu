package org.jianyu.app

import java.time.LocalDate
import java.time.Period

/** The product has a lower onboarding age, but Graduation is deliberately 16+ with no upper band. */
internal fun validateFamilyBirthDate(
    value: String,
    today: LocalDate = LocalDate.now(),
): LocalDate {
    val birthDate = runCatching { LocalDate.parse(value) }
        .getOrElse { throw IllegalArgumentException("请选择有效的出生日期") }
    val age = Period.between(birthDate, today).years
    require(age >= 4) { "当前版本支持 4 岁起的家庭成员；16 岁起进入成年交接" }
    return birthDate
}
