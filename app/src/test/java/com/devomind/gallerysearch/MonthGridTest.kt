package com.devomind.gallerysearch

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthGridTest {

    // November 2026 starts on a Sunday, so it is the cleanest month to pin both week-start rules.
    private val november = YearMonth.of(2026, 11)
    private val today = LocalDate.of(2026, 11, 24)

    @Test
    fun `monday-first grid starts on the monday before the 1st`() {
        val cells = MonthGrid.cells(november, DayOfWeek.MONDAY)

        assertEquals(MonthGrid.CELL_COUNT, cells.size)
        assertEquals(LocalDate.of(2026, 10, 26), cells.first())
        assertEquals(LocalDate.of(2026, 12, 6), cells.last())
        // Every day of November is present exactly once, at the offset its weekday dictates.
        assertTrue(cells.contains(LocalDate.of(2026, 11, 1)))
        assertTrue(cells.contains(LocalDate.of(2026, 11, 30)))
    }

    @Test
    fun `sunday-first grid starts on the 1st when the month begins on a sunday`() {
        val cells = MonthGrid.cells(november, DayOfWeek.SUNDAY)

        assertEquals(LocalDate.of(2026, 11, 1), cells.first())
        assertEquals(LocalDate.of(2026, 12, 12), cells.last())
    }

    @Test
    fun `weekday labels follow the locale's first day`() {
        assertEquals(
            listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"),
            MonthGrid.weekdayLabels(DayOfWeek.SUNDAY, Locale.US)
        )
        assertEquals(
            listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"),
            MonthGrid.weekdayLabels(DayOfWeek.MONDAY, Locale.UK)
        )
    }

    @Test
    fun `paging forward stops at the current month`() {
        assertNull(MonthGrid.nextMonth(YearMonth.from(today), today))
        assertEquals(YearMonth.of(2026, 12), MonthGrid.nextMonth(YearMonth.of(2026, 11), LocalDate.of(2026, 12, 3)))
        assertEquals(YearMonth.of(2026, 10), MonthGrid.previousMonth(YearMonth.of(2026, 11)))
    }

    @Test
    fun `today is selectable and tomorrow is not`() {
        assertTrue(MonthGrid.selectable(today, today))
        assertTrue(MonthGrid.selectable(today.minusDays(1), today))
        assertFalse(MonthGrid.selectable(today.plusDays(1), today))
    }
}
