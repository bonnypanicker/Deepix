package com.devomind.gallerysearch

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Flat Metro feedback banner — the replacement for Toast on screens that stay alive after the
 * action (a Toast outlives its activity, so save-and-finish flows keep Toast). Slides up from the
 * bottom over the content: pure-black panel, hairline border, optional accent action (UNDO etc.).
 * One banner at a time per activity; a new show() replaces the current one.
 */
object MetroBanner {

    // R.id values are resolved at resource-link time, not compile-time constants — must be val.
    private val TAG_KEY = R.id.metro_banner_tag

    fun show(
        activity: Activity,
        message: String,
        actionLabel: String? = null,
        durationMs: Long = 3500,
        bottomMarginDp: Int = 24,
        onAction: (() -> Unit)? = null
    ) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        dismiss(activity)

        val ctx = activity
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(ctx, R.color.metroBgPrimary))
                setStroke(dp(1), ContextCompat.getColor(ctx, R.color.metroDivider))
                cornerRadius = dp(3).toFloat()
            }
            setPadding(dp(16), dp(14), dp(8), dp(14))
            elevation = dp(8).toFloat()
        }

        panel.addView(TextView(ctx).apply {
            text = message
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.metroTextPrimary))
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = dp(8) }
        })

        if (actionLabel != null) {
            panel.addView(TextView(ctx).apply {
                text = actionLabel
                isAllCaps = true
                textSize = 13f
                letterSpacing = 0.06f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(DesignTokens.accent(ctx))
                background = ContextCompat.getDrawable(ctx, R.drawable.metro_row_pressed)
                includeFontPadding = false
                minHeight = dp(40)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener {
                    dismiss(activity)
                    onAction?.invoke()
                }
            })
        } else {
            panel.setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        // Edge-to-edge: the content frame extends under the nav bar, so lift the banner above it.
        val navBottom = root.rootWindowInsets?.let {
            androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
        } ?: 0
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            leftMargin = dp(16)
            rightMargin = dp(16)
            bottomMargin = dp(bottomMarginDp) + navBottom
        }

        panel.setTag(TAG_KEY, true)
        root.addView(panel, params)
        panel.alpha = 0f
        panel.translationY = dp(24).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(180).start()

        panel.postDelayed({ if (panel.parent != null) fadeOut(panel) }, durationMs)
    }

    fun dismiss(activity: Activity) {
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child.getTag(TAG_KEY) == true) root.removeView(child)
        }
    }

    private fun fadeOut(panel: View) {
        panel.animate().alpha(0f).translationY(panel.height.toFloat() / 3).setDuration(160)
            .withEndAction { (panel.parent as? FrameLayout)?.removeView(panel) }
            .start()
    }
}
