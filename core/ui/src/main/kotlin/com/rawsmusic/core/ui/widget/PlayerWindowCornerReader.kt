package com.rawsmusic.core.ui.widget

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Point
import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs

/** Read window-relative geometry; do not treat an internal window edge as a display corner. */
internal fun readPlayerWindowCornerRadii(view: View, viewport: Rect): PlayerWindowCornerRadii {
    if (viewport.width <= 0f || viewport.height <= 0f) return PlayerWindowCornerRadii.Zero
    if (Build.VERSION.SDK_INT >= 31) {
        val insets = view.rootWindowInsets ?: return PlayerWindowCornerRadii.Zero
        val positions = intArrayOf(
            RoundedCorner.POSITION_TOP_LEFT, RoundedCorner.POSITION_TOP_RIGHT,
            RoundedCorner.POSITION_BOTTOM_RIGHT, RoundedCorner.POSITION_BOTTOM_LEFT,
        )
        val corners = positions.map { insets.getRoundedCorner(it) }
        if (corners.any { it != null }) {
            val radii = corners.mapIndexed { index, corner ->
                corner?.radiusAtViewport(index, viewport) ?: 0f
            }
            return PlayerWindowCornerRadii(radii[0], radii[1], radii[2], radii[3])
        }
    }
    return readXiaomiFallback(view, viewport)
}

@RequiresApi(31)
private fun RoundedCorner.radiusAtViewport(index: Int, viewport: Rect): Float {
    val left = index == 0 || index == 3
    val top = index == 0 || index == 1
    val edgeX = center.x + if (left) -radius else radius
    val edgeY = center.y + if (top) -radius else radius
    return if (
        abs(edgeX - if (left) viewport.left else viewport.right) <= 1f &&
        abs(edgeY - if (top) viewport.top else viewport.bottom) <= 1f
    ) radius.toFloat().coerceAtLeast(0f) else 0f
}

@Suppress("DEPRECATION")
private fun readXiaomiFallback(view: View, viewport: Rect): PlayerWindowCornerRadii {
    if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) &&
        !Build.MANUFACTURER.equals("Redmi", ignoreCase = true) &&
        !Build.MANUFACTURER.equals("POCO", ignoreCase = true)
    ) return PlayerWindowCornerRadii.Zero
    val activity = view.context.findPlayerActivity() ?: return PlayerWindowCornerRadii.Zero
    // The vendor resources have no window-relative centers. Only use them in a full display window.
    if (activity.isInMultiWindowMode) return PlayerWindowCornerRadii.Zero
    val display = view.display ?: return PlayerWindowCornerRadii.Zero
    val screenSize = if (Build.VERSION.SDK_INT >= 30) {
        val current = activity.windowManager.currentWindowMetrics.bounds
        val maximum = activity.windowManager.maximumWindowMetrics.bounds
        if (current != maximum) return PlayerWindowCornerRadii.Zero
        Point(current.width(), current.height())
    } else {
        Point().also { display.getRealSize(it) }
    }
    fun resourceRadius(name: String): Float = runCatching {
        val id = view.resources.getIdentifier(name, "dimen", "android")
        if (id != 0) view.resources.getDimensionPixelSize(id).toFloat().coerceAtLeast(0f) else 0f
    }.getOrDefault(0f)
    // https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1631
    return rotatedPlayerWindowCorners(
        resourceRadius("rounded_corner_radius_top"),
        resourceRadius("rounded_corner_radius_bottom"),
        display.rotation,
    ).touchingEdges(
        left = abs(viewport.left) <= 1f,
        top = abs(viewport.top) <= 1f,
        right = abs(viewport.right - screenSize.x) <= 1f,
        bottom = abs(viewport.bottom - screenSize.y) <= 1f,
    )
}

private fun Context.findPlayerActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        val base = context.baseContext
        if (base === context) return null
        context = base
    }
    return context as? Activity
}
