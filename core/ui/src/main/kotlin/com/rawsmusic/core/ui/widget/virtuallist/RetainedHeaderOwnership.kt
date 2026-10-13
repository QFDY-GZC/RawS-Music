package com.rawsmusic.core.ui.widget.virtuallist

/** A parked concrete header may still own the outgoing provider's pixels. */
internal class RetainedHeaderOwnership {
    private var elementId: String? = null

    fun park(elementId: String) {
        this.elementId = elementId
    }

    fun publish(elementId: String?) {
        this.elementId = elementId
    }

    fun owns(elementId: String): Boolean = elementId.isNotBlank() && this.elementId == elementId
}
