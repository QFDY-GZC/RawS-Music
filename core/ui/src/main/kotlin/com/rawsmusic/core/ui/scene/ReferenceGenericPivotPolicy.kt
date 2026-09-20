package com.rawsmusic.core.ui.scene

/**
 * baseline implementation GenericPivot retained/current depth roles.
 *
 * reference player drives CURRENT with f and NEXT/RETAINED with (1-f). Combined with
 * VirtualListGenericPivotTransitionBase's 0.5/1.5 layout flags this yields:
 *
 * Forward: retained visible source 1 -> 1.5, current prepared destination 0.5 -> 1.
 * Back:    current visible source 1 -> 0.5, retained destination 1.5 -> 1.
 *
 * Raw's [retainedLayout] argument names the provider slot, not the visual source. In a back
 * transition the retained slot is the destination, while the current slot is the disappearing
 * source. Keep that role mapping explicit so swapping slots and reversing the scalar cannot invert
 * the curve a second time.
 * Alpha ownership is handled by SceneTransitionEngine; this helper freezes the scale direction so
 * source/destination roles cannot be accidentally swapped again when the animation clock reverses.
 */
internal fun resolveReferenceGenericPivotScale(
    isBack: Boolean,
    retainedLayout: Boolean,
    elapsed: Float,
): Float {
    val p = elapsed.coerceIn(0f, 1f)
    return when {
        !isBack && retainedLayout -> 1f + 0.5f * p
        !isBack && !retainedLayout -> 0.5f + 0.5f * p
        isBack && retainedLayout -> 1.5f - 0.5f * p
        else -> 1f - 0.5f * p
    }
}
