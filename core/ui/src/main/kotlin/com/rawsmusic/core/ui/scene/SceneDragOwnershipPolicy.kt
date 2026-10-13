package com.rawsmusic.core.ui.scene

import kotlin.math.abs

/** Only an established vertical child drag relinquishes this complete pointer sequence. */
fun shouldYieldSceneDragToChild(consumed: Boolean, dragging: Boolean,
    dx: Float = 0f, dy: Float = 0f, touchSlop: Float = 0f): Boolean =
    consumed && !dragging && abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.25f
