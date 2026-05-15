package com.rawsmusic.core.common.ext

import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.SmoothScroller

fun RecyclerView.smoothScrollToPositionWithOffset(position: Int, offset: Int = 0) {
    val smoothScroller = object : LinearSmoothScroller(context) {
        override fun getVerticalSnapPreference(): Int = SNAP_TO_START
        override fun getHorizontalSnapPreference(): Int = SNAP_TO_START

        override fun calculateDtToFit(
            viewStart: Int, viewEnd: Int, boxStart: Int, boxEnd: Int, snapPreference: Int
        ): Int = offset - viewStart
    }
    smoothScroller.targetPosition = position
    layoutManager?.startSmoothScroll(smoothScroller)
}

fun RecyclerView.addOverscrollEffect() {
    overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
}

fun RecyclerView.disableOverscrollEffect() {
    overScrollMode = RecyclerView.OVER_SCROLL_NEVER
}
