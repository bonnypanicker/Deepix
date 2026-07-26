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

/** Shared flat Metro dropdown used by sort controls and anchored contextual actions. */
object MetroDropdownMenu {

    data class Item(
        val label: CharSequence,
        val selected: Boolean = false,
        val danger: Boolean = false,
        val onClick: () -> Unit
    )

    fun show(anchor: View, items: List<Item>) {
        if (items.isEmpty()) return
        val context = anchor.context
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.info_sheet_bg)
            minimumWidth = dp(context, MenuMinWidthDp)
            setPadding(0, dp(context, MenuPaddingVerticalDp), 0, dp(context, MenuPaddingVerticalDp))
        }
        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = 0f
            isOutsideTouchable = true
        }

        items.forEach { item ->
            content.addView(buildRow(context, item) {
                popup.dismiss()
                item.onClick()
            })
        }
        popup.showAsDropDown(anchor, 0, dp(context, MenuVerticalOffsetDp), Gravity.END)
        content.alpha = 0f
        content.translationY = EnterTranslationDp * context.resources.displayMetrics.density
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(DesignTokens.MENU_FADE_DURATION_MS)
            .start()
    }

    private fun buildRow(context: Context, item: Item, onClick: () -> Unit): View {
        val accent = DesignTokens.accent(context)
        val textColor = when {
            item.danger -> ContextCompat.getColor(context, R.color.metroDanger)
            item.selected -> accent
            else -> DesignTokens.textPrimary(context)
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.metro_row_pressed)
            isClickable = true
            isFocusable = true
            contentDescription = item.label
            setPadding(
                dp(context, RowPaddingHorizontalDp),
                dp(context, RowPaddingVerticalDp),
                dp(context, RowPaddingHorizontalDp),
                dp(context, RowPaddingVerticalDp)
            )
            addView(TextView(context).apply {
                text = item.label
                setTextAppearance(R.style.TextAppearance_Metro_CompactAction)
                textSize = RowTextSizeSp
                includeFontPadding = false
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_fluent_checkmark_24_regular)
                imageTintList = ColorStateList.valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(
                    dp(context, CheckmarkSizeDp),
                    dp(context, CheckmarkSizeDp)
                ).apply { marginStart = dp(context, CheckmarkGapDp) }
                visibility = if (item.selected) View.VISIBLE else View.INVISIBLE
            })
            setOnClickListener { onClick() }
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private const val RowPaddingHorizontalDp = 20
    private const val RowPaddingVerticalDp = 13
    private const val RowTextSizeSp = 15f
    private const val CheckmarkSizeDp = 18
    private const val CheckmarkGapDp = 16
    private const val MenuPaddingVerticalDp = 6
    private const val MenuVerticalOffsetDp = 4
    private const val MenuMinWidthDp = 200
    private const val EnterTranslationDp = -8f
}
