package com.devomind.gallerysearch

import androidx.annotation.FontRes

/**
 * The typefaces offered by the Settings "fonts" test page. Each entry is a family bundled in
 * `res/font`, paired with the name shown beside its sample. [Default] is the one the app ships with.
 */
enum class AppFont(
    val displayName: String,
    @FontRes val fontRes: Int
) {
    NotoSans("Noto Sans", R.font.noto_sans_regular),
    OpenSans("Open Sans", R.font.open_sans_regular),
    Inter("Inter", R.font.inter_regular),
    Selawik("Selawik", R.font.selawik_regular),
    PtSans("PT Sans", R.font.pt_sans_regular),
    Lato("Lato", R.font.lato_regular);

    companion object {
        val Default = OpenSans

        /** Resolves a stored [name], falling back to [Default] for anything unknown. */
        fun fromName(stored: String?): AppFont =
            entries.firstOrNull { it.name == stored } ?: Default
    }
}
