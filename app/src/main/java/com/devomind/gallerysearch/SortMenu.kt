package com.devomind.gallerysearch

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * The sort dropdown, shared by every listing. Anchored under the header's sort label and
 * styled to match the Metro sheets: flat surface, square corners, no elevation, accent
 * on the active row only. Dismisses itself as soon as something is picked.
 */
object SortMenu {

    private const val RowPaddingHorizontalDp = 20
    private const val RowPaddingVerticalDp = 13
    private const val RowTextSizeSp = 15f
    private const val CheckmarkSizeDp = 18
    private const val CheckmarkGapDp = 16
    private const val MenuVerticalOffsetDp = 4
    private const val MenuMinWidthDp = 200
    private const val EnterTranslationDp = -8f

    /**
     * Shows the menu anchored to [anchor], right-aligned with it. [onSelected] fires only
     * when the user picks a different order than [current].
     */
    fun show(
        anchor: View,
        current: SortOption,
        options: List<SortOption> = SortOption.entries,
        onSelected: (SortOption) -> Unit
    ) {
        val context = anchor.context
        val accent = DesignTokens.accent(context)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.info_sheet_bg)
            minimumWidth = dp(context, MenuMinWidthDp)
            setPadding(0, dp(context, RowPaddingVerticalDp / 2), 0, dp(context, RowPaddingVerticalDp / 2))
        }

        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        popup.elevation = 0f

        options.forEach { option ->
            content.addView(
                buildRow(context, option, selected = option == current, accent = accent) {
                    popup.dismiss()
                    if (option != current) onSelected(option)
                }
            )
        }

        popup.showAsDropDown(
            anchor,
            0,
            dp(context, MenuVerticalOffsetDp),
            Gravity.END
        )
        animateIn(context, content)
    }

    private fun buildRow(
        context: Context,
        option: SortOption,
        selected: Boolean,
        accent: Int,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.metro_row_pressed)
            isClickable = true
            isFocusable = true
            setPadding(
                dp(context, RowPaddingHorizontalDp),
                dp(context, RowPaddingVerticalDp),
                dp(context, RowPaddingHorizontalDp),
                dp(context, RowPaddingVerticalDp)
            )
            setOnClickListener { onClick() }
        }
        row.addView(
            TextView(context).apply {
                text = option.label
                setTextAppearance(R.style.TextAppearance_Metro_CompactAction)
                textSize = RowTextSizeSp
                includeFontPadding = false
                setTextColor(if (selected) accent else DesignTokens.textPrimary(context))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
        )
        row.addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_fluent_checkmark_24_regular)
                imageTintList = ColorStateList.valueOf(accent)
                layoutParams = LinearLayout.LayoutParams(
                    dp(context, CheckmarkSizeDp),
                    dp(context, CheckmarkSizeDp)
                ).apply { marginStart = dp(context, CheckmarkGapDp) }
                visibility = if (selected) View.VISIBLE else View.INVISIBLE
            }
        )
        return row
    }

    /** Short fade plus a small downward settle — enough to feel responsive without slowing the tap. */
    private fun animateIn(context: Context, content: View) {
        content.alpha = 0f
        content.translationY = EnterTranslationDp * context.resources.displayMetrics.density
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(DesignTokens.MENU_FADE_DURATION_MS)
            .start()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()
}
