package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat

/**
 * The one way to build dialogs in Deepix. Produces the Metro dialog language established by
 * dialog_smart_album.xml: flat near-black panel, thin uppercase title with an optional accent
 * Fluent icon, secondary body text, and a flat footer (plain CANCEL + accent pill primary — red
 * pill when [danger]).
 *
 * Stock AlertDialog title/message/button chrome must not be used with Theme.GallerySearch.Dialog:
 * its transparent windowBackground leaves that chrome floating over the dim with no panel.
 */
object MetroDialog {

    /** Simple title + message + single acknowledge button. */
    fun message(
        context: Context,
        title: String,
        message: CharSequence,
        @DrawableRes iconRes: Int? = null,
        positive: String = "Got it",
        scrollableMessage: Boolean = false,
        cancelable: Boolean = true,
        onPositive: (() -> Unit)? = null
    ): AlertDialog {
        val v = inflate(context, title, iconRes)
        v.body.visibility = View.VISIBLE
        v.body.text = message
        if (scrollableMessage) {
            v.body.maxHeight = dp(context, 360)
            v.body.movementMethod = ScrollingMovementMethod()
            v.body.typeface = Typeface.MONOSPACE
            v.body.textSize = 11f
            v.body.setTextIsSelectable(true)
        }
        val dialog = create(context, v.root, cancelable)
        v.positive.text = positive
        v.positive.setOnClickListener {
            dialog.dismiss()
            onPositive?.invoke()
        }
        dialog.show()
        return dialog
    }

    /** Two-choice confirmation. [danger] switches the primary pill to Metro red. */
    fun confirm(
        context: Context,
        title: String,
        message: CharSequence,
        positive: String,
        negative: String = "Cancel",
        danger: Boolean = false,
        @DrawableRes iconRes: Int? = null,
        cancelable: Boolean = true,
        onNegative: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
        onPositive: () -> Unit
    ): AlertDialog {
        val v = inflate(context, title, iconRes)
        v.body.visibility = View.VISIBLE
        v.body.text = message
        val dialog = create(context, v.root, cancelable)
        v.positive.text = positive
        if (danger) v.positive.setBackgroundResource(R.drawable.dialog_danger_button_bg)
        v.positive.setOnClickListener {
            dialog.dismiss()
            onPositive()
        }
        v.negative.visibility = View.VISIBLE
        v.negative.text = negative
        v.negative.setOnClickListener {
            dialog.dismiss()
            onNegative?.invoke()
        }
        onCancel?.let { handler -> dialog.setOnCancelListener { handler() } }
        dialog.show()
        return dialog
    }

