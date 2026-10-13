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
 * Match the common scene engine's actual LayoutRes roles:
 * - ItemToHeader forward: RETAINED = source parent, CURRENT = destination child.
 * - HeaderToItem back: CURRENT = source child, RETAINED = destination parent.
 */
internal fun resolveFolderHierarchyRoleMotion(
    direction: FolderHierarchyDirection,
    retainedRole: Boolean,
    progress: Float,
): FolderHierarchyRoleMotion {
    val p = progress.coerceIn(0f, 1f)
    val forward = direction == FolderHierarchyDirection.FORWARD
    val motion = if (forward) PageMotion.FolderSharedForward else PageMotion.FolderSharedBack
    val engineProgress = if (forward) 1f - p else p
    return FolderHierarchyRoleMotion(
        scale = referenceGenericPivotScale(motion, engineProgress, retainedRole),
        alpha = referenceGenericPivotAlpha(motion, engineProgress, retainedRole),
    )
}
