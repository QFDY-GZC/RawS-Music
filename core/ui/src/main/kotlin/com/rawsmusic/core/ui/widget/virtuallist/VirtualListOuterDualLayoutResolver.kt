package com.rawsmusic.core.ui.widget.virtuallist

/**
 * Step 4B helpers for outer HOME/category GenericPivot.
 *
 * Reference baseline implementation keeps one physical item View with two LayoutRes slots. When both LayoutRes are
 * valid, the View resolver interpolates the two raw LayoutRes using one transition fraction. Raw's
 * existing CURRENT/RETAINED role alphas are complementary projections of that same GenericPivot
 * fraction, so normalizing them yields the CURRENT layout weight without teaching VirtualList about
 * navigation direction. Single-role holders keep their existing role-local mode=4 transform.
 */
internal enum class VirtualListOuterDualLayoutKind {
    SHARED,
    CURRENT_ONLY,
    RETAINED_ONLY,
}

internal fun virtualListOuterDualLayoutKind(
    current: VirtualListPhysicalLayoutRes?,
    retained: VirtualListPhysicalLayoutRes?,
): VirtualListOuterDualLayoutKind? = when {
    current != null && retained != null -> VirtualListOuterDualLayoutKind.SHARED
    current != null -> VirtualListOuterDualLayoutKind.CURRENT_ONLY
    retained != null -> VirtualListOuterDualLayoutKind.RETAINED_ONLY
    else -> null
}

internal fun virtualListOuterCurrentLayoutFractionFromAlphas(
    currentAlpha: Float,
    retainedAlpha: Float,
): Float {
    val current = currentAlpha.coerceIn(0f, 1f)
    val retained = retainedAlpha.coerceIn(0f, 1f)
    val total = current + retained
    return if (total > 0.0001f) (current / total).coerceIn(0f, 1f) else 1f
}
