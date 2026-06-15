package com.devomind.gallerysearch
import android.content.Context
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.EditText
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.view.ViewGroup

class TagPickerDialog(
    private val context: Context,
    private val dbRepository: DbRepository,
    private val uri: String,
    private val scope: kotlinx.coroutines.CoroutineScope
) {
    fun show() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_tag_picker, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.tagRecycler)
        val input = dialogView.findViewById<EditText>(R.id.newTagInput)
        val addBtn = dialogView.findViewById<Button>(R.id.addTagBtn)

        recycler.layoutManager = LinearLayoutManager(context)
        val adapter = TagAdapter(emptyList(), emptySet(), { tag, checked ->
            scope.launch {
                if (checked) dbRepository.tagMedia(uri, tag)
                else dbRepository.untagMedia(uri, tag)
            }
        }, { tag ->
            scope.launch {
                dbRepository.deleteTag(tag)
                dbRepository.clearAllMediaForTag(tag)
                refreshDialog(recycler)
            }
        })
        recycler.adapter = adapter

        val dialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        addBtn.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) {
                scope.launch {
                    dbRepository.addTag(name)
                    dbRepository.tagMedia(uri, name)
                    withContext(kotlinx.coroutines.Dispatchers.Main) { input.text.clear() }
                    refreshDialog(recycler)
                }
            }
        }

        dialog.show()
        refreshDialog(recycler)
    }

    private fun refreshDialog(recycler: RecyclerView) {
        scope.launch {
            val tags = dbRepository.getAllTags()
            val assigned = dbRepository.getTagsForMedia(uri).toSet()
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                (recycler.adapter as? TagAdapter)?.update(tags.map { it.name }, assigned)
            }
        }
    }

    private class TagAdapter(
        var tags: List<String>,
        var assigned: Set<String>,
        val onToggle: (String, Boolean) -> Unit,
        val onDelete: (String) -> Unit
    ) : RecyclerView.Adapter<TagAdapter.VH>() {
        class VH(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
            val checkBox = itemView.findViewById<CheckBox>(R.id.tagCheck)
            val deleteBtn = itemView.findViewById<ImageButton>(R.id.tagDelete)
        }

        override fun getItemCount() = tags.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tag, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val tag = tags[position]
            holder.checkBox.text = tag
            holder.checkBox.isChecked = assigned.contains(tag)
            holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                onToggle(tag, isChecked)
            }
            holder.deleteBtn.setOnClickListener { onDelete(tag) }
        }

        fun update(newTags: List<String>, newAssigned: Set<String>) {
            tags = newTags
            assigned = newAssigned
            notifyDataSetChanged()
        }
    }
}
