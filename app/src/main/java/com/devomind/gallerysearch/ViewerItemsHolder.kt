package com.devomind.gallerysearch

import android.graphics.drawable.Drawable

/**
 * Hands the (potentially large) media list to [ViewerActivity] without putting it through an
 * Intent's Binder transaction. A strong reference is held intentionally — a [java.lang.ref.WeakReference]
 * could be collected under memory pressure during the shared-element transition, leaving the viewer
 * empty. [ViewerActivity] clears it via [release] as soon as it has copied the list.
 */
object ViewerItemsHolder {
    private var items: List<GalleryRepository.MediaItem>? = null

    fun store(items: List<GalleryRepository.MediaItem>) {
        this.items = items
    }

    fun retrieve(stringUri: String): List<GalleryRepository.MediaItem>? {
        val current = items ?: return null
        return if (current.any { it.uri.toString() == stringUri }) current else null
    }

    fun release() {
        items = null
    }
}

/**
 * Carries a copy of the tapped grid thumbnail across the one activity launch that owns its shared
 * element. The destination can therefore draw the same image on its first frame instead of holding
 * the transition open for a new, larger decode. It is deliberately one-shot: a recreated viewer or
 * a launch without a matching transition name simply falls back to Glide's normal readiness path.
 */
object ViewerTransitionPreview {
    private var transitionName: String? = null
    private var preview: Drawable? = null

    fun store(name: String, source: Drawable?) {
        transitionName = name
        // Sharing one Drawable between source and destination changes its callback mid-transition.
        // A ConstantState copy shares the pixels safely while each ImageView owns its own Drawable.
        preview = source?.constantState?.newDrawable()?.mutate()
    }

    fun take(name: String?): Drawable? {
        val result = preview.takeIf { name != null && name == transitionName }
        transitionName = null
        preview = null
        return result
    }
}