    /** Labelled single-line text input with CANCEL + accent submit. */
    fun input(
        context: Context,
        title: String,
        label: String,
        positive: String,
        initial: String = "",
        hint: String = "",
        @DrawableRes iconRes: Int? = null,
        onSubmit: (String) -> Unit
    ): AlertDialog {
        val v = inflate(context, title, iconRes)
        v.inputLabel.visibility = View.VISIBLE
        v.inputLabel.text = label
        v.input.visibility = View.VISIBLE
        v.input.hint = hint
        v.input.setText(initial)
        v.input.setSelection(initial.length)
        val dialog = create(context, v.root, cancelable = true)
        val submit = {
            val text = v.input.text.toString().trim()
            if (text.isNotEmpty()) {
                dialog.dismiss()
                onSubmit(text)
            }
        }
        v.positive.text = positive
        v.positive.setOnClickListener { submit() }
        v.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit(); true
            } else {
                false
            }
        }
        v.negative.visibility = View.VISIBLE
        v.negative.text = "Cancel"
        v.negative.setOnClickListener { dialog.dismiss() }
        dialog.show()
        v.input.requestFocus()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )
        return dialog
    }

    /** Flat tap-to-pick option list (replaces AlertDialog.setItems). No footer buttons. */
    fun items(
        context: Context,
        options: List<String>,
        title: String? = null,
        dangerIndices: Set<Int> = emptySet(),
        onSelect: (Int) -> Unit
    ): AlertDialog {
        val v = inflate(context, title, iconRes = null)
        v.content.visibility = View.VISIBLE
        v.footer.visibility = View.GONE
        if (title == null) v.root.setPadding(0, dp(context, 8), 0, dp(context, 8))
        val dialog = create(context, v.root, cancelable = true)
        options.forEachIndexed { index, option ->
            v.content.addView(row(context, option).apply {
                if (index in dangerIndices) setTextColor(ContextCompat.getColor(context, R.color.metroDanger))
                setOnClickListener {
                    dialog.dismiss()
                    onSelect(index)
                }
            })
        }
        dialog.show()
        return dialog
    }

    /**
     * Flat single-choice list (replaces AlertDialog.setSingleChoiceItems). The selected row is
     * accent-colored with a trailing checkmark; picking a row dismisses and reports it.
     * [swatches] optionally prefixes each row with a color dot (accent picker).
     */
    fun singleChoice(
        context: Context,
        title: String,
        options: List<String>,
        checkedIndex: Int,
        swatches: List<Int>? = null,
        onSelect: (Int) -> Unit
    ): AlertDialog {
        val v = inflate(context, title, iconRes = null)
        v.content.visibility = View.VISIBLE
        val dialog = create(context, v.root, cancelable = true)
        val accent = DesignTokens.accent(context)
        options.forEachIndexed { index, option ->
            val selected = index == checkedIndex
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 52)
                setPadding(dp(context, 24), 0, dp(context, 24), 0)
                background = ContextCompat.getDrawable(context, R.drawable.metro_row_pressed)
                isClickable = true
                isFocusable = true
            }
            swatches?.getOrNull(index)?.let { color ->
                rowLayout.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(context, 16), dp(context, 16))
                        .apply { marginEnd = dp(context, 14) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                    }
                })
            }
            rowLayout.addView(row(context, option, padded = false).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                if (selected) setTextColor(accent)
            })
            if (selected) {
                rowLayout.addView(ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(context, 20), dp(context, 20))
                    setImageResource(R.drawable.ic_fluent_checkmark_24_regular)
                    imageTintList = android.content.res.ColorStateList.valueOf(accent)
                })
            }
            rowLayout.setOnClickListener {
                dialog.dismiss()
                if (index != checkedIndex) onSelect(index)
            }
            v.content.addView(rowLayout)
        }
        v.footer.visibility = View.GONE
        dialog.show()
        return dialog
    }

    // ------------------------------------------------------------------ internals

    private class Views(val root: View) {
        val icon: ImageView = root.findViewById(R.id.metroDialogIcon)
        val title: TextView = root.findViewById(R.id.metroDialogTitle)
        val header: View = root.findViewById(R.id.metroDialogHeader)
        val body: TextView = root.findViewById(R.id.metroDialogBody)
        val inputLabel: TextView = root.findViewById(R.id.metroDialogInputLabel)
        val input: EditText = root.findViewById(R.id.metroDialogInput)
        val content: LinearLayout = root.findViewById(R.id.metroDialogContent)
        val footer: View = root.findViewById(R.id.metroDialogFooter)
        val negative: TextView = root.findViewById(R.id.metroDialogNegative)
        val positive: TextView = root.findViewById(R.id.metroDialogPositive)
    }

    private fun inflate(context: Context, title: String?, @DrawableRes iconRes: Int?): Views {
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_metro_generic, null)
        val v = Views(root)
        if (title == null) {
            v.header.visibility = View.GONE
        } else {
            v.title.text = title
        }
        if (iconRes != null) {
            v.icon.visibility = View.VISIBLE
            v.icon.setImageResource(iconRes)
        } else {
            // No icon: drop the title's icon offset so it left-aligns with the body.
            (v.title.layoutParams as LinearLayout.LayoutParams).marginStart = 0
        }
        return v
    }

    private fun create(context: Context, view: View, cancelable: Boolean): AlertDialog =
        AlertDialog.Builder(context, R.style.Theme_GallerySearch_Dialog)
            .setView(view)
            .setCancelable(cancelable)
            .create()

    private fun row(context: Context, label: String, padded: Boolean = true): TextView =
        TextView(context).apply {
            text = label
            TextViewCompat.setTextAppearance(this, R.style.TextAppearance_Metro_Body)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(context, 52)
            if (padded) {
                setPadding(dp(context, 24), 0, dp(context, 24), 0)
                background = ContextCompat.getDrawable(context, R.drawable.metro_row_pressed)
                isClickable = true
                isFocusable = true
            }
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
