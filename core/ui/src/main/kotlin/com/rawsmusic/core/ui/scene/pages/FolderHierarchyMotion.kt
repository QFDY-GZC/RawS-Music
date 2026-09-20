package com.rawsmusic.core.ui.scene.pages

import com.rawsmusic.core.ui.scene.PageMotion
import com.rawsmusic.core.ui.scene.referenceGenericPivotScale
import com.rawsmusic.core.ui.scene.referenceGenericPivotAlpha

internal enum class FolderHierarchyDirection { FORWARD, BACK }

internal data class FolderHierarchyRoleMotion(
    val scale: Float,
    val alpha: Float,
)

/**
 * Reference item<->header role endpoints used by Folders Hierarchy navigation.
 *
 * This local hierarchy owner always defines RETAINED as the visible source and CURRENT as the
 * prepared destination, regardless of navigation direction. Normalize those roles to the common
 * scene engine instead of maintaining a second (and potentially reversed) scale/alpha law.
 */
internal fun resolveFolderHierarchyRoleMotion(
    direction: FolderHierarchyDirection,
    retainedRole: Boolean,
    progress: Float,
): FolderHierarchyRoleMotion {
    val p = progress.coerceIn(0f, 1f)
    val forward = direction == FolderHierarchyDirection.FORWARD
    val motion = if (forward) PageMotion.FolderSharedForward else PageMotion.FolderSharedBack
    // The scene engine's back retained role is the destination; hierarchy names roles by
    // source/destination regardless of direction. Normalize once and use the exact shared law.
    val engineProgress = if (forward) 1f - p else p
    val engineSourceLayout = if (forward) retainedRole else !retainedRole
    return FolderHierarchyRoleMotion(
        scale = referenceGenericPivotScale(motion, engineProgress, engineSourceLayout),
        alpha = referenceGenericPivotAlpha(motion, engineProgress, engineSourceLayout),
    )
}
