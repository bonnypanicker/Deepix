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
        /** A row with children is a header: tapping it opens these beside the menu instead of acting. */
        val children: List<Item> = emptyList(),
        val onClick: () -> Unit
    )

    fun show(anchor: View, items: List<Item>) {
        if (items.isEmpty()) return
        val context = anchor.context
        val menu = column(context, MenuMinWidthDp)

        // A flyout opens to the menu's left, inside this same window: two PopupWindows would let the
        // modal parent swallow the child's touches. The window spans the screen and both columns sit
        // at its right, so revealing the flyout moves neither the menu nor the window — and nothing is
        // measured, which matters because a weighted child under a WRAP_CONTENT parent has no defined
        // width to measure.
        val hasGroups = items.any { it.children.isNotEmpty() }
        val flyout = if (hasGroups) column(context, FlyoutMinWidthDp).apply { visibility = View.GONE } else null
        val slack = if (hasGroups) View(context) else null
        val content: View = if (flyout == null || slack == null) menu else LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            slack.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            addView(slack)
            addView(
                flyout,
                LinearLayout.LayoutParams(flyoutWidth(anchor), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dp(context, FlyoutGapDp)
                }
            )
            addView(menu)
        }

        val popup = PopupWindow(
            content,
            if (flyout == null) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            elevation = 0f
            isOutsideTouchable = true
        }
        // Before the flyout opens, the reserved width beside the menu is empty space, and empty space
        // beside a menu belongs to "tap outside": with a modal window it would otherwise be inert.
        slack?.setOnClickListener { popup.dismiss() }

        items.forEach { item ->
            val row = buildRow(context, item)
            row.setOnClickListener {
                if (item.children.isEmpty()) {
                    popup.dismiss()
                    item.onClick()
                } else if (flyout != null) {
                    toggleFlyout(context, popup, flyout, menu, row, item.children)
                }
            }
            menu.addView(row)
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

    /** Opens [children] beside [row], or closes them when the same header is tapped again. */
    private fun toggleFlyout(
        context: Context,
        popup: PopupWindow,
        flyout: LinearLayout,
        menu: LinearLayout,
        row: View,
        children: List<Item>
    ) {
        if (flyout.tag === row) {
            flyout.tag = null
            flyout.removeAllViews()
            flyout.visibility = View.GONE
            return
        }
        flyout.removeAllViews()
        children.forEach { child ->
            val childRow = buildRow(context, child)
            childRow.setOnClickListener {
                popup.dismiss()
                child.onClick()
            }
            flyout.addView(childRow)
        }
        flyout.tag = row
        setFlyoutTop(flyout, row.top)
        flyout.visibility = View.VISIBLE
        flyout.alpha = 0f
        flyout.animate().alpha(1f).setDuration(DesignTokens.MENU_FADE_DURATION_MS).start()
        // The window is never resized while a flyout opens, so a flyout that would hang below the
        // menu's own bottom has to be pulled up instead — which only shows once both are laid out.
        flyout.post {
            val overflow = flyout.top + flyout.height - menu.height
            if (overflow > 0) setFlyoutTop(flyout, (row.top - overflow).coerceAtLeast(0))
        }
    }

    private fun setFlyoutTop(flyout: LinearLayout, top: Int) {
        (flyout.layoutParams as LinearLayout.LayoutParams).topMargin = top
        flyout.requestLayout()
    }

    /**
     * The widest flyout that still leaves the menu column on screen, measured against the app's own
     * window rather than the display — in split-screen the display is wider than the room there is.
     * A window too narrow for even the floor takes whatever is left: the flyout's labels wrap before
     * the menu is pushed off the edge.
     */
    private fun flyoutWidth(anchor: View): Int {
        val context = anchor.context
        val window = anchor.rootView.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val available = window - dp(context, MenuMinWidthDp) - dp(context, FlyoutGapDp)
        val floor = dp(context, FlyoutMinWidthDp)
        return if (available >= floor) available.coerceAtMost(dp(context, FlyoutMaxWidthDp))
        else available.coerceAtLeast(0)
    }

    private fun column(context: Context, minWidthDp: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.info_sheet_bg)
        minimumWidth = dp(context, minWidthDp)
        setPadding(0, dp(context, MenuPaddingVerticalDp), 0, dp(context, MenuPaddingVerticalDp))
    }

    private fun buildRow(context: Context, item: Item): View {
        val accent = DesignTokens.accent(context)
        val textColor = when {
            item.danger -> ContextCompat.getColor(context, R.color.metroDanger)
            item.selected -> accent
            else -> DesignTokens.textPrimary(context)
        }
        val hasChildren = item.children.isNotEmpty()
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
                // A header gets the chevron that promises a flyout; a leaf gets the tick, held
                // invisible when unset so both rows keep the same width.
                setImageResource(
                    if (hasChildren) R.drawable.ic_fluent_chevron_right_24_regular
                    else R.drawable.ic_fluent_checkmark_24_regular
                )
                imageTintList = ColorStateList.valueOf(accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = LinearLayout.LayoutParams(
                    dp(context, GlyphSizeDp),
                    dp(context, GlyphSizeDp)
                ).apply { marginStart = dp(context, CheckmarkGapDp) }
                visibility = if (hasChildren || item.selected) View.VISIBLE else View.INVISIBLE
            })
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private const val RowPaddingHorizontalDp = 20
    private const val RowPaddingVerticalDp = 13
    private const val RowTextSizeSp = 15f
    private const val GlyphSizeDp = 18
    private const val CheckmarkGapDp = 16
    private const val MenuPaddingVerticalDp = 6
    private const val MenuVerticalOffsetDp = 4
    private const val MenuMinWidthDp = 200
    private const val FlyoutMinWidthDp = 130
    private const val FlyoutMaxWidthDp = 170
    private const val FlyoutGapDp = 6
    private const val EnterTranslationDp = -8f
}
