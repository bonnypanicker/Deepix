package com.devomind.gallerysearch

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The sizing decisions that depend on the shape of the window rather than on the device.
 *
 * Width and height are answered separately, on purpose, because "landscape" is only the usual way to
 * run into them. A phone turned sideways has width to spare and almost no height; a desktop or
 * split-screen window can be either; a foldable's cover screen is small on both. So the grid asks how
 * much wider the window is than the width the user's column preference was chosen against, and the
 * chrome asks how much height is left over to be chrome in.
 *
 * The reference for that first question is `smallestScreenWidthDp`, which does not move when the
 * device rotates — which is exactly what makes it usable as the portrait baseline a preference means.
 * Reading `displayMetrics.widthPixels` for either question is what this app did before, and it is why
 * a sideways phone drew a mosaic whose tiles were twice as tall as the screen could show.
 */
object Responsive {

    const val MAX_GRID_COLUMNS = 12

    /** Under this many dp of height, a screen's chrome has to give way to its content. */
    const val CRAMPED_HEIGHT_DP = 480

    /** Landscape by either definition; a square-ish window is reported as whatever it started as. */
    fun sideways(context: Context): Boolean =
        context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** True when a 40sp hero title and a 48dp margin are no longer affordable. */
    fun cramped(context: Context): Boolean = heightDp(context) < CRAMPED_HEIGHT_DP

    fun widthDp(context: Context): Int = context.resources.configuration.screenWidthDp

    fun heightDp(context: Context): Int = context.resources.configuration.screenHeightDp

    /**
     * Photo columns for a preference of [preferredColumns] chosen at the reference width: tiles keep
     * the size they have standing up, and the extra width becomes more of them. Never fewer than the
     * preference — a wide window is not a licence to make tiles huge — and capped, because the span
     * canvas is shared with card rows that have to divide into it.
     */
    fun gridColumns(context: Context, preferredColumns: Int): Int =
        gridColumnsFor(widthDp(context), context.resources.configuration.smallestScreenWidthDp, preferredColumns)

    /** The same decision without a window to read, so a wrong boundary is a failed test, not a phone. */
    fun gridColumnsFor(widthDp: Int, referenceWidthDp: Int, preferredColumns: Int): Int {
        val ratio = if (referenceWidthDp > 0) widthDp.toFloat() / referenceWidthDp else 1f
        return (preferredColumns * ratio).roundToInt().coerceIn(preferredColumns, MAX_GRID_COLUMNS)
    }

    /**
     * Album cards are wider than a tile and are sized in tiles: this is how many tile spans one card
     * occupies at the density the user chose. Derived from the preference rather than from the live
     * canvas, which is what lets a wider window add cards instead of enlarging them.
     */
    fun albumCardSpan(preferredColumns: Int): Int = (preferredColumns / 2).coerceAtLeast(1)

    /**
     * The width a justified row measures its tile extent against: the live row width while the window
     * is at or below the reference, the reference beyond it. Rows fill whatever width they are handed,
     * so this only limits how large a tile may get — the difference between three oversized tiles per
     * row sideways and the same tiles the user was already seeing, twice as many of them.
     */
    fun collageExtentWidthPx(context: Context, rowWidthPx: Int): Int =
        collageExtentWidthPxFor(rowWidthPx, referenceWidthPx(context))

    fun collageExtentWidthPxFor(rowWidthPx: Int, referencePx: Int): Int =
        if (referencePx > 0) minOf(rowWidthPx, referencePx) else rowWidthPx

    fun referenceWidthPx(context: Context): Int =
        (context.resources.configuration.smallestScreenWidthDp * context.resources.displayMetrics.density).toInt()

    /** The grid width in px, from the window's dp — what a cell divides into columns of. */
    fun widthPx(context: Context): Int = (widthDp(context) * context.resources.displayMetrics.density).toInt()

    /**
     * Height budget for a body that has to stay scrollable and reachable — a dialog's list, a viewer's
     * information sheet. [naturalLimitPx] is the size the layout wants when there is room for it; past
     * that the window decides, keeping [maxHeightFraction] of it for the body and the rest for the
     * title, buttons and whatever is behind the sheet.
     */
    fun bodyHeightPx(context: Context, naturalLimitPx: Int, maxHeightFraction: Float = 0.72f): Int =
        bodyHeightPxFor(
            heightDp(context),
            context.resources.displayMetrics.density,
            naturalLimitPx,
            maxHeightFraction
        )

    fun bodyHeightPxFor(heightDp: Int, density: Float, naturalLimitPx: Int, maxHeightFraction: Float): Int {
        val available = (heightDp * density * maxHeightFraction).toInt()
        return minOf(naturalLimitPx, available).coerceAtLeast(1)
    }

    /**
     * The span a card takes when [cardsPerRow] of them share a canvas of [totalSpans]. Floored, so a
     * row that cannot be divided exactly leaves its remainder as a gutter instead of widening every
     * card and pushing the last one onto a row of its own.
     */
    fun cardSpan(totalSpans: Int, cardsPerRow: Int): Int =
        (totalSpans / cardsPerRow.coerceAtLeast(1)).coerceAtLeast(1).coerceAtMost(totalSpans.coerceAtLeast(1))

    /**
     * A text size for a screen title: full-size when there is height to spend, reduced when the window
     * is short enough that the title would be a larger part of the screen than the content is.
     */
    fun titleTextSp(fullSizeSp: Float, context: Context): Float =
        titleTextSpFor(heightDp(context), fullSizeSp)

    fun titleTextSpFor(heightDp: Int, fullSizeSp: Float): Float =
        if (heightDp < CRAMPED_HEIGHT_DP) (fullSizeSp * 0.65f).coerceAtLeast(20f) else fullSizeSp

    /**
     * Applies [titleTextSp] to a title that carries its full size in XML. Called on the way in and
     * again when the window changes shape: a declared `configChanges` means the layout is never
     * re-inflated, so the attribute is only ever the size the screen started with.
     */
    fun applyTitleText(title: TextView, context: Context, fullSizeSp: Float) {
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleTextSp(fullSizeSp, context))
    }

    /** The side a square tile gets when [columns] of them share [widthPx]. */
    fun tileSizePx(widthPx: Int, columns: Int): Int = (widthPx / columns.coerceAtLeast(1)).coerceAtLeast(1)

    /**
     * How many cards at least [minCardWidthPx] across fit in [widthPx]. A card whose content has a
     * size of its own — a 92dp cover, a label that needs two words — cannot be spread over the window
     * by a ratio without being clipped, so its row count comes from what genuinely fits, capped at
     * [maxCards] so a desktop window does not reduce a face to a few pixels.
     */
    fun cardsFitting(widthPx: Int, minCardWidthPx: Int, maxCards: Int): Int =
        (widthPx / minCardWidthPx.coerceAtLeast(1)).coerceIn(1, maxCards.coerceAtLeast(1))

    /**
     * The width a grid really has. Falls back to the window while the view is pre-layout, which is
     * the same number the screen was measured against before there was anything to measure.
     */
    fun widthOf(view: View): Int =
        if (view.width > 0) view.width else view.resources.displayMetrics.widthPixels
}
