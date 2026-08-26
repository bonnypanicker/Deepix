package com.devomind.gallerysearch

import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.module.AppGlideModule

/**
 * Generates the typed GlideAPI entry point and turns off manifest parsing, which Glide falls
 * back to (with a warning and slower first-load setup) when no AppGlideModule is present.
 */
@GlideModule
class GalleryGlideModule : AppGlideModule() {
    override fun isManifestParsingEnabled(): Boolean = false
}
