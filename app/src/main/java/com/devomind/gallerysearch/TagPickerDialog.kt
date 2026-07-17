package com.devomind.gallerysearch

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.devomind.gallerysearch.db.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TagPickerDialog(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val dbRepository: DbRepository,
    private val mediaUri: String,
    private val onTagsChanged: () -> Unit
) {
    private val presetColors = listOf(
        DesignTokens.accent(context),
        Color.parseColor("#34C759"),
        Color.parseColor("#FF9500"),
        Color.parseColor("#FF6B8A"),
        Color.parseColor("#AF52DE"),
        Color.parseColor("#FFD60A")
    )

    private var selectedColor = presetColors[0]
    private var allTags = listOf<TagEntity>()
    private var selectedTagIds = setOf<Long>()

    fun show() {
        lifecycleOwner.lifecycleScope.launch {
            allTags = withContext(Dispatchers.IO) { dbRepository.getAllTags() }
            selectedTagIds = withContext(Dispatchers.IO) {
                dbRepository.getTagsForMedia(mediaUri).map { it.id }.toSet()
            }

            val binding = com.devomind.gallerysearch.databinding.DialogTagPickerBinding.inflate(
                LayoutInflater.from(context)
            )

            renderExistingTags(binding)
            renderColorPresets(binding)
            bindAddButton(binding)

            AlertDialog.Builder(context)
                .setView(binding.root)
                .setPositiveButton("Save") { _, _ ->
                    lifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            dbRepository.setTagsForMedia(mediaUri, selectedTagIds.toList())
                        }
                        onTagsChanged()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun renderExistingTags(binding: com.devomind.gallerysearch.databinding.DialogTagPickerBinding) {
        binding.existingTagsContainer.removeAllViews()
        if (allTags.isEmpty()) {
            val empty = TextView(context).apply {
                text = "No tags yet"
                textSize = 13f
                setTextColor(Color.parseColor("#6F6F6F"))
            }
            binding.existingTagsContainer.addView(empty)
            return
        }

        allTags.forEach { tag ->
            val chip = createChip(tag.name, tag.color, selectedTagIds.contains(tag.id)) {
                selectedTagIds = if (tag.id in selectedTagIds) {
                    selectedTagIds - tag.id
                } else {
                    selectedTagIds + tag.id
                }
                renderExistingTags(binding)
            }
            binding.existingTagsContainer.addView(chip)
        }
    }

    private fun renderColorPresets(binding: com.devomind.gallerysearch.databinding.DialogTagPickerBinding) {
        binding.colorPresetContainer.removeAllViews()
        presetColors.forEach { color ->
            val dot = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                    marginEnd = dp(12)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == selectedColor) {
                        setStroke(dp(2), Color.WHITE)
                    }
                }
                setOnClickListener {
                    selectedColor = color
                    binding.newTagColorDot.setBackgroundColor(color)
                    renderColorPresets(binding)
                }
            }
            binding.colorPresetContainer.addView(dot)
        }
    }

    private fun bindAddButton(binding: com.devomind.gallerysearch.databinding.DialogTagPickerBinding) {
        binding.newTagColorDot.setBackgroundColor(selectedColor)
        binding.addTagBtn.setOnClickListener {
            val name = binding.newTagInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) return@setOnClickListener

            lifecycleOwner.lifecycleScope.launch {
                val newId = withContext(Dispatchers.IO) {
                    dbRepository.addTag(name, selectedColor)
                }
                val refreshed = withContext(Dispatchers.IO) { dbRepository.getAllTags() }
                allTags = refreshed
                selectedTagIds = selectedTagIds + newId
                binding.newTagInput.text?.clear()
                renderExistingTags(binding)
            }
        }
    }

    private fun createChip(name: String, color: Int, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = name
            textSize = 12f
            setTextColor(if (selected) Color.BLACK else color)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(if (selected) color else Color.parseColor("#0A0A0A"))
                setStroke(dp(1), color)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
