package com.rawsmusic.core.ui.widget.virtuallist

/** One-shot positioning, independent of artwork/cover-target availability. */
internal class VirtualListRevealRequestOwner {
    private var requestIndex = -1
    private var applied = false
    private var reported = false

    fun bind(index: Int) {
        if (requestIndex == index) return
        requestIndex = index
        applied = false
        reported = false
    }

    val pendingIndex: Int get() = if (requestIndex >= 0 && !applied) requestIndex else -1
    val targetReportPending: Boolean get() = requestIndex >= 0 && applied && !reported

    fun markScrollApplied(index: Int) {
        if (index == requestIndex) applied = true
    }

    fun markTargetReported(index: Int) {
        if (index == requestIndex) reported = true
    }

    fun scrollForLayout(retainedScrollPx: Int, requestedScrollPx: Int?): Int =
        if (pendingIndex >= 0 && requestedScrollPx != null) requestedScrollPx else retainedScrollPx
}
