package com.devomind.gallerysearch

import android.app.AlertDialog
import android.util.TypedValue
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.devomind.gallerysearch.databinding.DialogPersonIdentityBinding
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.PersonEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Display identity for a person cluster: an optional name plus an optional relationship.
 *
 * Unnamed people deliberately have NO placeholder label: a grid mixing "Person 3", "Sumesh" and
 * "Person 5" reads as broken. Instead, people stay anonymous (face + photo count) until the user
 * either names them or picks a relationship chip — a relationship alone is a complete label
 * ("Mom", "Brother"), so nobody is forced to type.
 */
object PersonIdentity {

    data class Relationship(val key: String, val label: String)

    val Relationships: List<Relationship> = listOf(
        Relationship("me", "Me"),
        Relationship("partner", "Partner"),
        Relationship("father", "Father"),
        Relationship("mother", "Mother"),
        Relationship("brother", "Brother"),
        Relationship("sister", "Sister"),
        Relationship("son", "Son"),
        Relationship("daughter", "Daughter"),
        Relationship("friend", "Friend"),
        Relationship("colleague", "Colleague"),
        Relationship("family", "Family")
    )

    fun relationshipLabel(key: String?): String? =
        key?.let { k -> Relationships.firstOrNull { it.key == k }?.label }

    /** Name if set, else the relationship label, else null (callers show no caption). */
    fun displayName(person: PersonEntity): String? =
        person.nameLabel?.takeIf { it.isNotBlank() } ?: relationshipLabel(person.relationship)
}

/** Editor for a person's [PersonEntity.nameLabel] + [PersonEntity.relationship]. */
object PersonIdentityEditor {

    fun show(activity: AppCompatActivity, person: PersonEntity, onSaved: () -> Unit) {
        val binding = DialogPersonIdentityBinding.inflate(activity.layoutInflater)
        binding.identityName.setText(person.nameLabel ?: "")
        binding.identityName.setSelection(binding.identityName.length())

        var selected = person.relationship
        val accent = resolveColor(activity, R.attr.accentColor)
        val normalText = ContextCompat.getColor(activity, R.color.metroTextPrimary)
        val chips = mutableListOf<Pair<PersonIdentity.Relationship, TextView>>()
        for (relationship in PersonIdentity.Relationships) {
            val chip = activity.layoutInflater.inflate(
                R.layout.item_search_chip, binding.identityRelationshipChips, false
            ) as TextView
            chip.text = relationship.label
            chip.setOnClickListener {
                selected = if (selected == relationship.key) null else relationship.key
                refreshChips(chips, selected, accent, normalText)
            }
            chips += relationship to chip
            binding.identityRelationshipChips.addView(chip)
        }
        refreshChips(chips, selected, accent, normalText)

        AlertDialog.Builder(activity, R.style.Theme_GallerySearch_Dialog)
            .setTitle("Who is this?")
            .setView(binding.root)
            .setPositiveButton("Save") { _, _ ->
                val name = binding.identityName.text.toString().trim().ifBlank { null }
                val relationship = selected
                activity.lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        GalleryDatabase.getInstance(activity.applicationContext)
                            .personDao().updateIdentity(person.personId, name, relationship)
                    }
                    onSaved()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshChips(
        chips: List<Pair<PersonIdentity.Relationship, TextView>>,
        selected: String?,
        accent: Int,
        normalText: Int
    ) {
        for ((relationship, chip) in chips) {
            val isSelected = relationship.key == selected
            chip.setTextColor(if (isSelected) accent else normalText)
            chip.background.alpha = if (isSelected) 255 else 140
        }
    }

    private fun resolveColor(activity: AppCompatActivity, attr: Int): Int {
        val value = TypedValue()
        activity.theme.resolveAttribute(attr, value, true)
        return value.data
    }
}
