package com.devomind.gallerysearch

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields

/**
 * The calendar behind the glyph beside "Jump to date".
 *
 * Built in the app's own dialog language rather than the platform date picker: flat near-black panel,
 * thin uppercase title with the accent Fluent calendar, and flat press overlays on every day. Weekday
 * order comes from the locale, the grid keeps a fixed six rows so paging never resizes the panel, and
 * future days are inert because a gallery cannot hold a photo taken tomorrow.
 */
object MetroDatePicker {

    /**
     * Shows the picker at [initial]'s month (clamped to today) and reports the tapped day. The dialog
     * dismisses itself before [onPick], so the caller is free to start a search immediately.
     */
    fun show(
        context: Context,
        initial: LocalDate = LocalDate.now(),
        onPick: (LocalDate) -> Unit
    ): AlertDialog {
        val today = LocalDate.now()
        val locale = context.resources.configuration.locales[0]
        val firstDay = WeekFields.of(locale).firstDayOfWeek
        val start = if (initial.isAfter(today)) today else initial
        var month = YearMonth.from(start)

        val root = LayoutInflater.from(context).inflate(R.layout.dialog_metro_date_picker, null)
        val monthLabel = root.findViewById<TextView>(R.id.datePickerMonth)
        val weekdays = root.findViewById<LinearLayout>(R.id.datePickerWeekdays)
        val gridScroll = root.findViewById<ScrollView>(R.id.datePickerGridScroll)
        val grid = root.findViewById<LinearLayout>(R.id.datePickerGrid)
        val previous = root.findViewById<View>(R.id.datePickerPrev)
        val next = root.findViewById<View>(R.id.datePickerNext)

        val dialog = AlertDialog.Builder(context, R.style.Theme_GallerySearch_Dialog)
            .setView(root)
            .setCancelable(true)
            .create()

        val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        val dayDescription = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
        val cellHeightPx = fitGridToWindow(context, gridScroll)
        val accent = DesignTokens.accent(context)

        fun render() {
            monthLabel.text = month.format(monthFormat)
            val forward = MonthGrid.nextMonth(month, today)
            next.isEnabled = forward != null
            next.alpha = if (next.isEnabled) 1f else 0.35f

            weekdays.removeAllViews()
            MonthGrid.weekdayLabels(firstDay, locale).forEach { label ->
                weekdays.addView(weekdayCell(context, label))
            }

            grid.removeAllViews()
            MonthGrid.cells(month, firstDay).chunked(7).forEach { week ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                week.forEach { date ->
                    row.addView(
                        dayCell(context, date, month, today, cellHeightPx, accent, dayDescription) { picked ->
                            dialog.dismiss()
                            onPick(picked)
                        }
                    )
                }
                grid.addView(row)
            }
        }

        previous.setOnClickListener {
            month = MonthGrid.previousMonth(month)
            render()
        }
        next.setOnClickListener {
            MonthGrid.nextMonth(month, today)?.let {
                month = it
                render()
            }
        }
        root.findViewById<ImageView>(R.id.datePickerDismiss).setOnClickListener { dialog.dismiss() }

        render()
        dialog.setOnShowListener {
            val metrics = context.resources.displayMetrics
            // A calendar stretched across a tablet reads as a table, so the panel keeps a column width
            // and only uses what a phone would.
            val maxWidth = (392 * metrics.density).toInt()
            val edge = (24 * metrics.density).toInt()
            dialog.window?.setLayout(
                minOf(maxWidth, metrics.widthPixels - edge),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
        return dialog
    }

    /**
     * Sizes the day grid to the window and returns the height of one day cell.
     *
     * Chrome around the grid (panel padding, header, month nav, weekday row and dialog margins) is
     * about 176dp. The rest is divided over six weeks, between 30dp and 40dp per cell; when even 30dp
     * does not fit — a phone lying sideways — the scroll takes the remaining height instead of the
     * grid growing past the window.
     */
    private fun fitGridToWindow(context: Context, scroll: View): Int {
        val density = context.resources.displayMetrics.density
        val availableDp = (Responsive.heightDp(context) - CHROME_DP - EDGE_DP).coerceAtLeast(1)
        val cellDp = (availableDp / MonthGrid.ROWS).coerceIn(MIN_CELL_DP, MAX_CELL_DP)
        val gridDp = cellDp * MonthGrid.ROWS
        scroll.layoutParams = scroll.layoutParams.apply {
            height = (minOf(gridDp, availableDp) * density).toInt()
        }
        return (cellDp * density).toInt()
    }

    private fun weekdayCell(context: Context, label: String): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            letterSpacing = 0.06f
            text = label
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Metro_Caption)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

    private fun dayCell(
        context: Context,
        date: LocalDate,
        month: YearMonth,
        today: LocalDate,
        cellHeightPx: Int,
        accent: Int,
        dayDescription: DateTimeFormatter,
        onPick: (LocalDate) -> Unit
    ): TextView =
        TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, cellHeightPx, 1f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)

            if (YearMonth.from(date) != month) {
                // Blanks keep the six rows aligned; there is nothing to announce in an adjacent month.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                return@apply
            }

            text = date.dayOfMonth.toString()
            contentDescription = date.format(dayDescription)
            when {
                !MonthGrid.selectable(date, today) -> {
                    setTextColor(ContextCompat.getColor(context, R.color.metroTextDisabled))
                    isEnabled = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                date == today -> {
                    setTextColor(accent)
                    background = ContextCompat.getDrawable(context, R.drawable.date_picker_today_bg)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onPick(date) }
                }
                else -> {
                    setTextColor(ContextCompat.getColor(context, R.color.metroTextPrimary))
                    background = ContextCompat.getDrawable(context, R.drawable.metro_row_pressed)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onPick(date) }
                }
            }
        }

    /** Non-grid height of the panel, in dp: margins, padding, header, month nav and weekday row. */
    private const val CHROME_DP = 176

    /** Breathing room so the fitted panel never touches the window edge. */
    private const val EDGE_DP = 8

    private const val MIN_CELL_DP = 30
    private const val MAX_CELL_DP = 40
}
