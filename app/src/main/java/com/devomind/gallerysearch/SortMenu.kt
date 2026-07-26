package com.devomind.gallerysearch

import android.view.View

/**
 * The sort dropdown, shared by every listing. Anchored under the header's sort label and
 * styled to match the Metro sheets: flat surface, square corners, no elevation, accent
 * on the active row only. Dismisses itself as soon as something is picked.
 */
object SortMenu {

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
        MetroDropdownMenu.show(
            anchor,
            options.map { option ->
                MetroDropdownMenu.Item(
                    label = option.label,
                    selected = option == current
                ) {
                    if (option != current) onSelected(option)
                }
            }
        )
    }
}
