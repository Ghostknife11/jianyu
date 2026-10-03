package org.jianyu.app

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FamilyBirthDatePolicyTest {
    private val today = LocalDate.of(2026, 9, 20)

    @Test
    fun `adult ages remain valid because Graduation is sixteen plus`() {
        assertEquals(
            LocalDate.of(1986, 9, 20),
            validateFamilyBirthDate("1986-09-20", today),
        )
    }

    @Test
    fun `fourth birthday is the lower inclusive boundary`() {
        assertEquals(
            LocalDate.of(2022, 9, 20),
            validateFamilyBirthDate("2022-09-20", today),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateFamilyBirthDate("2022-09-21", today)
        }
    }

    @Test
    fun `invalid and future dates fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateFamilyBirthDate("not-a-date", today)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateFamilyBirthDate("2027-01-01", today)
        }
    }

    @Test
    fun `leap day child reaches fourth birthday on the exact date in a leap year`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateFamilyBirthDate("2020-02-29", LocalDate.of(2024, 2, 28))
        }
        assertEquals(
            LocalDate.of(2020, 2, 29),
            validateFamilyBirthDate("2020-02-29", LocalDate.of(2024, 2, 29)),
        )
    }

    @Test
    fun `leap day fourth birthday uses March first in a non leap century year`() {
        assertThrows(IllegalArgumentException::class.java) {
            validateFamilyBirthDate("2024-02-29", LocalDate.of(2028, 2, 28))
        }
        assertEquals(
            LocalDate.of(2024, 2, 29),
            validateFamilyBirthDate("2024-02-29", LocalDate.of(2028, 2, 29)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validateFamilyBirthDate("2096-02-29", LocalDate.of(2100, 2, 28))
        }
        assertEquals(
            LocalDate.of(2096, 2, 29),
            validateFamilyBirthDate("2096-02-29", LocalDate.of(2100, 3, 1)),
        )
    }
}
