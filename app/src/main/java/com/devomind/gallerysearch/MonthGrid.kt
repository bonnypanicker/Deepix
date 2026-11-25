package com.devomind.gallerysearch

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Month-grid arithmetic for [MetroDatePicker], kept free of Android types so week starts, leading
 * blanks, the fixed six-row height and the "no future dates" rule are unit-tested rather than tapped.
 */
object MonthGrid {

    /** Rows in the grid. Fixed, so paging from a five-row month to a six-row one does not resize. */
    const val ROWS = 6

    /** Cells in the grid (six weeks of seven days). */
    const val CELL_COUNT = ROWS * 7

    /** The first cell of [month]'s grid: the week's first day on or before the 1st. */
    fun firstCell(month: YearMonth, firstDay: DayOfWeek): LocalDate =
        month.atDay(1).with(TemporalAdjusters.previousOrSame(firstDay))

    /**
     * [CELL_COUNT] consecutive days starting at [firstCell]. Cells outside [month] are still returned
     * so the caller can blank them and keep every row the same height.
     */
    fun cells(month: YearMonth, firstDay: DayOfWeek): List<LocalDate> {
        val start = firstCell(month, firstDay)
        return (0 until CELL_COUNT).map { start.plusDays(it.toLong()) }
    }

    /** Locale weekday labels in grid order, upper-cased to match the Metro section labels. */
    fun weekdayLabels(firstDay: DayOfWeek, locale: Locale): List<String> =
        (0 until 7).map { offset ->
            firstDay.plus(offset.toLong()).getDisplayName(TextStyle.SHORT, locale).uppercase(locale)
        }

    /**
     * The next month, or null when [month] is already [today]'s month. A gallery cannot hold a photo
     * taken in the future, so the picker never pages past the current month.
     */
    fun nextMonth(month: YearMonth, today: LocalDate): YearMonth? =
        if (month < YearMonth.from(today)) month.plusMonths(1) else null

    fun previousMonth(month: YearMonth): YearMonth = month.minusMonths(1)

    /** A day is searchable when it is today or earlier. */
    fun selectable(date: LocalDate, today: LocalDate): Boolean = !date.isAfter(today)
}
