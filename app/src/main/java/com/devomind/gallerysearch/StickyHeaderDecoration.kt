package com.devomind.gallerysearch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.devomind.gallerysearch.databinding.ItemTimelineHeaderBinding

class StickyHeaderDecoration(
    private val adapter: ImageAdapter
) : RecyclerView.ItemDecoration() {

    private var headerView: View? = null
    private var headerBinding: ItemTimelineHeaderBinding? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cachedHeaderHeight = 0
    private var lastBoundTitle: String? = null
    private var lastBoundSubtitle: String? = null

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        val firstVisiblePos = (parent.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.findFirstVisibleItemPosition() ?: return

        val headerPos = findStickyHeaderPosition(firstVisiblePos) ?: run {
            headerView = null
            headerBinding = null
            return
        }

        val headerCell = adapter.cells.getOrNull(headerPos) as? GalleryCell.Header ?: run {
            headerView = null
            headerBinding = null
            return
        }

        if (headerView == null || headerBinding == null) {
            val inflater = LayoutInflater.from(parent.context)
            headerBinding = ItemTimelineHeaderBinding.inflate(inflater, parent, false)
            headerView = headerBinding!!.root
            measureHeader(parent)
        }
        if (lastBoundTitle != headerCell.title || lastBoundSubtitle != headerCell.subtitle) {
            headerBinding!!.headerTitle.text = headerCell.title
            headerBinding!!.headerSubtitle.text = headerCell.subtitle
            lastBoundTitle = headerCell.title
            lastBoundSubtitle = headerCell.subtitle
            measureHeader(parent)
        }
        val nextHeaderView = findNextHeader(parent, headerPos)
        var offset = 0
        if (nextHeaderView != null) {
            val nextTop = nextHeaderView.top
            val headerH = headerView!!.measuredHeight
            if (nextTop < headerH) {
                offset = nextTop - headerH
            }
        }

        headerView!!.translationY = offset.toFloat()

        val saveCount = c.save()
        c.translate(0f, 0f)
        headerView!!.draw(c)
        c.restoreToCount(saveCount)
    }

    private fun findStickyHeaderPosition(firstVisiblePos: Int): Int? {
        for (i in firstVisiblePos downTo 0) {
            if (adapter.cells.getOrNull(i) is GalleryCell.Header) {
                return i
            }
        }
        return null
    }

    private fun findNextHeader(parent: RecyclerView, currentHeaderPos: Int): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val pos = parent.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION && pos > currentHeaderPos && adapter.cells.getOrNull(pos) is GalleryCell.Header) {
                return child
            }
        }
        return null
    }

    private fun measureHeader(parent: RecyclerView) {
        val view = headerView ?: return
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parent.width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        cachedHeaderHeight = view.measuredHeight
    }
}
