package com.rawsmusic.core.ui.decoration

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.rawsmusic.core.common.utils.UiUtils

class GridSpacingItemDecoration(
    context: Context,
    private val spanCount: Int,
    dpSpacing: Float = 8f,
    private val includeEdge: Boolean = true
) : RecyclerView.ItemDecoration() {

    private val spacing = UiUtils.dpToPx(context, dpSpacing).toInt()

    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        val column = position % spanCount

        if (includeEdge) {
            outRect.left = spacing - column * spacing / spanCount
            outRect.right = (column + 1) * spacing / spanCount
            if (position < spanCount) outRect.top = spacing
            outRect.bottom = spacing
        } else {
            outRect.left = column * spacing / spanCount
            outRect.right = spacing - (column + 1) * spacing / spanCount
            if (position >= spanCount) outRect.top = spacing
        }
    }
}

class LinearSpacingItemDecoration(
    context: Context,
    dpSpacing: Float = 8f,
    private val includeTop: Boolean = true,
    private val includeBottom: Boolean = true
) : RecyclerView.ItemDecoration() {

    private val spacing = UiUtils.dpToPx(context, dpSpacing).toInt()

    override fun getItemOffsets(
        outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == 0 && includeTop) outRect.top = spacing
        outRect.bottom = spacing
        outRect.left = spacing
        outRect.right = spacing
    }
}
